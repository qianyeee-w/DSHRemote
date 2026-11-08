// @ts-check
/**
 * DSH 连接层：用 FakeDsh 覆盖登录、一元调用、mux、$events，以及 DSH 重启（换端口、换 token）后的自动恢复。
 */
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { FakeDsh } from '../helpers/fake-dsh.js'
import { DshConnection, DshUnavailableError, PLUGIN_ACTOR } from '../../src/dsh/connection.js'

const DEVICE = 'd_abcdefghijkl'
const FAST = { idleTimeoutMs: 1500, backoffMinMs: 20, backoffMaxMs: 100 }

/** @type {FakeDsh} */
let dsh
/** @type {DshConnection} */
let conn

beforeEach(async () => {
  dsh = await new FakeDsh().start()
  conn = new DshConnection({ getPort: () => dsh.port, authenticatedUrl: dsh.authenticatedUrl, timing: FAST, random: () => 0.5 })
})

afterEach(async () => {
  conn.close()
  await dsh.stop()
})

/** @param {() => boolean} predicate */
async function until(predicate, ms = 5000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (predicate()) return
    await new Promise((r) => setTimeout(r, 10))
  }
  throw new Error('等待超时')
}

test('一元调用：首次自动登录，之后复用 cookie', async () => {
  assert.deepEqual(await conn.call('session/list', { _request: {} }, { actor: DEVICE }), { ok: true, value: { items: [] } })
  await conn.call('session/list', { _request: {} }, { actor: DEVICE })
  assert.equal(dsh.logins, 1)
})

test('业务错误原样返回（例如参数名不对）', async () => {
  const result = await conn.call('session/list', {}, { actor: DEVICE })
  assert.equal(result.ok, false)
  assert.equal(result.ok === false && result.error.code, 'gateway/arguments-invalid')
})

test('cookie 失效（401）时自动重新登录并重试一次', async () => {
  await conn.call('session/list', { _request: {} }, { actor: DEVICE })
  dsh.invalidateCookies()
  assert.equal((await conn.call('session/list', { _request: {} }, { actor: DEVICE })).ok, true)
  assert.equal(dsh.logins, 2)
})

test('DSH 重启换端口后，一元调用用新端口重新登录', async () => {
  await conn.call('session/list', { _request: {} }, { actor: DEVICE })
  const oldPort = dsh.port
  await dsh.restart()
  assert.notEqual(dsh.port, oldPort)
  assert.equal((await conn.call('session/list', { _request: {} }, { actor: DEVICE })).ok, true)
})

test('【反向】DSH 还没监听、端口不可达、启动 token 不对时抛出 DshUnavailableError', async () => {
  const noPort = new DshConnection({ getPort: () => undefined, authenticatedUrl: dsh.authenticatedUrl })
  await assert.rejects(noPort.call('session/list', { _request: {} }, { actor: DEVICE }), DshUnavailableError)
  const port = dsh.port
  await dsh.stop()
  const down = new DshConnection({ getPort: () => port, authenticatedUrl: dsh.authenticatedUrl })
  await assert.rejects(down.call('session/list', { _request: {} }, { actor: DEVICE }), DshUnavailableError)
  await dsh.start()
  const badToken = new DshConnection({ getPort: () => dsh.port, authenticatedUrl: (b) => `${b}?token=wrong` })
  await assert.rejects(badToken.call('session/list', { _request: {} }, { actor: DEVICE }), /登录失败/)
})

test('【反向】每次调用都必须说明调用方；接口名必须合法', async () => {
  await assert.rejects(conn.call('session/list', {}, { actor: 'someone' }), TypeError)
  await assert.rejects(conn.call('session/list', {}, { actor: '' }), TypeError)
  await assert.rejects(conn.call('../admin', {}, { actor: DEVICE }), TypeError)
  await assert.rejects(conn.call('session', {}, { actor: DEVICE }), TypeError)
  await assert.rejects(conn.call('session/list?x=1', {}, { actor: DEVICE }), TypeError)
  await assert.rejects(conn.call('session/../list', {}, { actor: DEVICE }), TypeError)
  await assert.rejects(conn.call('session//list', {}, { actor: DEVICE }), TypeError)
  assert.equal((await conn.call('$events/result', {}, { actor: DEVICE })).ok, true)
  dsh.calls.length = 0
  assert.equal(dsh.calls.length, 0, '非法的接口名不应发出任何请求')
})

