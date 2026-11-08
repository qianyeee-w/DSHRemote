// 模拟手机客户端，对真实 dsh web 验证 v1 需要的接口。
// 用法：node probe.mjs '<dsh web 启动链接（带 token）>'
import http from 'node:http'
import { randomUUID } from 'node:crypto'
import fs from 'node:fs'
import WebSocket from 'ws'

const launch = new URL(process.argv[2])
const ORIGIN = `${launch.protocol}//${launch.host}`
const results = []
const record = (name, ok, detail) => { results.push({ name, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`) }
const short = (v, n = 260) => { const s = JSON.stringify(v) ?? 'undefined'; return s.length > n ? s.slice(0, n) + '…' : s }

function request(path, { method = 'GET', headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(`${ORIGIN}${path}`, { method, headers: { host: launch.host, ...headers } }, (res) => {
      let data = ''
      res.on('data', d => { data += d })
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: data }))
    })
    req.on('error', reject)
    if (body) req.write(body)
    req.end()
  })
}

// 1. 用启动 token 换 cookie（插件将来在内部做这一步）
const login = await request(`/?token=${launch.searchParams.get('token')}`)
const cookie = (login.headers['set-cookie'] ?? [])[0]?.split(';')[0]
record('登录：token 换取 cookie', Boolean(cookie), `status=${login.status}`)

async function rpc(endpoint, args = {}) {
  const res = await request(`/api/${endpoint}`, {
    method: 'POST',
    headers: { cookie, 'content-type': 'application/json' },
    body: JSON.stringify({ type: 'client-request', rpcId: randomUUID(), method: endpoint, payload: { args } }),
  })
  let parsed
  try { parsed = JSON.parse(res.body) } catch { parsed = res.body }
  return { status: res.status, ...(typeof parsed === 'object' ? parsed : { raw: parsed }) }
}

const unauth = await request('/api/session/list', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' })
record('未带 cookie 的调用被拒绝', unauth.status === 401 || unauth.status === 403, `status=${unauth.status}`)

// 2. 一元调用
const list0 = await rpc('session/list', { _request: {} })
record('session/list', list0.result?.ok === true, short(list0.result))
const catalog = await rpc('session/modelCatalog')
record('session/modelCatalog', catalog.result?.ok === true, short(catalog.result, 400))
const presets = await rpc('permissionPresets/catalog')
record('permissionPresets/catalog', presets.result?.ok === true, short(presets.result ?? presets, 300))

// 3. mux WebSocket + $events
const ws = new WebSocket(`ws://${launch.host}/api/remote.mux`, { headers: { cookie, host: launch.host } })
await new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject) })
record('打开 /api/remote.mux', true)
const streams = new Map()
const events = []
let clientId
ws.on('message', (raw) => {
  const frame = JSON.parse(String(raw))
  const handler = streams.get(frame.streamId)
  if (handler) handler(frame)
})
function open(endpoint, args, onItem) {
  const streamId = randomUUID()
  const frames = []
  streams.set(streamId, (f) => { frames.push(f); onItem?.(f) })
  ws.send(JSON.stringify({ type: 'open', streamId, endpoint, payload: { args } }))
  return { streamId, frames, cancel: () => ws.send(JSON.stringify({ type: 'cancel', streamId })) }
}
const wait = (ms) => new Promise(r => setTimeout(r, ms))
async function until(fn, ms = 15000, label = 'condition') {
  const end = Date.now() + ms
  while (Date.now() < end) { const v = fn(); if (v) return v; await wait(100) }
  throw new Error(`timeout waiting for ${label}`)
}

const evStream = open('$events', {}, (f) => {
  if (f.type !== 'item') return
  const v = f.value
  if (v?.type === 'ready') clientId = v.clientId
  events.push(v)
})
await until(() => clientId, 10000, 'ready')
record('$events ready 帧', true, `clientId=${String(clientId).slice(0, 8)}…`)

const wsFollow = open('workspace/follow', {})
await until(() => wsFollow.frames.length > 0, 10000, 'workspace follow')
record('workspace/follow 首帧', wsFollow.frames[0].type === 'item', short(wsFollow.frames[0].value, 300))

// 第二个客户端（模拟同时开着的 Desktop 窗口）
const ws2 = new WebSocket(`ws://${launch.host}/api/remote.mux`, { headers: { cookie, host: launch.host } })
await new Promise((resolve, reject) => { ws2.once('open', resolve); ws2.once('error', reject) })
const events2 = []
const ev2Id = randomUUID()
ws2.on('message', (raw) => { const f = JSON.parse(String(raw)); if (f.streamId === ev2Id && f.type === 'item') events2.push(f.value) })
ws2.send(JSON.stringify({ type: 'open', streamId: ev2Id, endpoint: '$events', payload: { args: {} } }))
await until(() => events2.some(e => e?.type === 'ready'), 10000, 'client2 ready')
record('第二个客户端 $events ready', true)

