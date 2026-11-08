// @ts-check
/**
 * 端到端测试：插件装在真实的 dsh web 里，假模型服务驱动 Agent，测试代码模拟手机，
 * 只通过插件对外的接口（局域网 HTTPS + /v1/stream，按证书指纹固定）和本机配对页操作，走完 M1 验收清单：
 *
 *   配对 → 列出会话 → 新建会话 → 订阅 → 发消息 → 看到流式输出 → 任务完成（session.idle）
 *   → 回答提问 → 审批在「Desktop」上被处理（attention.resolved: other，手机再处理 409）
 *   → 重启 DSH 后不需要重新配对就能恢复 → 撤销设备后立即无法访问
 *
 * 用法：npm run e2e（环境变量同 contract）。stdout 最后一行是 JSON 结果，退出码 0 表示全部通过。
 */
import http from 'node:http'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { DEFAULT_CONFIG } from '../../src/config.js'
import { spkiFingerprint } from '../../src/tls/certificate.js'
import { CERT_FILE } from '../../src/tls/identity.js'
import { DshConnection, PLUGIN_ACTOR } from '../../src/dsh/connection.js'
import { startDshWeb, DSH_VERSION, log } from '../helpers/dsh-runtime.mjs'
import { pinnedRequest, pinnedWebSocket, collectMessages } from '../helpers/pinned-client.js'
import { startFakeLlm, STREAM_REPLY, TOOL_DONE_REPLY } from '../contract/fake-llm.mjs'

const LAN_PORT = 3290
const ADMIN_PORT = 3291
const STEP_TIMEOUT_MS = 30_000

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
    await wait(150)
  }
  throw new Error(`等待超时：${label}${lastError ? `（${lastError instanceof Error ? lastError.message : String(lastError)}）` : ''}`)
}

// ---------------------------------------------------------------- 本机配对页（模拟用户在电脑上操作）

/** @param {string} path @param {{ method?: string, body?: string, headers?: Record<string, string> }} [options] */
function admin(path, options = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: ADMIN_PORT, path, method: options.method ?? 'GET', headers: { host: `127.0.0.1:${ADMIN_PORT}`, ...options.headers } }, (res) => {
      let body = ''
      res.on('data', (c) => { body += c })
      res.on('end', () => resolve(/** @type {{ status: number, body: string }} */ ({ status: res.statusCode ?? 0, body })))
    })
    req.on('error', reject)
    if (options.body) req.write(options.body)
    req.end()
  })
}

/** 像用户点按钮一样提交配对页上的表单（带 Origin 和 CSRF token）。 @param {string} action @param {Record<string, string>} [fields] */
async function adminPost(action, fields = {}) {
  const page = /** @type {{ body: string }} */ (await admin('/'))
  const csrf = page.body.match(/name="csrf" value="([^"]+)"/)?.[1]
  if (!csrf) throw new Error('配对页上没有 CSRF token')
  const body = new URLSearchParams({ csrf, ...fields }).toString()
  const res = /** @type {{ status: number }} */ (await admin(action, {
    method: 'POST', body,
    headers: { origin: `http://127.0.0.1:${ADMIN_PORT}`, 'content-type': 'application/x-www-form-urlencoded', 'content-length': String(Buffer.byteLength(body)) },
  }))
  if (res.status !== 303) throw new Error(`提交 ${action} 返回 ${res.status}`)
}

// ---------------------------------------------------------------- 手机

/** @type {{ fingerprint: string, token: string, deviceId: string }} */
const phone = { fingerprint: '', token: '', deviceId: '' }

