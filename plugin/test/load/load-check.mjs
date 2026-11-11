// @ts-check
/**
 * 加载检查：把插件装进真实的 `dsh web`，确认 DSH 加载了它并且处于运行状态，
 * 并且局域网 HTTPS 端口能按证书指纹访问 /v1/info。
 * 然后占住插件的局域网端口、重启 DSH，确认 DSH 把插件标记为失败，并在插件列表中给出端口被占用的说明。
 *
 * 用法：
 *   npm run load-check                     # 使用基线版本（src/dsh/versions.js）
 *   DSH_VERSION=0.2.3 npm run load-check   # 测试其他版本
 *   DSH_BIN=/path/to/dsh-entry.js npm run load-check   # 使用已安装的 DSH（指向 JS 入口文件）
 *   DSH_INSTALL_DIR=/path/to/dir npm run load-check    # 把 DSH 装在这个目录并复用（CI 用它做缓存）
 *   DSHREMOTE_PLUGIN_PACKAGE=dist/dshremote-plugin-X.Y.Z.tgz npm run load-check
 *                                          # 用安装包（npm run pack）离线安装，额外检查装进去的是安装包的副本
 *
 * 前置条件：PATH 上要有 pnpm。npm 版的 dsh 安装插件时会调用 pnpm。
 *
 * 输出一行 JSON 结果（stdout 最后一行），退出码 0 表示通过。
 * 跨平台：Windows 和 Linux 上都直接用 node 运行 DSH 的 JS 入口，不依赖 shell 和 .cmd 包装。
 */
