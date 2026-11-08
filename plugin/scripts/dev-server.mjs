// @ts-check
/**
 * 给 App 开发用的服务器（M2 T1）：真实的 dsh web + 本插件 + 假模型（test/contract/fake-llm.mjs）。
 * App 连接它的局域网端口，就像连接一台装了插件的真实电脑，但模型的行为由消息中的关键词决定。
 *
 * 用法（在 plugin/ 下）：
 *   npm run dev-server                        启动（前台运行）。默认局域网端口 3290，配对页端口 = 局域网端口 + 1
 *   npm run dev-server -- --port 3390 --admin-port 3391 --bind 0.0.0.0 --home <目录>
 *   npm run dev-server -- pair [--addr <IPv4>] [--json] [--no-qr]   生成配对码，打印二维码和手动输入信息
 *   npm run dev-server -- devices [--json]    列出已配对的设备
 *   npm run dev-server -- revoke <deviceId> | --all                 撤销设备
 *   npm run dev-server -- restart             重启 DSH（端口会变，模拟电脑上的 DSH 重启）
 *   npm run dev-server -- stop                停止 dev-server
 *   npm run dev-server -- status [--json]     查看是否在运行
 *
 * 前台运行时也可以直接按键：p 生成配对码 · d 列出设备 · r 重启 DSH · q 退出。
 *
 * 数据目录（--home，默认 ~/.cache/dshremote-dev，可用 DSHREMOTE_DEV_HOME 指定）里保存 DSH_HOME、证书和已配对的设备，
 * 重启后指纹不变，手机不需要重新配对。删除这个目录就会换一个新指纹。
 * DSH 默认安装在数据目录的 dsh/ 下；设置 DSH_INSTALL_DIR 可以复用已经下载的 DSH。
 *
 * 这是开发工具：配对码会打印到终端（和配对页上显示的一样，5 分钟过期、只能用一次）。不要在生产环境中使用。
 */
import http from 'node:http'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, renameSync } from 'node:fs'
import { hostname } from 'node:os'
import { join } from 'node:path'
import net from 'node:net'
import { execFileSync } from 'node:child_process'
import { parseArgs } from 'node:util'
import { DEFAULT_CONFIG } from '../src/config.js'
import { spkiFingerprint } from '../src/tls/certificate.js'
import { CERT_FILE } from '../src/tls/identity.js'
import { buildPairingUri } from '../src/admin/page.js'
import { lanAddresses } from '../src/admin/addresses.js'
import { startDshWeb } from '../test/helpers/dsh-runtime.mjs'
import { startFakeLlm } from '../test/contract/fake-llm.mjs'
import {
  defaultHome, startOptions, parseCsrf, parsePairingCode, parseDshState, parseDevices, renderQr, groupFingerprint,
} from './dev-server-lib.mjs'

const STATE_FILE = 'dev-server.json'
const STOP_FILE = 'dev-server.stop'
const READY_TIMEOUT_MS = 120_000
/** DSH 在这段时间内连续退出 3 次，就认为它起不来，不再重启 */
const QUICK_EXIT_MS = 15_000
const MAX_QUICK_EXITS = 3

const sleep = (/** @type {number} */ ms) => new Promise((r) => setTimeout(r, ms))
const say = (/** @type {string} */ text) => console.log(text)

/**
 * @typedef {object} DevState
 * @property {number} pid dev-server（监护进程）的 pid
 * @property {number | undefined} dshPid
 * @property {number} lanPort
 * @property {number} adminPort
 * @property {string} bind
 * @property {string} fingerprint
 * @property {string} dshVersion
 * @property {number} dshPort DSH 自己的随机端口（只用于显示）
 * @property {number} readyAt 本次 DSH 启动完成、插件连上 DSH 的时间
 */

// ---------------------------------------------------------------- 状态文件

/** @param {string} home @returns {DevState | undefined} */
function readState(home) {
  try { return JSON.parse(readFileSync(join(home, STATE_FILE), 'utf8')) } catch { return undefined }
}

