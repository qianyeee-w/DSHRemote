// @ts-check
/**
 * 实时流 /v1/stream 的传输层：认证、握手限制、hello 顺序、订阅、撤销、心跳。实时状态用桩代替（见 live.test.js）。
 */
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { generatePrivateKeyPem, issueSelfSignedCertificate, spkiFingerprint } from '../../src/tls/certificate.js'
import { startLanServer } from '../../src/server/https.js'
import { createRouter } from '../../src/server/routes.js'
import { attachStream, CLOSE_REVOKED, CLOSE_TRY_AGAIN } from '../../src/server/stream.js'
import { PairingCodes } from '../../src/pairing/codes.js'
import { DeviceRegistry } from '../../src/pairing/devices.js'
import { openStorage } from '../../src/storage.js'
import { unusedFacade } from '../helpers/stub-facade.js'
import { pinnedWebSocket, collectMessages, PinMismatchError } from '../helpers/pinned-client.js'

const keyPem = generatePrivateKeyPem()
const certPem = issueSelfSignedCertificate(keyPem)
const fingerprint = spkiFingerprint(certPem)

/** 可控的实时状态桩 */
function fakeHub() {
  /** @type {Set<(e: any) => void>} */
  const listeners = new Set()
  /** @type {{ sessionId: string, listener: (e: any) => void, off: boolean }[]} */
  const subscriptions = []
  const control = { snapshotGate: Promise.resolve(), fail: false }
  const hub = {
    onEvent: (/** @type {(e: any) => void} */ l) => { listeners.add(l); return () => listeners.delete(l) },
    async snapshot() {
      await control.snapshotGate
      if (control.fail) throw new Error('dsh down')
      return { sessions: [{ sessionId: 's1' }], attention: [] }
    },
    subscribe(/** @type {string} */ sessionId, /** @type {(e: any) => void} */ listener) {
      const entry = { sessionId, listener, off: false }
      subscriptions.push(entry)
      return () => { entry.off = true }
    },
  }
  return {
    hub: /** @type {import('../../src/server/stream.js').StreamHub} */ (/** @type {unknown} */ (hub)),
    control, subscriptions,
    broadcast: (/** @type {any} */ e) => { for (const l of listeners) l(e) },
    listenerCount: () => listeners.size,
  }
}

/** @type {{ home: string, devices: DeviceRegistry, server: import('../../src/server/https.js').LanServer, stream: ReturnType<typeof attachStream>, hub: ReturnType<typeof fakeHub>, token: string, deviceId: string }} */
let env

beforeEach(async () => {
  const home = await mkdtemp(join(tmpdir(), 'dshremote-stream-'))
  const devices = await DeviceRegistry.open(await openStorage({ dshHome: home }))
  const registered = await devices.register({ name: 'Pixel', platform: 'android', appVersion: '0.1.0' })
  const router = createRouter({ dshStatus: () => ({ version: null, compatible: null }), facade: unusedFacade, codes: new PairingCodes(), devices })
  const server = await startLanServer({ identity: { keyPem, certPem }, host: '127.0.0.1', port: 0, handler: router })
  const hub = fakeHub()
  const stream = attachStream({ server: server.server, devices, hub: hub.hub, pingIntervalMs: 200 })
  env = { home, devices, server, stream, hub, token: registered.token, deviceId: registered.device.deviceId }
})

afterEach(async () => {
  env.stream.close()
  await env.server.close()
  await env.devices.flush()
  await rm(env.home, { recursive: true, force: true })
})

/** @param {{ token?: string | null, headers?: Record<string, string>, path?: string, fingerprint?: string }} [options] */
function connect(options = {}) {
  const token = options.token === undefined ? env.token : options.token
  return pinnedWebSocket({
    host: '127.0.0.1', port: env.server.port(), fingerprint: options.fingerprint ?? fingerprint,
    ...(options.path ? { path: options.path } : {}),
    headers: { ...(token === null ? {} : { authorization: `Bearer ${token}` }), ...options.headers },
  })
}

test('连接后先收到 hello（完整状态），hello 之前发生的广播排在它后面', async () => {
  /** @type {() => void} */
  let open = () => {}
  env.hub.control.snapshotGate = new Promise((r) => { open = () => r(undefined) })
  const ws = await connect()
  const inbox = collectMessages(ws)
  await new Promise((r) => setTimeout(r, 50))
  env.hub.broadcast({ type: 'session.updated', eventId: 'e1', session: { sessionId: 's1' } })
  open()
  await inbox.waitFor((m) => m.type === 'session.updated')
  assert.deepEqual(inbox.messages.map((m) => m.type), ['hello', 'session.updated'])
  assert.equal(inbox.messages[0].protocol, 1)
  assert.deepEqual(inbox.messages[0].sessions, [{ sessionId: 's1' }])
  assert.equal(typeof inbox.messages[0].eventId, 'string')
  ws.close()
})

test('attention.resolved：自己处理的显示为 this-device，别处处理的显示为 other', async () => {
  const ws = await connect()
  const inbox = collectMessages(ws)
  await inbox.waitFor((m) => m.type === 'hello')
  env.hub.broadcast({ type: 'attention.resolved', eventId: 'e1', attentionId: 'a1', sessionId: 's1', by: env.deviceId })
  env.hub.broadcast({ type: 'attention.resolved', eventId: 'e2', attentionId: 'a2', sessionId: 's1', by: 'd_otherdevice1' })
  env.hub.broadcast({ type: 'attention.resolved', eventId: 'e3', attentionId: 'a3', sessionId: 's1', by: null })
  await inbox.waitFor((m) => m.eventId === 'e3')
  assert.deepEqual(inbox.messages.filter((m) => m.type === 'attention.resolved').map((m) => m.by), ['this-device', 'other', 'other'])
  // 不把其他设备的 id 发给这台手机
  assert.doesNotMatch(JSON.stringify(inbox.messages), /d_otherdevice1/)
  ws.close()
})