import { readFileSync, lstatSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import http from 'node:http'
import net from 'node:net'
import { randomUUID } from 'node:crypto'
import { DEFAULT_CONFIG } from '../../src/config.js'
import { spkiFingerprint } from '../../src/tls/certificate.js'
import { CERT_FILE } from '../../src/tls/identity.js'
import { pinnedRequest } from '../helpers/pinned-client.js'
import { startDshWeb, DSH_VERSION, freePort, PLUGIN_DIR, PLUGIN_SOURCE } from '../helpers/dsh-runtime.mjs'

/** @param {URL} launch @param {string} path @param {{ method?: string, headers?: Record<string,string>, body?: string }} [options] */
function request(launch, path, options = {}) {
  return new Promise((resolveRequest, reject) => {
    const req = http.request(`http://${launch.host}${path}`, {
      method: options.method ?? 'GET',
      headers: { host: launch.host, ...options.headers },
    }, (res) => {
      let body = ''
      res.on('data', (chunk) => { body += chunk })
      res.on('end', () => resolveRequest({ status: res.statusCode ?? 0, headers: res.headers, body }))
    })
    req.on('error', reject)
    if (options.body) req.write(options.body)
    req.end()
  })
}

/**
 * 读取 DSH 插件列表（pluginInventory/list）中的 dshremote 一行。
 * @param {URL} launch
 * @returns {Promise<{ entry?: { moduleName: string, enabled: boolean, fiberPhase: string | null, diagnostic?: string }, error?: string }>}
 */
async function readEntry(launch) {
  const login = /** @type {{ headers: import('node:http').IncomingHttpHeaders }} */ (
    await request(launch, `/?token=${launch.searchParams.get('token')}`))
  const cookie = login.headers['set-cookie']?.[0]?.split(';')[0]
  if (!cookie) return { error: '无法用启动 token 登录 DSH' }
  const endpoint = 'pluginInventory/list'
  const res = /** @type {{ body: string }} */ (await request(launch, `/api/${endpoint}`, {
    method: 'POST',
    headers: { cookie, 'content-type': 'application/json' },
    body: JSON.stringify({ type: 'client-request', rpcId: randomUUID(), method: endpoint, payload: { args: {} } }),
  }))
  const result = JSON.parse(res.body).result
  if (!result?.ok) return { error: `${endpoint} 调用失败：${JSON.stringify(result?.error)}` }
  /** @type {{ moduleName: string, enabled: boolean, fiberPhase: string | null, diagnostic?: string }[]} */
  const entries = result.value.entries
  const entry = entries.find((e) => e.moduleName === 'dshremote')
  return entry ? { entry } : { error: 'DSH 的插件列表中没有 dshremote' }
}

/** @returns {Promise<{ ok: boolean, detail: string, phase?: string | null }>} */
async function checkLoaded(/** @type {URL} */ launch) {
  const { entry, error } = await readEntry(launch)
  if (!entry) return { ok: false, detail: String(error) }
  const ok = entry.enabled === true && entry.fiberPhase === 'active'
  return { ok, phase: entry.fiberPhase, detail: ok ? '已加载并处于 active 状态' : `插件状态异常：enabled=${entry.enabled} fiberPhase=${entry.fiberPhase}` }
}

/**
 * 读取插件生成的证书，按指纹访问局域网端口上的 /v1/info。
 * @param {string} dshHome
 * @returns {Promise<{ ok: boolean, detail: string }>}
 */
async function checkLanInfo(dshHome) {
  const certPath = join(dshHome, 'dshremote', CERT_FILE)
  // 插件在后台启动服务，稍等片刻
  for (let attempt = 0; attempt < 30; attempt++) {
    try {
      const fingerprint = spkiFingerprint(readFileSync(certPath, 'utf8'))
      const res = await pinnedRequest({ host: '127.0.0.1', port: ports.lan, fingerprint, path: '/v1/info' })
      const body = res.json()
      if (res.status === 200 && body.protocol === 1) {
        if (body.dsh?.version !== DSH_VERSION) return { ok: false, detail: `/v1/info 报告的 DSH 版本不对：${JSON.stringify(body.dsh)}` }
        return { ok: true, detail: `/v1/info 正常（plugin ${body.pluginVersion}，dsh ${body.dsh.version}，compatible=${body.dsh.compatible}）` }
      }
      return { ok: false, detail: `/v1/info 返回 ${res.status}` }
    } catch (error) {
      if (attempt === 29) return { ok: false, detail: `无法访问局域网端口：${error instanceof Error ? error.message : String(error)}` }
      await new Promise((r) => setTimeout(r, 500))
    }
  }
  return { ok: false, detail: '无法访问局域网端口' }
}

/**
 * 配对页能打开，显示的指纹与证书一致；非本机 Host 被拒绝。
 * @param {string} dshHome
 * @returns {Promise<{ ok: boolean, detail: string }>}
 */
async function checkAdminPage(dshHome) {
  const fingerprint = spkiFingerprint(readFileSync(join(dshHome, 'dshremote', CERT_FILE), 'utf8'))
  const base = new URL(`http://127.0.0.1:${ports.admin}/?token=x`)
  try {
    const page = /** @type {{ status: number, body: string }} */ (await request(base, '/'))
    if (page.status !== 200) return { ok: false, detail: `配对页返回 ${page.status}` }
    if (!page.body.includes(fingerprint.slice(8))) return { ok: false, detail: '配对页上的指纹与证书不一致' }
    const rebinding = /** @type {{ status: number }} */ (await request(base, '/', { headers: { host: 'evil.example' } }))
    if (rebinding.status !== 403) return { ok: false, detail: `非本机 Host 没有被拒绝（${rebinding.status}）` }
    // 插件通过适配层连上 DSH 后，配对页显示「已连接」
    let state = ''
    for (let attempt = 0; attempt < 40; attempt++) {
      const again = /** @type {{ body: string }} */ (await request(base, '/'))
      state = again.body.match(/data-state="([a-z]+)"/)?.[1] ?? ''
      if (state === 'ready') break
      await new Promise((r) => setTimeout(r, 500))
    }
    if (state !== 'ready') return { ok: false, detail: `插件没有连上 DSH（state=${state || '无'}）` }
    if (!page.body.includes(DSH_VERSION)) return { ok: false, detail: '配对页上没有显示 DSH 版本' }
    return { ok: true, detail: '配对页正常，指纹一致，非本机 Host 被拒绝，已连接 DSH' }
  } catch (error) {
    return { ok: false, detail: `无法访问配对页：${error instanceof Error ? error.message : String(error)}` }
  }
}

/**
 * 局域网端口被其他程序占用时，DSH 把插件标记为失败，插件列表中的诊断信息指出是哪个端口；配对页也不会启动。
 * @param {URL} launch
 * @returns {Promise<{ ok: boolean, detail: string }>}
 */
async function checkPortConflictReported(launch) {
  /** @type {Awaited<ReturnType<typeof readEntry>>} */
  let read = {}
  // 插件启动是异步的，等它从 pending / loading 变成 failed
  for (let attempt = 0; attempt < 40; attempt++) {
    read = await readEntry(launch)
    if (read.entry?.fiberPhase === 'failed') break
    await new Promise((r) => setTimeout(r, 500))
  }
  const entry = read.entry
  if (!entry) return { ok: false, detail: String(read.error) }
  if (entry.fiberPhase !== 'failed') return { ok: false, detail: `端口被占用时插件状态应为 failed，实际为 ${entry.fiberPhase}` }
  const expected = `局域网端口 ${ports.lan} 已被其他程序占用`
  if (!entry.diagnostic?.includes(expected)) return { ok: false, detail: `插件列表的诊断信息中没有「${expected}」：${JSON.stringify(entry.diagnostic)}` }
  if (await portBusyHint(ports.admin, '127.0.0.1')) return { ok: false, detail: '局域网端口失败时配对页不应启动' }
  return { ok: true, detail: `插件被标记为 failed，诊断信息：${entry.diagnostic}` }
}

/** @param {number} port @param {string} host @returns {Promise<net.Server>} */
function occupy(port, host) {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(port, host, () => resolve(server))
  })
}