/** @param {string} home @param {DevState} state */
function writeState(home, state) {
  const file = join(home, STATE_FILE)
  writeFileSync(`${file}.tmp`, `${JSON.stringify(state, null, 2)}\n`)
  renameSync(`${file}.tmp`, file)
}

/** @param {number | undefined} pid */
function isAlive(pid) {
  if (!pid) return false
  try { process.kill(pid, 0); return true } catch (error) { return /** @type {NodeJS.ErrnoException} */ (error).code === 'EPERM' }
}

/** @param {string} home @returns {DevState} */
function requireRunning(home) {
  const state = readState(home)
  if (!state || !isAlive(state.pid)) throw new Error(`dev-server 没有在运行（数据目录 ${home}）。先运行：npm run dev-server`)
  return state
}

/** 结束 DSH 进程（Windows 上连同子进程一起结束）。 @param {number} pid */
function killDsh(pid) {
  if (process.platform === 'win32') {
    try { execFileSync('taskkill', ['/pid', String(pid), '/t', '/f'], { stdio: 'ignore' }) } catch { /* 可能已经退出 */ }
  } else {
    try { process.kill(pid, 'SIGINT') } catch { /* 可能已经退出 */ }
  }
}

/** @param {number} port @param {string} host */
function assertPortFree(port, host) {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', (error) => {
      const code = /** @type {NodeJS.ErrnoException} */ (error).code
      reject(new Error(code === 'EADDRINUSE'
        ? `端口 ${port} 已被占用（${host}）。换一个端口：npm run dev-server -- --port <端口>`
        : `无法监听 ${host}:${port}：${code ?? error.message}`))
    })
    server.listen(port, host, () => server.close(() => resolve(undefined)))
  })
}

// ---------------------------------------------------------------- 本机配对页

/**
 * @param {number} adminPort
 * @param {string} path
 * @param {{ method?: string, body?: string, headers?: Record<string, string> }} [options]
 * @returns {Promise<{ status: number, body: string }>}
 */
function admin(adminPort, path, options = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: adminPort, path, method: options.method ?? 'GET', headers: { host: `127.0.0.1:${adminPort}`, ...options.headers } }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (c) => { body += c })
      res.on('end', () => resolve({ status: res.statusCode ?? 0, body }))
    })
    req.on('error', reject)
    if (options.body) req.write(options.body)
    req.end()
  })
}

/** 像用户点按钮一样提交配对页上的表单（带 Origin 和 CSRF token）。 @param {number} adminPort @param {string} action @param {Record<string, string>} [fields] */
async function adminPost(adminPort, action, fields = {}) {
  const csrf = parseCsrf((await admin(adminPort, '/')).body)
  if (!csrf) throw new Error('配对页上没有 CSRF token')
  const body = new URLSearchParams({ csrf, ...fields }).toString()
  const res = await admin(adminPort, action, {
    method: 'POST', body,
    headers: { origin: `http://127.0.0.1:${adminPort}`, 'content-type': 'application/x-www-form-urlencoded', 'content-length': String(Buffer.byteLength(body)) },
  })
  if (res.status !== 303) throw new Error(`配对页操作 ${action} 失败：HTTP ${res.status}`)
}

/**
 * 等插件生成证书、连上 DSH（配对页显示「已连接」），返回证书指纹。
 * cancelled() 为 true（DSH 中途退出或用户要求停止）时立即返回 undefined。
 * @param {string} dshHome @param {number} adminPort @param {() => boolean} cancelled
 * @returns {Promise<string | undefined>}
 */
async function waitReady(dshHome, adminPort, cancelled) {
  const certFile = join(dshHome, 'dshremote', CERT_FILE)
  const end = Date.now() + READY_TIMEOUT_MS
  while (Date.now() < end) {
    if (cancelled()) return undefined
    let state
    try { state = parseDshState((await admin(adminPort, '/')).body) } catch { /* 插件还没启动配对页 */ }
    if (state === 'ready' && existsSync(certFile)) return spkiFingerprint(readFileSync(certFile, 'utf8'))
    await sleep(300)
  }
  throw new Error(`等待插件连上 DSH 超时（${READY_TIMEOUT_MS / 1000} 秒）。端口 ${adminPort} 是否被其他程序占用？`)
}

