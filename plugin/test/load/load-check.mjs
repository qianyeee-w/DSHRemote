// @ts-check
/**
 * 加载检查：把插件装进真实的 `dsh web`，确认 DSH 加载了它并且处于运行状态，
 * 并且局域网 HTTPS 端口能按证书指纹访问 /v1/info。
 *
 * 用法：
 *   npm run load-check                     # 使用基线版本（src/dsh/versions.js）
 *   DSH_VERSION=0.2.3 npm run load-check   # 测试其他版本
 *   DSH_BIN=/path/to/dsh-entry.js npm run load-check   # 使用已安装的 DSH（指向 JS 入口文件）
 *   DSH_INSTALL_DIR=/path/to/dir npm run load-check    # 把 DSH 装在这个目录并复用（CI 用它做缓存）
 *
 * 前置条件：PATH 上要有 pnpm。npm 版的 dsh 安装插件时会调用 pnpm。
 *
 * 输出一行 JSON 结果（stdout 最后一行），退出码 0 表示通过。
 * 跨平台：Windows 和 Linux 上都直接用 node 运行 DSH 的 JS 入口，不依赖 shell 和 .cmd 包装。
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import http from 'node:http'
import net from 'node:net'
import { randomUUID } from 'node:crypto'
import { DEFAULT_CONFIG } from '../../src/config.js'
import { spkiFingerprint } from '../../src/tls/certificate.js'
import { CERT_FILE } from '../../src/tls/identity.js'
import { pinnedRequest } from '../helpers/pinned-client.js'
import { startDshWeb, DSH_VERSION } from '../helpers/dsh-runtime.mjs'

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

/** @returns {Promise<{ ok: boolean, detail: string, phase?: string | null }>} */
async function checkLoaded(/** @type {URL} */ launch) {
  const login = /** @type {{ headers: import('node:http').IncomingHttpHeaders }} */ (
    await request(launch, `/?token=${launch.searchParams.get('token')}`))
  const cookie = login.headers['set-cookie']?.[0]?.split(';')[0]
  if (!cookie) return { ok: false, detail: '无法用启动 token 登录 DSH' }
  const endpoint = 'pluginInventory/list'
  const res = /** @type {{ body: string }} */ (await request(launch, `/api/${endpoint}`, {
    method: 'POST',
    headers: { cookie, 'content-type': 'application/json' },
    body: JSON.stringify({ type: 'client-request', rpcId: randomUUID(), method: endpoint, payload: { args: {} } }),
  }))
  const result = JSON.parse(res.body).result
  if (!result?.ok) return { ok: false, detail: `${endpoint} 调用失败：${JSON.stringify(result?.error)}` }
  /** @type {{ moduleName: string, enabled: boolean, fiberPhase: string | null }[]} */
  const entries = result.value.entries
  const entry = entries.find((e) => e.moduleName === 'dshremote')
  if (!entry) return { ok: false, detail: 'DSH 的插件列表中没有 dshremote' }
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
      const res = await pinnedRequest({ host: '127.0.0.1', port: DEFAULT_CONFIG.lanPort, fingerprint, path: '/v1/info' })
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
  const base = new URL(`http://127.0.0.1:${DEFAULT_CONFIG.adminPort}/?token=x`)
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
 * 插件的端口被其他程序占用时，插件会启动失败，但 DSH 仍然把它显示为 active，也不打印错误。
 * 检查失败时试着绑定一下端口，把「被占用」直接告诉用户。
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

async function main() {
  /** @type {import('../helpers/dsh-runtime.mjs').DshWeb | undefined} */
  let web
  try {
    // 关掉「第一次启动时自动打开浏览器」，其余配置用默认值
    web = await startDshWeb({ withPlugin: true, pluginConfig: { ...DEFAULT_CONFIG, openAdminOnFirstRun: false } })
    const loaded = await checkLoaded(web.launch)
    const lan = loaded.ok ? await withPortHint(await checkLanInfo(web.dshHome), DEFAULT_CONFIG.lanPort, DEFAULT_CONFIG.lanBind) : { ok: false, detail: '插件未加载，跳过局域网检查' }
    const admin = loaded.ok ? await checkAdminPage(web.dshHome) : { ok: false, detail: '插件未加载，跳过配对页检查' }
    const ok = loaded.ok && lan.ok && admin.ok
    console.log(JSON.stringify({ check: 'load', dshVersion: web.version, platform: process.platform, ok, phase: loaded.phase, detail: loaded.detail, lan, admin }))
    process.exitCode = ok ? 0 : 1
  } catch (error) {
    console.log(JSON.stringify({ check: 'load', dshVersion: DSH_VERSION, platform: process.platform, ok: false, detail: String(error instanceof Error ? error.message : error) }))
    process.exitCode = 1
  } finally {
    await web?.stop()
  }
}

await main()