/** 插件这次使用的端口（main 中选定） */
const ports = { lan: 0, admin: 0 }

/**
 * 插件的端口被其他程序占用时，插件会启动失败（DSH 把它标记为 failed）。
 * 检查失败时试着绑定一下端口，把「被占用」和查看占用程序的命令直接告诉用户。
 * @param {number} port @param {string} host
 * @returns {Promise<string>} 空字符串表示端口空闲
 */
function portBusyHint(port, host) {
  return new Promise((resolve) => {
    const probe = net.createServer()
    probe.once('error', (error) => {
      const code = /** @type {NodeJS.ErrnoException} */ (error).code
      resolve(code === 'EADDRINUSE'
        ? `端口 ${port} 已被其他程序占用，插件因此没能启动。Linux 上用 ss -ltnp 'sport = :${port}' 查看是哪个程序（Windows 上用 Get-NetTCPConnection -LocalPort ${port}）`
        : `绑定端口 ${port} 失败（${code}）`)
    })
    probe.listen(port, host, () => probe.close(() => resolve('')))
  })
}

/** @param {{ ok: boolean, detail?: string }} result @param {number} port @param {string} host */
async function withPortHint(result, port, host) {
  if (result.ok) return result
  const hint = await portBusyHint(port, host)
  return hint ? { ...result, detail: `${result.detail}。${hint}` } : result
}

/**
 * 用安装包安装时：profile 里的插件应该是安装包的副本（不是指向源码的链接），版本一致，运行时依赖在包里。
 * @param {string} dshHome
 * @returns {{ ok: boolean, detail: string }}
 */