// ---------------------------------------------------------------- 子命令

const KEYWORDS = [
  'ASK     → 提问（ask_user_question，单选「方案A / 方案B」）',
  'SHELL   → 运行命令，触发审批',
  'STREAM  → 约 5 秒慢速流式输出',
  'THINK   → 先输出思考过程，再回复',
  'SLOW    → 很长、很慢的输出（用来测试停止）',
  '其他    → 立即回复一句固定的话',
]

/** @param {string} home @param {DevState} state */
function printBanner(home, state) {
  const addresses = lanAddresses(state.bind)
  say('')
  say(`DSHRemote dev-server 已启动：DSH ${state.dshVersion} + 插件 + 假模型`)
  say(`  App 连接地址：${addresses.length ? addresses.map((a) => `${a.address}:${state.lanPort}`).join('  ') : `（没有找到局域网地址）端口 ${state.lanPort}`}`)
  say(`  证书指纹：    ${groupFingerprint(state.fingerprint)}`)
  say(`                手动配对时核对前 8 位：${state.fingerprint.slice(0, 8)}`)
  say(`  配对页：      http://127.0.0.1:${state.adminPort}/（只在本机可访问；在别的电脑上看，用 ssh -L ${state.adminPort}:127.0.0.1:${state.adminPort} 转发）`)
  say(`  数据目录：    ${home}（指纹和已配对设备保存在这里，删除后会换新指纹）`)
  say('  假模型按消息里的关键词行动（区分大小写）：')
  for (const k of KEYWORDS) say(`    ${k}`)
  say('  按键：p 生成配对码 · d 列出设备 · r 重启 DSH · q 退出')
  say('  其他终端：npm run dev-server -- pair | devices | revoke <deviceId> | restart | stop | status')
  say('')
}

/** @param {string} home @param {{ addr?: string | undefined, json?: boolean | undefined, 'no-qr'?: boolean | undefined }} opts */
async function cmdPair(home, opts) {
  const state = requireRunning(home)
  const addresses = lanAddresses(state.bind).map((a) => a.address)
  const host = opts.addr ?? addresses[0]
  if (!host) throw new Error('没有找到局域网地址。用 --addr <IPv4> 指定')
  if (!net.isIPv4(host)) throw new Error(`--addr 必须是 IPv4 地址：${host}`)
  await adminPost(state.adminPort, '/pair-code')
  const code = parsePairingCode((await admin(state.adminPort, '/')).body)
  if (!code) throw new Error('配对页上没有显示配对码（配对接口可能被锁定，或者没有可用的局域网地址）')
  const name = hostname()
  const uri = buildPairingUri({ host, port: state.lanPort, fingerprint: state.fingerprint, code, name })
  if (opts.json) {
    say(JSON.stringify({ uri, host, port: state.lanPort, fingerprint: state.fingerprint, code, name }))
    return
  }
  if (!opts['no-qr']) for (const line of renderQr(uri)) say(line)
  say(uri)
  say(`手动输入：地址 ${host}  端口 ${state.lanPort}  配对码 ${code.slice(0, 4)} ${code.slice(4)}  （指纹前 8 位 ${state.fingerprint.slice(0, 8)}）`)
  say(`配对码 5 分钟内有效，只能使用一次。${addresses.length > 1 ? `其他地址：${addresses.filter((a) => a !== host).join('  ')}（用 --addr 切换）` : ''}`)
}

/** @param {string} home @param {{ json?: boolean | undefined }} opts */
async function cmdDevices(home, opts) {
  const state = requireRunning(home)
  const devices = parseDevices((await admin(state.adminPort, '/')).body)
  if (opts.json) { say(JSON.stringify({ devices })); return }
  if (devices.length === 0) { say('还没有配对的设备'); return }
  for (const d of devices) say(`${d.deviceId}  ${d.name}  ${d.client}  配对于 ${d.createdAt}  最近在线 ${d.lastSeenAt}`)
}

