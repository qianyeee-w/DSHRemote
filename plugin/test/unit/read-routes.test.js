// @ts-check
/**
 * 只读接口的路由：认证、路径参数、查询参数、错误映射。外观层用桩代替，DSH 的转换由 facade.test.js 覆盖。
 */
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { generatePrivateKeyPem, issueSelfSignedCertificate, spkiFingerprint } from '../../src/tls/certificate.js'
import { startLanServer } from '../../src/server/https.js'
import { createRouter } from '../../src/server/routes.js'
import { PairingCodes } from '../../src/pairing/codes.js'
import { DeviceRegistry } from '../../src/pairing/devices.js'
import { openStorage } from '../../src/storage.js'
import { DshUnavailableError } from '../../src/dsh/connection.js'
import { FacadeError } from '../../src/dsh/facade.js'
import { pinnedRequest } from '../helpers/pinned-client.js'

const keyPem = generatePrivateKeyPem()
const certPem = issueSelfSignedCertificate(keyPem)
const fingerprint = spkiFingerprint(certPem)

/** @type {{ home: string, devices: DeviceRegistry, server: import('../../src/server/https.js').LanServer, token: string, deviceId: string, calls: { op: string, args: unknown[] }[], behavior: { error?: unknown } }} */
let env

beforeEach(async () => {
  const home = await mkdtemp(join(tmpdir(), 'dshremote-read-'))
  const devices = await DeviceRegistry.open(await openStorage({ dshHome: home }))
  const registered = await devices.register({ name: 'Pixel', platform: 'android', appVersion: '0.1.0' })
  /** @type {{ op: string, args: unknown[] }[]} */
  const calls = []
  /** @type {{ error?: unknown }} */
  const behavior = {}
  /** @param {string} op */
  const op = (op) => async (/** @type {unknown[]} */ ...args) => {
    calls.push({ op, args })
    if (behavior.error) throw behavior.error
    return { op }
  }
  const facade = /** @type {import('../../src/server/routes.js').FacadeApi} */ (/** @type {unknown} */ ({
    workspaces: op('workspaces'), sessions: op('sessions'), models: op('models'), permissionPresets: op('permissionPresets'), messages: op('messages'),
  }))
  const router = createRouter({ dshStatus: () => ({ version: null, compatible: null }), facade, codes: new PairingCodes(), devices })
  const server = await startLanServer({ identity: { keyPem, certPem }, host: '127.0.0.1', port: 0, handler: router })
  env = { home, devices, server, token: registered.token, deviceId: registered.device.deviceId, calls, behavior }
})

afterEach(async () => {
  await env.server.close()
  await env.devices.flush()
  await rm(env.home, { recursive: true, force: true })
})

/** @param {string} path @param {{ method?: string, token?: string | null }} [options] */
function get(path, options = {}) {
  const token = options.token === undefined ? env.token : options.token
  return pinnedRequest({
    host: '127.0.0.1', port: env.server.port(), fingerprint, path, method: options.method ?? 'GET',
    headers: token === null ? {} : { authorization: `Bearer ${token}` },
  })
}

const READ_ROUTES = [
  ['/v1/workspaces', 'workspaces'],
  ['/v1/sessions', 'sessions'],
  ['/v1/models', 'models'],
  ['/v1/permission-presets', 'permissionPresets'],
  ['/v1/sessions/session-abc/messages', 'messages'],
]

test('只读接口：调用对应的外观层操作，并带上调用方的 deviceId', async () => {
  for (const [path, op] of READ_ROUTES) {
    const res = await get(String(path))
    assert.equal(res.status, 200, String(path))
    assert.deepEqual(res.json(), { op })
    assert.equal(env.calls.at(-1)?.op, op)
    assert.equal(env.calls.at(-1)?.args[0], env.deviceId)
  }
})

test('【反向】只读接口：没有 token、token 错误、设备被撤销时一律 401，且不访问 DSH', async () => {
  for (const [path] of READ_ROUTES) {
    assert.equal((await get(String(path), { token: null })).status, 401)
    assert.equal((await get(String(path), { token: 'A'.repeat(43) })).status, 401)
  }
  await env.devices.revoke(env.deviceId)
  assert.equal((await get('/v1/sessions')).status, 401)
  assert.equal(env.calls.length, 0)
})

test('历史消息：路径参数和查询参数原样传给外观层', async () => {
  const res = await get('/v1/sessions/session-abc/messages?before=42&limit=20')
  assert.equal(res.status, 200)
  assert.deepEqual(env.calls[0]?.args, [env.deviceId, 'session-abc', { before: '42', limit: 20 }])
  await get('/v1/sessions/session-abc/messages')
  assert.deepEqual(env.calls[1]?.args, [env.deviceId, 'session-abc', { before: undefined, limit: undefined }])
})

test('【反向】历史消息：limit 格式不对时 400；路径参数含非法字符或多余路径段时 404；写方法 405', async () => {
  assert.equal((await get('/v1/sessions/s/messages?limit=abc')).status, 400)
  assert.equal((await get('/v1/sessions/s/messages?limit=0')).status, 400)
  assert.equal((await get('/v1/sessions/s/messages?limit=-1')).status, 400)
  assert.equal((await get('/v1/sessions/a.b/messages')).status, 404)
  assert.equal((await get('/v1/sessions/%2e%2e/messages')).status, 404)
  assert.equal((await get('/v1/sessions/a/b/messages')).status, 404)
  assert.equal((await get('/v1/sessions//messages')).status, 404)
  const post = await get('/v1/sessions/s/messages', { method: 'POST' })
  assert.equal(post.status, 405)
  assert.equal(post.headers.allow, 'GET')
  assert.equal(env.calls.length, 0)
})

test('错误映射：DSH 不可用 → 503；外观层错误原样返回；其他异常 → 500 且不带细节', async () => {
  env.behavior.error = new DshUnavailableError('无法连接 DSH', new Error('connect ECONNREFUSED 127.0.0.1:39519'))
  let res = await get('/v1/sessions')
  assert.equal(res.status, 503)
  assert.equal(res.json().error.code, 'DSH_UNAVAILABLE')
  assert.doesNotMatch(res.body, /ECONNREFUSED|39519/)

  env.behavior.error = new FacadeError(404, 'NOT_FOUND', '会话不存在')
  res = await get('/v1/sessions/s/messages')
  assert.equal(res.status, 404)
  assert.deepEqual(res.json(), { error: { code: 'NOT_FOUND', message: '会话不存在' } })

  env.behavior.error = new Error('/home/me/.dsh/secret 读取失败')
  res = await get('/v1/models')
  assert.equal(res.status, 500)
  assert.deepEqual(res.json(), { error: { code: 'INTERNAL', message: '插件内部错误' } })
})
