// @ts-check
/**
 * 加载检查：把插件装进真实的 `dsh web`，确认 DSH 加载了它并且处于运行状态。
 *
 * 用法：
 *   npm run load-check                     # 使用基线版本（src/dsh/versions.js）
 *   DSH_VERSION=0.2.3 npm run load-check   # 测试其他版本
 *   DSH_BIN=/path/to/dsh-entry.js npm run load-check   # 使用已安装的 DSH（指向 JS 入口文件）
 *
 * 输出一行 JSON 结果（stdout 最后一行），退出码 0 表示通过。
 * 跨平台：Windows 和 Linux 上都直接用 node 运行 DSH 的 JS 入口，不依赖 shell 和 .cmd 包装。
 */
import { spawn, execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import http from 'node:http'
import { randomUUID } from 'node:crypto'
import { DSH_BASELINE_VERSION } from '../../src/dsh/versions.js'

const PLUGIN_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const DSH_VERSION = process.env.DSH_VERSION || DSH_BASELINE_VERSION
const STARTUP_TIMEOUT_MS = 120_000

/** @param {string} message */
const log = (message) => console.error(`[load-check] ${message}`)

/** 安装指定版本的 DSH，返回其 JS 入口文件路径。 */
function installDsh(/** @type {string} */ dir) {
  writeFileSync(join(dir, 'package.json'), JSON.stringify({ name: 'dsh-load-check', private: true }))
  log(`安装 @deepseek-ai/dsh@${DSH_VERSION} ...`)
  const npmCli = process.env.npm_execpath
  const args = ['install', '--no-audit', '--no-fund', '--loglevel=error', `@deepseek-ai/dsh@${DSH_VERSION}`]
  if (npmCli && npmCli.endsWith('.js')) execFileSync(process.execPath, [npmCli, ...args], { cwd: dir, stdio: 'inherit' })
  else execFileSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', args, { cwd: dir, stdio: 'inherit', shell: process.platform === 'win32' })
  const pkgPath = join(dir, 'node_modules', '@deepseek-ai', 'dsh', 'package.json')
  const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'))
  const bin = typeof pkg.bin === 'string' ? pkg.bin : pkg.bin?.dsh
  if (typeof bin !== 'string') throw new Error('找不到 @deepseek-ai/dsh 的 bin 入口')
  return { entry: join(dirname(pkgPath), bin), version: String(pkg.version) }
}

/**
 * @param {string} entry
 * @param {string[]} args
 * @param {NodeJS.ProcessEnv} env
 * @param {string} cwd
 */
function runDsh(entry, args, env, cwd) {
  execFileSync(process.execPath, [entry, ...args], { env, cwd, stdio: ['ignore', 'inherit', 'inherit'] })
}

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

async function main() {
  const work = mkdtempSync(join(tmpdir(), 'dshremote-load-'))
  const dshHome = join(work, 'home')
  const cwd = join(work, 'workspace')
  mkdirSync(dshHome); mkdirSync(cwd)
  const env = { ...process.env, DSH_HOME: dshHome }
  /** @type {import('node:child_process').ChildProcess | undefined} */
  let child
  try {
    const dsh = process.env.DSH_BIN
      ? { entry: process.env.DSH_BIN, version: DSH_VERSION }
      : installDsh(work)
    log(`DSH ${dsh.version}，把插件装进 web profile ...`)
    runDsh(dsh.entry, ['plugin', '--profile', 'web', 'add', '-w', PLUGIN_DIR], env, cwd)

    log('启动 dsh web --port 0 ...')
    child = spawn(process.execPath, [dsh.entry, 'web', '--no-open', '--port', '0'], { env, cwd, stdio: ['ignore', 'pipe', 'pipe'] })
    const launch = await new Promise((resolveLaunch, reject) => {
      let output = ''
      const timer = setTimeout(() => reject(new Error(`等待 dsh web 启动超时，输出：\n${output.slice(-2000)}`)), STARTUP_TIMEOUT_MS)
      /** @param {Buffer} chunk */
      const onData = (chunk) => {
        output += chunk
        const match = output.match(/http:\/\/127\.0\.0\.1:\d+\/\?token=[A-Za-z0-9_-]+/)
        if (match) { clearTimeout(timer); resolveLaunch(new URL(match[0])) }
      }
      child?.stdout?.on('data', onData)
      child?.stderr?.on('data', onData)
      child?.on('exit', (code) => { clearTimeout(timer); reject(new Error(`dsh web 提前退出（code=${code}），输出：\n${output.slice(-2000)}`)) })
    })
    const result = await checkLoaded(launch)
    console.log(JSON.stringify({ check: 'load', dshVersion: dsh.version, platform: process.platform, ...result }))
    process.exitCode = result.ok ? 0 : 1
  } catch (error) {
    console.log(JSON.stringify({ check: 'load', dshVersion: DSH_VERSION, platform: process.platform, ok: false, detail: String(error instanceof Error ? error.message : error) }))
    process.exitCode = 1
  } finally {
    if (child && child.exitCode === null) {
      if (process.platform === 'win32' && child.pid) {
        try { execFileSync('taskkill', ['/pid', String(child.pid), '/t', '/f'], { stdio: 'ignore' }) } catch { /* 进程可能已退出 */ }
      } else {
        child.kill('SIGINT')
        await new Promise((r) => setTimeout(r, 3000))
        if (child.exitCode === null) child.kill('SIGKILL')
      }
    }
    try { rmSync(work, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 }) } catch { /* Windows 上文件可能仍被占用 */ }
  }
}

await main()