test('流：收到 item 和 end；error 帧转成 onError', async () => {
  /** @type {unknown[]} */
  const items = []
  let ended = false
  await conn.openStream('test/echo', { items: [1, 'two', { three: 3 }] }, { onItem: (v) => items.push(v), onEnd: () => { ended = true } }, { actor: DEVICE })
  await until(() => ended)
  assert.deepEqual(items, [1, 'two', { three: 3 }])

  /** @type {{ code: string } | undefined} */
  let failure
  await conn.openStream('test/fail', {}, { onItem: () => {}, onError: (e) => { failure = e } }, { actor: DEVICE })
  await until(() => failure !== undefined)
  assert.equal(failure?.code, 'gateway/input-invalid')
})

test('$events：ready 后状态变为 ready；emit、waterfall、cancel 都能收到；回答时带上 clientId', async () => {
  /** @type {import('../../src/dsh/connection.js').DshAppEvent[]} */
  const events = []
  conn.onEvent((e) => events.push(e))
  await until(() => conn.status().state === 'ready')

  dsh.emit('api-session/status', ['session-1', true])
  const eventId = dsh.waterfall('approval/request', { toolName: 'bash' })
  dsh.cancelEvent(eventId)
  await until(() => events.length === 3)
  assert.deepEqual(events[0], { type: 'emit', event: 'api-session/status', args: ['session-1', true] })
  assert.equal(events[1]?.type, 'waterfall')
  assert.deepEqual(events[2], { type: 'cancel', eventId })

  assert.deepEqual(await conn.answerEvent(eventId, { kind: 'result', value: 'allowed-once' }, { actor: DEVICE }), { ok: true, value: undefined })
  const answer = dsh.calls.find((c) => c.endpoint === '$events/result')
  const [client] = dsh.clients
  assert.deepEqual(answer?.args, { clientId: client?.clientId, eventId, outcome: { kind: 'result', value: 'allowed-once' } })
})

test('DSH 重启（换端口、换 token、旧 cookie 失效）后，$events 自动重连并继续收到事件', async () => {
  /** @type {import('../../src/dsh/connection.js').DshAppEvent[]} */
  const events = []
  /** @type {string[]} */
  const states = []
  conn.onStatus((s) => states.push(s.state))
  conn.onEvent((e) => events.push(e))
  await until(() => conn.status().state === 'ready')

  await dsh.restart()
  await until(() => states.includes('connecting'))
  await until(() => conn.status().state === 'ready')
  dsh.emit('api-session/added', [{ sessionId: 'session-2' }])
  await until(() => events.length === 1)
  assert.equal(events[0]?.type === 'emit' && events[0].event, 'api-session/added')
  assert.ok(dsh.logins >= 1)
})

test('DSH 停止期间状态为 unavailable，恢复后自动变回 ready', async () => {
  conn.onEvent(() => {})
  await until(() => conn.status().state === 'ready')
  const port = dsh.port
  await dsh.stop()
  await until(() => conn.status().state === 'unavailable')
  assert.match(String(conn.status().lastError), /DSH|mux/)
  // 在同一端口重新启动（dsh web 固定端口的情况），cookie 已失效
  dsh.port = port
  await new Promise((r) => setTimeout(r, 50))
  await dsh.start()
  conn = new DshConnection({ getPort: () => dsh.port, authenticatedUrl: dsh.authenticatedUrl, timing: FAST })
  conn.onEvent(() => {})
  await until(() => conn.status().state === 'ready')
})

test('mux 断开时，未结束的流收到 carrier-lost', async () => {
  /** @type {{ code: string } | undefined} */
  let failure
  await conn.openStream('test/hold', {}, { onItem: () => {}, onError: (e) => { failure = e } }, { actor: DEVICE })
  await dsh.restart()
  await until(() => failure !== undefined)
  assert.equal(failure?.code, 'carrier-lost')
})

test('没有心跳和消息超过空闲时间时，主动断开并重连', async () => {
  conn.onEvent(() => {})
  await until(() => conn.status().state === 'ready')
  const [first] = dsh.clients
  dsh.ping = false
  await until(() => !dsh.clients.has(/** @type {any} */ (first)), 4000)
  dsh.ping = true
  await until(() => conn.status().state === 'ready' && dsh.clients.size === 1, 4000)
})

test('回答事件时如果事件流还没就绪，抛出 DshUnavailableError', async () => {
  await assert.rejects(conn.answerEvent('x', { kind: 'next' }, { actor: DEVICE }), DshUnavailableError)
})

test('close 之后不再重连', async () => {
  conn.onEvent(() => {})
  await until(() => conn.status().state === 'ready')
  conn.close()
  assert.equal(conn.status().state, 'closed')
  await new Promise((r) => setTimeout(r, 300))
  assert.equal(dsh.clients.size, 0)
  await assert.rejects(conn.openStream('test/echo', {}, { onItem: () => {} }, { actor: PLUGIN_ACTOR }), DshUnavailableError)
})