/** @param {string} home @param {string | undefined} deviceId @param {{ all?: boolean | undefined }} opts */
async function cmdRevoke(home, deviceId, opts) {
  const state = requireRunning(home)
  const devices = parseDevices((await admin(state.adminPort, '/')).body)
  const targets = opts.all ? devices.map((d) => d.deviceId) : deviceId ? [deviceId] : []
  if (targets.length === 0) throw new Error(opts.all ? '没有可以撤销的设备' : '用法：revoke <deviceId> 或 revoke --all')
  for (const id of targets) {
    if (!devices.some((d) => d.deviceId === id)) throw new Error(`没有这台设备：${id}`)
    await adminPost(state.adminPort, '/devices/revoke', { deviceId: id })
    say(`已撤销 ${id}`)
  }
}

/** @param {string} home */
async function cmdRestart(home) {
  const before = requireRunning(home)
  if (!before.dshPid) throw new Error('DSH 还没有启动完成')
  killDsh(before.dshPid)
  const end = Date.now() + READY_TIMEOUT_MS
  while (Date.now() < end) {
    const now = readState(home)
    if (!now || !isAlive(now.pid)) throw new Error('dev-server 在重启 DSH 时退出了')
    if (now.readyAt > before.readyAt) { say(`DSH 已重启（DSH 端口 ${before.dshPort} → ${now.dshPort}，App 连接的端口仍然是 ${now.lanPort}）`); return }
    await sleep(300)
  }
  throw new Error('等待 DSH 重启超时')
}

/** @param {string} home */
async function cmdStop(home) {
  const state = readState(home)
  if (!state || !isAlive(state.pid)) {
    rmSync(join(home, STATE_FILE), { force: true })
    say('dev-server 没有在运行')
    return
  }
  writeFileSync(join(home, STOP_FILE), '')
  if (state.dshPid) killDsh(state.dshPid)
  const end = Date.now() + 30_000
  while (Date.now() < end) {
    if (!isAlive(state.pid)) { say('dev-server 已停止'); return }
    await sleep(300)
  }
  throw new Error(`等待 dev-server（pid ${state.pid}）退出超时`)
}

/** @param {string} home @param {{ json?: boolean | undefined }} opts */
function cmdStatus(home, opts) {
  const state = readState(home)
  const running = Boolean(state && isAlive(state.pid))
  if (opts.json) { say(JSON.stringify({ running, home, ...(running ? state : {}) })); return }
  if (!running || !state) { say(`dev-server 没有在运行（数据目录 ${home}）`); return }
  say(`dev-server 正在运行（pid ${state.pid}）：局域网端口 ${state.lanPort}（${state.bind}），配对页 http://127.0.0.1:${state.adminPort}/，DSH ${state.dshVersion}，指纹 ${state.fingerprint}`)
}

// ---------------------------------------------------------------- 启动（监护进程）