/** @param {string} method @param {string} path @param {unknown} [body] */
async function api(method, path, body) {
  const res = await pinnedRequest({
    host: '127.0.0.1', port: LAN_PORT, fingerprint: phone.fingerprint, method, path,
    headers: { ...(phone.token ? { authorization: `Bearer ${phone.token}` } : {}), ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  return { status: res.status, json: () => res.json() }
}

async function openStream() {
  const ws = await pinnedWebSocket({ host: '127.0.0.1', port: LAN_PORT, fingerprint: phone.fingerprint, headers: { authorization: `Bearer ${phone.token}` } })
  return { ws, inbox: collectMessages(ws) }
}

/**
 * 模拟 App：按 message.upsert / message.removed 维护打开的会话的消息列表。
 * @param {ReturnType<typeof collectMessages>} inbox
 */
function messagesOf(inbox) {
  /** @type {Map<string, any>} */
  const map = new Map()
  for (const m of inbox.messages) {
    if (m.type === 'message.removed') map.delete(m.messageId)
    if (m.type !== 'message.upsert') continue
    if (m.replaces) map.delete(m.replaces)
    map.set(m.message.id, m.message)
  }
  return [...map.values()]
}

async function main() {
  const llm = await startFakeLlm()
  const pluginConfig = { ...DEFAULT_CONFIG, lanPort: LAN_PORT, adminPort: ADMIN_PORT, openAdminOnFirstRun: false }
  const env = { DEEPSEEK_BASE_URL: llm.url, DEEPSEEK_API_KEY: 'sk-0123456789abcdef0123456789abcdef' }
  /** @type {import('../helpers/dsh-runtime.mjs').DshWeb | undefined} */
  let web
  let version = DSH_VERSION
  /** @type {Awaited<ReturnType<typeof openStream>> | undefined} */
  let stream
  try {
    web = await startDshWeb({ withPlugin: true, pluginConfig, env })
    version = web.version
    const home = web.dshHome
    phone.fingerprint = spkiFingerprint(await until(() => { try { return readFileSync(join(home, 'dshremote', CERT_FILE), 'utf8') } catch { return undefined } }, '插件生成证书'))
    await until(async () => (await admin('/')).status === 200 && (await admin('/')).body.includes('data-state="ready"'), '插件连上 DSH')

    if (!await step('配对：在配对页生成配对码，手机用它换取 token', async () => {
      await adminPost('/pair-code')
      const page = /** @type {{ body: string }} */ (await admin('/'))
      const code = page.body.match(/class="mono big">([A-Z0-9 ]+)</)?.[1]?.replace(/ /g, '')
      if (!code) throw new Error('配对页上没有显示配对码（可能没有可用的局域网地址）')
      const res = await api('POST', '/v1/pair', { code, deviceName: 'e2e 手机', platform: 'android', appVersion: '0.0.0-e2e' })
      if (res.status !== 200) throw new Error(`配对失败：${res.status}`)
      Object.assign(phone, { token: res.json().token, deviceId: res.json().deviceId })
      if ((await api('POST', '/v1/pair', { code, deviceName: 'x', platform: 'android', appVersion: '0' })).status !== 400) throw new Error('配对码应当只能用一次')
    })) throw new Error('配对失败，后续步骤跳过')

    stream = await openStream()
    const s = stream
    await step('实时流：连接后先收到 hello', async () => {
      const hello = await s.inbox.waitFor((m) => m.type === 'hello')
      if (hello.protocol !== 1 || !Array.isArray(hello.sessions) || !Array.isArray(hello.attention)) throw new Error(JSON.stringify(hello).slice(0, 200))
      return `sessions=${hello.sessions.length}`
    })

    /** @type {string} */
    let sessionId = ''
    await step('新建会话，出现在会话列表和实时流中', async () => {
      const created = await api('POST', '/v1/sessions', {})
      if (created.status !== 200) throw new Error(`新建会话失败：${created.status}`)
      sessionId = created.json().sessionId
      const list = (await api('GET', '/v1/sessions')).json().sessions
      if (!list.some((/** @type {any} */ x) => x.sessionId === sessionId)) throw new Error('会话列表中没有新会话')
      await s.inbox.waitFor((m) => m.type === 'session.updated' && m.session.sessionId === sessionId)
    })
    const sid = sessionId
    s.ws.send(JSON.stringify({ type: 'subscribe', sessionId: sid }))
    await wait(500)

    await step('发消息：先看到流式草稿，写入日志后被正式消息替换；任务完成后收到 session.idle', async () => {
      const start = Date.now()
      const res = await api('POST', `/v1/sessions/${sid}/prompt`, { requestId: randomUUID(), text: '请 STREAM 一段话' })
      if (res.status !== 200) throw new Error(`发消息失败：${res.status}`)
      await s.inbox.waitFor((m) => m.type === 'session.updated' && m.session.sessionId === sid && m.session.status === 'running')
      const draft = await s.inbox.waitFor((m) => m.type === 'message.upsert' && m.message.draft && m.message.parts.some((/** @type {any} */ p) => p.type === 'text'))
      const partial = draft.message.parts.find((/** @type {any} */ p) => p.type === 'text').text
      if (partial.length >= STREAM_REPLY.length) throw new Error('草稿不是部分内容，没有体现流式输出')
      await s.inbox.waitFor((m) => m.type === 'message.upsert' && m.replaces === draft.message.id)
      const assistant = messagesOf(s.inbox).filter((m) => m.role === 'assistant')
      if (assistant.some((m) => m.draft)) throw new Error('草稿没有被替换')
      if (!JSON.stringify(assistant).includes(STREAM_REPLY)) throw new Error('最终消息不完整')
      const idle = await s.inbox.waitFor((m) => m.type === 'session.idle' && m.sessionId === sid, 20_000)
      return `草稿「${partial}」；session.idle 在 ${Math.round((Date.now() - start) / 1000)} 秒后，title=${idle.title}`
    })

    await step('提问：手机收到 attention.requested，回答后收到 attention.resolved（this-device）', async () => {
      await api('POST', `/v1/sessions/${sid}/prompt`, { requestId: randomUUID(), text: '请 ASK 我一个问题' })
      const requested = await s.inbox.waitFor((m) => m.type === 'attention.requested' && m.attention.kind === 'question')
      const listed = (await api('GET', '/v1/sessions')).json().sessions.find((/** @type {any} */ x) => x.sessionId === sid)
      if (listed.status !== 'waiting') throw new Error(`会话状态应为 waiting：${listed.status}`)
      const answer = await api('POST', `/v1/attention/${requested.attention.attentionId}/answer`, { answers: [{ id: 'q1', selected: ['方案A'] }] })
      if (answer.status !== 200) throw new Error(`回答失败：${answer.status} ${JSON.stringify(answer.json())}`)
      const resolved = await s.inbox.waitFor((m) => m.type === 'attention.resolved' && m.attentionId === requested.attention.attentionId)
      if (resolved.by !== 'this-device') throw new Error(`by 应为 this-device：${resolved.by}`)
      await until(() => JSON.stringify(messagesOf(s.inbox)).includes(TOOL_DONE_REPLY), '模型收到回答后继续')
    })

    await step('审批在 Desktop 上被处理：手机收到 attention.resolved（other），再处理返回 409', async () => {
      const launch = /** @type {import('../helpers/dsh-runtime.mjs').DshWeb} */ (web).launch
      const token = launch.searchParams.get('token') ?? ''
      const desktop = new DshConnection({ getPort: () => Number(launch.port), authenticatedUrl: (b) => { const u = new URL(b); u.searchParams.set('token', token); return u.href } })
      try {
        /** @type {import('../../src/dsh/connection.js').DshAppEvent[]} */
        const events = []
        desktop.onEvent((e) => events.push(e))
        await until(() => desktop.status().state === 'ready', 'desktop ready')
        await api('POST', `/v1/sessions/${sid}/prompt`, { requestId: randomUUID(), text: '请执行 SHELL 命令' })
        const requested = await s.inbox.waitFor((m) => m.type === 'attention.requested' && m.attention.kind === 'approval')
        if (!String(requested.attention.approval.summary).startsWith('运行命令：')) throw new Error(`审批里没有命令：${JSON.stringify(requested.attention.approval)}`)
        const seen = await until(() => events.find((e) => e.type === 'waterfall' && e.eventId === requested.attention.attentionId), 'desktop 收到审批')
        if (seen.type !== 'waterfall') throw new Error('unreachable')
        await desktop.answerEvent(seen.eventId, { kind: 'result', value: 'allowed-once' }, { actor: PLUGIN_ACTOR })
        const resolved = await s.inbox.waitFor((m) => m.type === 'attention.resolved' && m.attentionId === requested.attention.attentionId)
        if (resolved.by !== 'other') throw new Error(`by 应为 other：${resolved.by}`)
        const late = await api('POST', `/v1/attention/${requested.attention.attentionId}/answer`, { decision: 'reject' })
        if (late.status !== 409 || late.json().error.code !== 'ATTENTION_RESOLVED') throw new Error(`应当 409：${late.status}`)
      } finally { desktop.close() }
    })

    await step('重启 DSH（端口变化）：手机不需要重新配对，重连后收到新的 hello', async () => {
      const before = /** @type {import('../helpers/dsh-runtime.mjs').DshWeb} */ (web)
      const oldPort = before.launch.port
      await before.stop({ keepFiles: true })
      await s.inbox.waitClosed()
      web = await startDshWeb({ withPlugin: true, pluginConfig, env, reuse: before.work })
      const again = await until(async () => {
        try { return await openStream() } catch { return undefined }
      }, '重新连接实时流', 60_000)
      stream = again
      const hello = await again.inbox.waitFor((m) => m.type === 'hello', 20_000)
      if (!hello.sessions.some((/** @type {any} */ x) => x.sessionId === sid)) throw new Error('hello 中没有原来的会话')
      const page = (await api('GET', `/v1/sessions/${sid}/messages`)).json()
      if (!JSON.stringify(page.messages).includes(STREAM_REPLY)) throw new Error('重启后读不到历史')
      return `DSH 端口 ${oldPort} → ${web.launch.port}`
    })

    await step('【反向】在配对页撤销设备：实时流立即断开（4401），之后所有接口 401', async () => {
      const current = /** @type {Awaited<ReturnType<typeof openStream>>} */ (stream)
      await current.inbox.waitFor((m) => m.type === 'hello')
      await adminPost('/devices/revoke', { deviceId: phone.deviceId })
      const closed = await current.inbox.waitClosed()
      if (closed.code !== 4401) throw new Error(`关闭码应为 4401：${closed.code}`)
      if ((await api('GET', '/v1/me')).status !== 401) throw new Error('/v1/me 应当 401')
      if ((await api('GET', '/v1/sessions')).status !== 401) throw new Error('/v1/sessions 应当 401')
      try { await openStream(); throw new Error('不应当还能连接实时流') } catch (error) {
        if (/** @type {any} */ (error).status !== 401) throw error
      }
    })
  } catch (error) {
    results.push({ name: '运行端到端测试', ok: false, detail: error instanceof Error ? error.message : String(error) })
    log(`FAIL  运行端到端测试  ${error instanceof Error ? error.message : String(error)}`)
  } finally {
    stream?.ws.terminate()
    await web?.stop()
    await llm.close()
  }
  const ok = results.length > 0 && results.every((r) => r.ok)
  console.log(JSON.stringify({ check: 'e2e', dshVersion: version, platform: process.platform, ok, results }))
  process.exitCode = ok ? 0 : 1
}

await main()