test('订阅和取消订阅：转发 message 事件；断开连接时自动取消所有订阅', async () => {
  const ws = await connect()
  const inbox = collectMessages(ws)
  await inbox.waitFor((m) => m.type === 'hello')
  ws.send(JSON.stringify({ type: 'subscribe', sessionId: 's1' }))
  ws.send(JSON.stringify({ type: 'subscribe', sessionId: 's1' })) // 重复订阅被忽略
  ws.send(JSON.stringify({ type: 'subscribe', sessionId: 's2' }))
  await new Promise((r) => setTimeout(r, 50))
  assert.deepEqual(env.hub.subscriptions.map((s) => s.sessionId), ['s1', 's2'])
  env.hub.subscriptions[0]?.listener({ type: 'message.upsert', eventId: 'm1', sessionId: 's1', rev: 1, message: { id: 'x' } })
  await inbox.waitFor((m) => m.type === 'message.upsert')
  ws.send(JSON.stringify({ type: 'unsubscribe', sessionId: 's1' }))
  await new Promise((r) => setTimeout(r, 50))
  assert.equal(env.hub.subscriptions[0]?.off, true)
  assert.equal(env.hub.subscriptions[1]?.off, false)
  ws.close()
  await inbox.waitClosed()
  await new Promise((r) => setTimeout(r, 50))
  assert.equal(env.hub.subscriptions[1]?.off, true)
  assert.equal(env.hub.listenerCount(), 0)
})

test('【反向】格式不对的消息返回 error，但不断开；二进制消息断开', async () => {
  const ws = await connect()
  const inbox = collectMessages(ws)
  await inbox.waitFor((m) => m.type === 'hello')
  for (const bad of ['not json', JSON.stringify({ type: 'exec', sessionId: 's1' }), JSON.stringify({ type: 'subscribe', sessionId: '../x' })]) ws.send(bad)
  await new Promise((r) => setTimeout(r, 100))
  assert.equal(inbox.messages.filter((m) => m.type === 'error' && m.code === 'BAD_REQUEST').length, 3)
  assert.equal(env.hub.subscriptions.length, 0)
  ws.send(Buffer.from([1, 2, 3]))
  assert.equal((await inbox.waitClosed()).code, 1008)
})

test('【反向】握手：没有 token、token 错误时 401 且不升级；路径不对 404；带 Origin（网页发起）403', async () => {
  await assert.rejects(connect({ token: null }), (e) => /** @type {any} */ (e).status === 401)
  await assert.rejects(connect({ token: 'A'.repeat(43) }), (e) => /** @type {any} */ (e).status === 401 && /** @type {any} */ (e).headers['www-authenticate'] === 'Bearer')
  await assert.rejects(connect({ path: '/v1/streamx' }), (e) => /** @type {any} */ (e).status === 404)
  await assert.rejects(connect({ headers: { origin: 'https://evil.example' } }), (e) => /** @type {any} */ (e).status === 403)
  assert.equal(env.stream.connections(), 0)
  assert.equal(env.hub.listenerCount(), 0)
})

test('【反向】证书指纹不对时，客户端在发送 token 之前就断开', async () => {
  await assert.rejects(connect({ fingerprint: 'A'.repeat(43) }), PinMismatchError)
  assert.equal(env.stream.connections(), 0)
})

test('【反向】设备被撤销时，它的连接立即关闭（4401），之后无法再连接', async () => {
  const ws = await connect()
  const inbox = collectMessages(ws)
  await inbox.waitFor((m) => m.type === 'hello')
  await env.devices.revoke(env.deviceId)
  assert.equal((await inbox.waitClosed()).code, CLOSE_REVOKED)
  await assert.rejects(connect(), (e) => /** @type {any} */ (e).status === 401)
})

test('【反向】每台设备的连接数有上限', async () => {
  const sockets = []
  for (let i = 0; i < 4; i++) sockets.push(await connect())
  await assert.rejects(connect(), (e) => /** @type {any} */ (e).status === 429)
  for (const ws of sockets) ws.close()
})

test('DSH 不可用时发出 error 并以 1013 关闭，App 稍后重连', async () => {
  env.hub.control.fail = true
  const ws = await connect()
  const inbox = collectMessages(ws)
  const closed = await inbox.waitClosed()
  assert.equal(closed.code, CLOSE_TRY_AGAIN)
  assert.deepEqual(inbox.messages.map((m) => [m.type, m.code]), [['error', 'DSH_UNAVAILABLE']])
})

test('心跳：客户端不回应 ping 时，服务端断开连接', async () => {
  const ws = await connect()
  const inbox = collectMessages(ws)
  await inbox.waitFor((m) => m.type === 'hello')
  // 让客户端不再自动回应 pong
  /** @type {any} */
  const raw = ws
  raw._receiver.removeAllListeners('ping')
  raw.pong = () => {}
  const closed = await inbox.waitClosed(3000)
  assert.equal(closed.code, 1006)
})