function checkInstalledFromPackage(dshHome) {
  const installed = join(dshHome, 'profiles', 'web', 'node_modules', 'dshremote')
  if (!existsSync(join(installed, 'package.json'))) return { ok: false, detail: `profile 中没有 dshremote：${installed}` }
  if (lstatSync(installed).isSymbolicLink()) return { ok: false, detail: 'profile 中的 dshremote 是符号链接，不是安装包的副本' }
  const version = JSON.parse(readFileSync(join(installed, 'package.json'), 'utf8')).version
  const expected = JSON.parse(readFileSync(join(PLUGIN_DIR, 'package.json'), 'utf8')).version
  if (version !== expected) return { ok: false, detail: `装进去的版本是 ${version}，源码中是 ${expected}` }
  if (!existsSync(join(installed, 'node_modules', 'ws', 'package.json'))) return { ok: false, detail: '安装包中没有打包运行时依赖 ws' }
  return { ok: true, detail: `安装包的副本，版本 ${version}，ws 打包在内` }
}

async function main() {
  /** @type {import('../helpers/dsh-runtime.mjs').DshWeb | undefined} */
  let web
  try {
    // 端口用临时找到的空闲端口，其余配置用默认值；关掉「第一次启动时自动打开浏览器」
    ports.lan = await freePort()
    ports.admin = await freePort()
    web = await startDshWeb({ withPlugin: true, pluginConfig: { ...DEFAULT_CONFIG, lanPort: ports.lan, adminPort: ports.admin, openAdminOnFirstRun: false } })
    const installedFrom = PLUGIN_SOURCE !== PLUGIN_DIR ? checkInstalledFromPackage(web.dshHome) : { ok: true, detail: '源码目录' }
    const loaded = await checkLoaded(web.launch)
    const lan = loaded.ok ? await withPortHint(await checkLanInfo(web.dshHome), ports.lan, DEFAULT_CONFIG.lanBind) : { ok: false, detail: '插件未加载，跳过局域网检查' }
    const admin = loaded.ok ? await checkAdminPage(web.dshHome) : { ok: false, detail: '插件未加载，跳过配对页检查' }
    // 占住插件的局域网端口，沿用同一个 DSH_HOME 和插件配置重启 DSH
    /** @type {{ ok: boolean, detail: string }} */
    let conflict = { ok: false, detail: '插件未正常加载，跳过端口占用检查' }
    if (loaded.ok && lan.ok && admin.ok) {
      await web.stop({ keepFiles: true })
      const blocker = await occupy(ports.lan, DEFAULT_CONFIG.lanBind)
      try {
        web = await startDshWeb({ withPlugin: true, reuse: web.work })
        // 插件启动失败只能让插件自己失败，不能让整个 DSH 退出
        const exited = web.exited.then((code) => ({ ok: false, detail: `DSH 退出了（code=${code}）：插件启动失败让整个 DSH 退出了，apply 是不是 async 函数？` }))
        conflict = await Promise.race([
          checkPortConflictReported(web.launch).catch((error) => ({ ok: false, detail: String(error instanceof Error ? error.message : error) })),
          exited,
        ])
      } finally {
        await new Promise((r) => blocker.close(r))
      }
    }
    // results 和 contract、e2e 的格式一致，供兼容性检测（scripts/dsh-compat.mjs）统一解析
    const results = [
      { name: '插件加载', ok: loaded.ok, detail: `phase=${loaded.phase} ${loaded.detail ?? ''}`.trim() },
      { name: '局域网接口', ...lan },
      { name: '配对页', ...admin },
      { name: '端口被占用时插件显示为失败', ...conflict },
    ]
    if (PLUGIN_SOURCE !== PLUGIN_DIR) results.unshift({ name: '从安装包安装', ...installedFrom })
    const ok = results.every((r) => r.ok)
    console.log(JSON.stringify({ check: 'load', dshVersion: web.version, platform: process.platform, ok, phase: loaded.phase, detail: loaded.detail, lan, admin, conflict, results }))
    process.exitCode = ok ? 0 : 1
  } catch (error) {
    const detail = String(error instanceof Error ? error.message : error)
    console.log(JSON.stringify({ check: 'load', dshVersion: DSH_VERSION, platform: process.platform, ok: false, detail, results: [{ name: '运行加载检查', ok: false, detail }] }))
    process.exitCode = 1
  } finally {
    await web?.stop()
  }
}

await main()