// 4. 新建会话 + 跟随
const created = await rpc('session/create', { request: {} })
const sessionId = created.result?.value?.sessionId
record('session/create', Boolean(sessionId), short(created.result))

const follow = open('session/follow', { request: { address: { kind: 'session', sessionId }, activate: true, assistantStream: true, maxMessages: 20 } })
await until(() => follow.frames.length > 0, 10000, 'follow first frame')
record('session/follow 首帧', follow.frames[0].type === 'item', short(follow.frames[0], 400))

const stateStream = open('session/observeState', { request: { sessionId } })
await until(() => stateStream.frames.length > 0, 10000, 'state')
record('session/observeState', stateStream.frames[0].type === 'item', short(stateStream.frames[0].value, 300))

async function prompt(text, mode = 'queue') {
  return rpc('session/prompt', { request: { requestId: randomUUID(), sessionId, mode, content: [{ type: 'text', text }] } })
}

// 5. 普通对话：流式输出
let mark = follow.frames.length
const p1 = await prompt('你好')
record('session/prompt（文本）', p1.result?.ok === true, short(p1.result))
await until(() => events.some(e => e?.type === 'emit' && e.event === 'api-session/status'), 15000, 'status event').catch(() => {})
await wait(4000)
const textFrames = follow.frames.slice(mark)
const sawText = JSON.stringify(textFrames).includes('假模型')
record('流式输出到达 follow 流', sawText, `新增帧数=${textFrames.length}`)
fs.writeFileSync('./out-follow-text.json', JSON.stringify(textFrames, null, 2))

// 6. 提问（waterfall）
mark = events.length
await prompt('请 ASK 我一个问题')
const q = await until(() => events.slice(mark).find(e => e?.type === 'waterfall' && e.event === 'user-questions/request'), 20000, 'question waterfall').catch(e => (record('收到提问 waterfall', false, e.message), undefined))
if (q) {
  record('收到提问 waterfall', true, short(q, 400))
  const ans = await rpc('$events/result', { clientId, eventId: q.eventId, outcome: { kind: 'result', value: { answers: [{ id: 'q1', selected: ['方案A'] }] } } })
  record('$events/result 回答提问', ans.result?.ok === true, short(ans.result ?? ans))
  await wait(4000)
}

// 7. 审批（waterfall）
mark = events.length
await prompt('请执行 SHELL 命令')
const a = await until(() => events.slice(mark).find(e => e?.type === 'waterfall' && e.event === 'approval/request'), 20000, 'approval waterfall').catch(e => (record('收到审批 waterfall', false, e.message), undefined))
if (a) {
  record('收到审批 waterfall', true, short(a, 400))
  const a2 = events2.find(e => e?.type === 'waterfall' && e.event === 'approval/request')
  record('第二个客户端也收到同一个审批', Boolean(a2), a2 ? `eventId 相同=${a2.eventId === a.eventId}` : '没收到')
  const dec = await rpc('$events/result', { clientId, eventId: a.eventId, outcome: { kind: 'result', value: 'allowed-once' } })
  record('$events/result 批准', dec.result?.ok === true, short(dec.result ?? dec))
  await wait(5000)
  const after2 = events2.filter(e => e?.eventId === a.eventId)
  record('第一个客户端批准后，第二个客户端收到的帧', true, short(after2.map(e => e.type)))
  record('命令确实执行了', fs.existsSync(`${process.env.PROBE_WORKDIR ?? process.cwd()}/probe-output.txt`))
}

// 8. 历史分页
const page = await rpc('session/page', { request: { address: { kind: 'session', sessionId }, maxMessages: 2 } })
record('session/page（maxMessages=2）', page.result?.ok === true, short(page.result, 300))

// 9. 取消
const cancel = await rpc('session/cancel', { request: { sessionId } })
record('session/cancel', cancel.result?.ok === true, short(cancel.result))

// 10. 事件汇总
const eventNames = [...new Set(events.filter(e => e?.event).map(e => `${e.type}:${e.event}`))]
record('收到的事件种类', true, eventNames.join(', '))

fs.writeFileSync('./out-events.json', JSON.stringify(events, null, 2))
fs.writeFileSync('./out-follow-all.json', JSON.stringify(follow.frames, null, 2))
fs.writeFileSync('./results.json', JSON.stringify(results, null, 2))
ws.close(); ws2.close()
process.exit(0)