/** @param {string} home @param {{ port?: string | undefined, 'admin-port'?: string | undefined, bind?: string | undefined }} values */
async function cmdStart(home, values) {
  const { lanPort, adminPort, bind } = startOptions(values)
  mkdirSync(home, { recursive: true })
  const previous = readState(home)
  if (previous && isAlive(previous.pid)) throw new Error(`dev-server 已经在运行（pid ${previous.pid}，端口 ${previous.lanPort}）。先运行：npm run dev-server -- stop`)
  rmSync(join(home, STOP_FILE), { force: true })
  await assertPortFree(lanPort, bind)
  await assertPortFree(adminPort, '127.0.0.1')

  const llm = await startFakeLlm()
  const pluginConfig = { ...DEFAULT_CONFIG, lanPort, adminPort, lanBind: bind, openAdminOnFirstRun: false }
  const env = { DEEPSEEK_BASE_URL: llm.url, DEEPSEEK_API_KEY: 'sk-0123456789abcdef0123456789abcdef' }

  let stopping = false
  /** @type {import('../test/helpers/dsh-runtime.mjs').DshWeb | undefined} */
  let web
  const shutdown = () => {
    if (stopping) return
    stopping = true
    say('正在停止 ...')
    void web?.stop()
  }
  process.on('SIGINT', shutdown)
  process.on('SIGTERM', shutdown)
  if (process.stdin.isTTY) {
    process.stdin.setRawMode(true)
    process.stdin.setEncoding('utf8')
    process.stdin.on('data', (key) => {
      const k = String(key)
      if (k === 'q' || k === '\u0003') shutdown()
      else if (k === 'p') cmdPair(home, {}).catch((e) => say(`生成配对码失败：${e.message}`))
      else if (k === 'd') cmdDevices(home, {}).catch((e) => say(`读取设备列表失败：${e.message}`))
      else if (k === 'r' && web && !stopping) { say('重启 DSH ...'); void web.stop() }
    })
  }

  let quickExits = 0
  let first = true
  try {
    while (!stopping) {
      const current = await startDshWeb({ withPlugin: true, persistentDir: home, pluginConfig, env })
      web = current
      let dshExited = false
      void current.exited.then(() => { dshExited = true })
      const fingerprint = await waitReady(current.dshHome, adminPort, () => stopping || dshExited)
      const startedAt = Date.now()
      if (fingerprint === undefined) {
        if (stopping) break
        quickExits += 1
        if (quickExits >= MAX_QUICK_EXITS) throw new Error(`DSH 连续 ${MAX_QUICK_EXITS} 次在启动过程中退出，不再重启`)
        say('DSH 在启动过程中退出，重新启动 ...')
        continue
      }
      /** @type {DevState} */
      const state = { pid: process.pid, dshPid: current.pid, lanPort, adminPort, bind, fingerprint, dshVersion: current.version, dshPort: Number(current.launch.port), readyAt: startedAt }
      writeState(home, state)
      if (first) printBanner(home, state)
      else say(`DSH 已重新启动（DSH 端口 ${state.dshPort}），App 应当自动重连`)
      first = false

      const code = await current.exited
      // Ctrl+C 时 DSH 和本进程同时收到信号，给信号处理一点时间，避免把「退出」误判成「需要重启」
      await sleep(300)
      if (stopping || existsSync(join(home, STOP_FILE))) break
      quickExits = Date.now() - startedAt < QUICK_EXIT_MS ? quickExits + 1 : 0
      if (quickExits >= MAX_QUICK_EXITS) throw new Error(`DSH 连续 ${MAX_QUICK_EXITS} 次启动后很快退出（最近一次 code=${code}），不再重启`)
      say(`DSH 已退出（code=${code}），重新启动 ...`)
    }
  } finally {
    stopping = true
    await web?.stop()
    await llm.close()
    rmSync(join(home, STATE_FILE), { force: true })
    rmSync(join(home, STOP_FILE), { force: true })
    if (process.stdin.isTTY) { process.stdin.setRawMode(false); process.stdin.pause() }
  }
  say('dev-server 已停止')
}

// ---------------------------------------------------------------- 入口

async function main() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      port: { type: 'string' },
      'admin-port': { type: 'string' },
      bind: { type: 'string' },
      home: { type: 'string' },
      addr: { type: 'string' },
      json: { type: 'boolean' },
      'no-qr': { type: 'boolean' },
      all: { type: 'boolean' },
      help: { type: 'boolean', short: 'h' },
    },
  })
  const home = values.home ?? defaultHome()
  const [command = 'start', arg] = positionals
  if (values.help) { say('用法见 plugin/scripts/dev-server.mjs 文件开头的说明，或 plugin/AGENTS.md'); return }
  switch (command) {
    case 'start': return cmdStart(home, values)
    case 'pair': return cmdPair(home, values)
    case 'devices': return cmdDevices(home, values)
    case 'revoke': return cmdRevoke(home, arg, values)
    case 'restart': return cmdRestart(home)
    case 'stop': return cmdStop(home)
    case 'status': return cmdStatus(home, values)
    default: throw new Error(`不认识的命令：${command}。可用：start、pair、devices、revoke、restart、stop、status`)
  }
}

main().catch((error) => {
  console.error(`[dev-server] ${error instanceof Error ? error.message : String(error)}`)
  process.exit(1)
})
