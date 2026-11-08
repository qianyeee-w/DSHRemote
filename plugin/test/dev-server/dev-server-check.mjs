// @ts-check
/**
 * dev-server 检查（M2 T1）：用临时数据目录和空闲端口启动 scripts/dev-server.mjs，
 * 只通过它的子命令和插件对外的接口操作，确认 App 开发需要的功能都可用：
 *
 *   端口被占用时直接报错 → 启动 → 不能重复启动 → pair 生成的二维码内容正确 → 手机配对
 *   → 流式对话 → restart 后用原 token 恢复 → devices / revoke → stop
 *   → 用同一个数据目录再次启动，指纹不变
 *
 * 用法：npm run dev-server-check（环境变量同 e2e，建议设置 DSH_INSTALL_DIR）。stdout 最后一行是 JSON 结果。
 */
import { spawn, execFile } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import net from 'node:net'
import { randomUUID } from 'node:crypto'
import { spkiFingerprint } from '../../src/tls/certificate.js'
import { CERT_FILE } from '../../src/tls/identity.js'
import { PLUGIN_DIR, log, freePort } from '../helpers/dsh-runtime.mjs'
import { pinnedRequest, pinnedWebSocket, collectMessages } from '../helpers/pinned-client.js'
import { STREAM_REPLY } from '../contract/fake-llm.mjs'

const SCRIPT = join(PLUGIN_DIR, 'scripts', 'dev-server.mjs')
const HOST = '127.0.0.1'
const START_TIMEOUT_MS = 240_000
const STEP_TIMEOUT_MS = 30_000

const home = mkdtempSync(join(tmpdir(), 'dshremote-devserver-'))
const LAN_PORT = await freePort()
const ADMIN_PORT = await freePort()
const portArgs = ['--home', home, '--port', String(LAN_PORT), '--admin-port', String(ADMIN_PORT), '--bind', HOST]

/** @type {{ name: string, ok: boolean, detail?: string }[]} */
const results = []
/** @param {string} name @param {() => Promise<string | void>} fn */
async function step(name, fn) {
  try {
    const detail = await fn()
    results.push(detail ? { name, ok: true, detail } : { name, ok: true })
    log(`PASS  ${name}${detail ? `  ${detail}` : ''}`)
    return true
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error)
    results.push({ name, ok: false, detail })
    log(`FAIL  ${name}  ${detail}`)
    return false
  }
}
const wait = (/** @type {number} */ ms) => new Promise((r) => setTimeout(r, ms))
/** @template T @param {() => Promise<T | undefined | false> | T | undefined | false} fn @param {string} label @param {number} [ms] @returns {Promise<T>} */
async function until(fn, label, ms = STEP_TIMEOUT_MS) {
  const end = Date.now() + ms
  let lastError
  while (Date.now() < end) {
    try { const v = await fn(); if (v) return v } catch (error) { lastError = error }
    await wait(300)
  }
  throw new Error(`等待超时：${label}${lastError ? `（${lastError instanceof Error ? lastError.message : String(lastError)}）` : ''}`)
}

/** 运行一个子命令，返回 stdout。 @param {string[]} args @returns {Promise<{ code: number, stdout: string, stderr: string }>} */
function cli(args) {
  return new Promise((resolve) => {
    execFile(process.execPath, [SCRIPT, ...args], { env: process.env, timeout: START_TIMEOUT_MS }, (error, stdout, stderr) => {
      resolve({ code: error ? Number(/** @type {any} */ (error).code ?? 1) : 0, stdout, stderr })
    })
  })
}
/** @param {string[]} args */
async function cliJson(args) {
  const r = await cli([...args, '--json', '--home', home])
  if (r.code !== 0) throw new Error(`dev-server ${args.join(' ')} 失败：${r.stderr.trim()}`)
  return JSON.parse(r.stdout.trim().split('\n').at(-1) ?? '')
}

/** 启动一个 dev-server（前台进程），等它就绪。 */
async function startServer() {
  const child = spawn(process.execPath, [SCRIPT, ...portArgs], { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] })
  let output = ''
  child.stdout.on('data', (c) => { output += c })
  child.stderr.on('data', (c) => { output += c })
  /** @type {Promise<number | null>} */
  const exited = new Promise((r) => child.once('exit', (code) => r(code)))
  let done = false
  void exited.then(() => { done = true })
  await until(async () => {
    if (done) throw new Error(`dev-server 提前退出：\n${output.slice(-2000)}`)
    const s = await cliJson(['status'])
    return s.running && s.readyAt ? s : undefined
  }, 'dev-server 启动', START_TIMEOUT_MS)
  return { child, exited, output: () => output }
}

/** @type {{ fingerprint: string, token: string, deviceId: string }} */
const phone = { fingerprint: '', token: '', deviceId: '' }
/** @param {string} method @param {string} path @param {unknown} [body] */
async function api(method, path, body) {
  const res = await pinnedRequest({
    host: HOST, port: LAN_PORT, fingerprint: phone.fingerprint, method, path,
    headers: { ...(phone.token ? { authorization: `Bearer ${phone.token}` } : {}), ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  return { status: res.status, json: () => res.json() }
}

async function main() {
  /** @type {Awaited<ReturnType<typeof startServer>> | undefined} */
  let server
  try {
    await step('端口被占用时直接报错，指出是哪个端口', async () => {
      const blocker = net.createServer()
      await new Promise((r) => blocker.listen(LAN_PORT, HOST, () => r(undefined)))
      try {
        const r = await cli(portArgs)
        if (r.code === 0 || !r.stderr.includes(`端口 ${LAN_PORT} 已被占用`)) throw new Error(`应当报错：code=${r.code} ${r.stderr.trim()}`)
      } finally {
        await new Promise((r) => blocker.close(() => r(undefined)))
      }
    })

    if (!await step('启动：插件连上 DSH，状态文件记录端口和指纹', async () => {
      server = await startServer()
      const status = await cliJson(['status'])
      phone.fingerprint = spkiFingerprint(readFileSync(join(home, 'home', 'dshremote', CERT_FILE), 'utf8'))
      if (status.lanPort !== LAN_PORT || status.adminPort !== ADMIN_PORT) throw new Error(`端口不对：${JSON.stringify(status)}`)
      if (status.fingerprint !== phone.fingerprint) throw new Error('状态文件中的指纹和证书不一致')
      return `DSH ${status.dshVersion}`
    })) throw new Error('dev-server 没有启动，后续步骤跳过')

    await step('同一个数据目录不能同时启动两个 dev-server', async () => {
      const r = await cli(portArgs)
      if (r.code === 0 || !r.stderr.includes('已经在运行')) throw new Error(`应当拒绝：code=${r.code} ${r.stderr.trim()}`)
    })

    await step('/v1/info 可以按指纹访问', async () => {
      const info = await api('GET', '/v1/info')
      if (info.status !== 200 || info.json().protocol !== 1 || info.json().dsh.compatible !== true) throw new Error(`${info.status} ${JSON.stringify(info.json())}`)
    })

    if (!await step('pair：二维码内容符合 protocol.md 2.1，手机用它完成配对', async () => {
      const pair = await cliJson(['pair', '--addr', HOST])
      const uri = new URL(pair.uri)
      if (uri.protocol !== 'dshremote:' || uri.host !== 'pair') throw new Error(`二维码前缀不对：${uri.protocol}//${uri.host}`)
      const q = uri.searchParams
      const expected = { v: '1', h: HOST, p: String(LAN_PORT), fp: phone.fingerprint, c: pair.code }
      for (const [k, v] of Object.entries(expected)) if (q.get(k) !== v) throw new Error(`二维码字段 ${k} 不对`)
      if (!q.get('n')) throw new Error('二维码中没有电脑名')
      const res = await api('POST', '/v1/pair', { code: pair.code, deviceName: 'dev-server-check', platform: 'android', appVersion: '0.0.0-check' })
      if (res.status !== 200) throw new Error(`配对失败：${res.status}`)
      Object.assign(phone, { token: res.json().token, deviceId: res.json().deviceId })
    })) throw new Error('配对失败，后续步骤跳过')

    await step('假模型：STREAM 触发流式输出，任务完成后收到 session.idle', async () => {
      const ws = await pinnedWebSocket({ host: HOST, port: LAN_PORT, fingerprint: phone.fingerprint, headers: { authorization: `Bearer ${phone.token}` } })
      const inbox = collectMessages(ws)
      try {
        await inbox.waitFor((m) => m.type === 'hello')
        const created = await api('POST', '/v1/sessions', {})
        if (created.status !== 200) throw new Error(`新建会话失败：${created.status}`)
        const sid = created.json().sessionId
        ws.send(JSON.stringify({ type: 'subscribe', sessionId: sid }))
        await wait(500)
        const res = await api('POST', `/v1/sessions/${sid}/prompt`, { requestId: randomUUID(), text: '请 STREAM 一段话' })
        if (res.status !== 200) throw new Error(`发消息失败：${res.status}`)
        await inbox.waitFor((m) => m.type === 'message.upsert' && m.message.draft === true)
        await inbox.waitFor((m) => m.type === 'message.upsert' && !m.message.draft && JSON.stringify(m.message).includes(STREAM_REPLY))
        await inbox.waitFor((m) => m.type === 'session.idle' && m.sessionId === sid, 20_000)
      } finally {
        ws.close()
      }
    })

    await step('restart：DSH 换了端口，App 用原来的 token 和指纹恢复', async () => {
      const before = await cliJson(['status'])
      const r = await cli(['restart', '--home', home])
      if (r.code !== 0) throw new Error(`restart 失败：${r.stderr.trim()}`)
      const after = await cliJson(['status'])
      if (after.dshPid === before.dshPid) throw new Error('DSH 进程没有变')
      if (after.fingerprint !== before.fingerprint) throw new Error('重启后指纹变了')
      await until(async () => (await api('GET', '/v1/me')).status === 200, '重启后 /v1/me 返回 200')
      return `DSH 端口 ${before.dshPort} → ${after.dshPort}`
    })

    await step('devices 列出这台手机；revoke 后立即返回 401', async () => {
      const { devices } = await cliJson(['devices'])
      if (!devices.some((/** @type {{ deviceId: string }} */ d) => d.deviceId === phone.deviceId)) throw new Error(`设备列表中没有这台手机：${JSON.stringify(devices)}`)
      const r = await cli(['revoke', phone.deviceId, '--home', home])
      if (r.code !== 0) throw new Error(`revoke 失败：${r.stderr.trim()}`)
      const me = await api('GET', '/v1/me')
      if (me.status !== 401) throw new Error(`撤销后应返回 401：${me.status}`)
    })

    await step('stop：dev-server 正常退出，清理状态文件', async () => {
      const running = /** @type {NonNullable<typeof server>} */ (server)
      const r = await cli(['stop', '--home', home])
      if (r.code !== 0) throw new Error(`stop 失败：${r.stderr.trim()}`)
      const code = await Promise.race([running.exited, wait(30_000).then(() => 'timeout')])
      if (code !== 0) throw new Error(`退出码应为 0：${code}\n${running.output().slice(-1500)}`)
      if (existsSync(join(home, 'dev-server.json'))) throw new Error('状态文件没有被删除')
      server = undefined
    })

    await step('用同一个数据目录再次启动：指纹不变（手机不需要重新配对）', async () => {
      server = await startServer()
      const status = await cliJson(['status'])
      if (status.fingerprint !== phone.fingerprint) throw new Error('指纹变了')
      const r = await cli(['stop', '--home', home])
      if (r.code !== 0) throw new Error(`stop 失败：${r.stderr.trim()}`)
      await server.exited
      server = undefined
    })
  } finally {
    if (server) {
      await cli(['stop', '--home', home])
      await Promise.race([server.exited, wait(15_000)])
      server.child.kill()
    }
    try { rmSync(home, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 }) } catch { /* Windows 上文件可能仍被占用 */ }
  }
}

let fatal
try { await main() } catch (error) { fatal = error instanceof Error ? error.message : String(error) }
const ok = !fatal && results.every((r) => r.ok)
if (fatal) log(`中止：${fatal}`)
console.log(JSON.stringify({ ok, results, ...(fatal ? { fatal } : {}) }))
process.exit(ok ? 0 : 1)
