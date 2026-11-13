// @ts-check
/**
 * 外观层接口的路由：认证、路径参数、查询参数、请求体、错误映射。
 * 外观层用桩代替，DSH 的转换由 facade.test.js、attention.test.js 覆盖。
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

/** @type {{ home: string, devices: DeviceRegistry, server: import('../../src/server/https.js').LanServer, token: string, deviceId: string, calls: { op: string, args: unknown[] }[], behavior: { error?: unknown }, audit: { event: string, detail?: Record<string, string> }[], changed: string[] }} */
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
    return op === 'answerAttention' ? { attentionId: args[1] } : { op }
  }
  const facade = /** @type {import('../../src/server/routes.js').FacadeApi} */ (/** @type {unknown} */ ({
    directories: op('directories'), createDirectory: op('createDirectory'), registerWorkspace: op('registerWorkspace'),
    workspaces: op('workspaces'), sessions: op('sessions'), models: op('models'), permissionPresets: op('permissionPresets'), messages: op('messages'), usage: op('usage'),
    createSession: op('createSession'), prompt: op('prompt'), cancel: op('cancel'), selectModel: op('selectModel'),
    setPermissionPreset: op('setPermissionPreset'), answerAttention: op('answerAttention'),
  }))
  /** @type {{ event: string, detail?: Record<string, string> }[]} */
  const audit = []
  /** @type {string[]} */
  const changed = []
  const router = createRouter({
    dshStatus: () => ({ version: null, compatible: null }), facade, codes: new PairingCodes(), devices,
    audit: (event, detail) => audit.push({ event, ...(detail ? { detail } : {}) }),
    sessionChanged: (sessionId) => changed.push(sessionId),
  })
  const server = await startLanServer({ identity: { keyPem, certPem }, host: '127.0.0.1', port: 0, handler: router })
  env = { home, devices, server, token: registered.token, deviceId: registered.device.deviceId, calls, behavior, audit, changed }
})

afterEach(async () => {
  await env.server.close()
  await env.devices.flush()
  await rm(env.home, { recursive: true, force: true })
})

/** @param {string} path @param {{ method?: string, token?: string | null, body?: string, contentType?: string }} [options] */
function get(path, options = {}) {
  const token = options.token === undefined ? env.token : options.token
  /** @type {Record<string, string>} */
  const headers = token === null ? {} : { authorization: `Bearer ${token}` }
  if (options.body !== undefined) headers['content-type'] = options.contentType ?? 'application/json'
  return pinnedRequest({
    host: '127.0.0.1', port: env.server.port(), fingerprint, path, method: options.method ?? 'GET', headers,
    ...(options.body === undefined ? {} : { body: options.body }),
  })
}

/** @param {string} path @param {unknown} body @param {{ token?: string | null, contentType?: string }} [options] */
const post = (path, body, options = {}) => get(path, { ...options, method: 'POST', body: typeof body === 'string' ? body : JSON.stringify(body) })

const READ_ROUTES = [
  ['/v1/directories', 'directories'],
  ['/v1/workspaces', 'workspaces'],
  ['/v1/sessions', 'sessions'],
  ['/v1/models', 'models'],
  ['/v1/permission-presets', 'permissionPresets'],
  ['/v1/sessions/session-abc/messages', 'messages'],
  ['/v1/sessions/session-abc/usage', 'usage'],
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

const WRITE_ROUTES = /** @type {const} */ ([
  ['/v1/directories', 'createDirectory', { path: '/tmp', name: '项目' }, []],
  ['/v1/workspaces', 'registerWorkspace', { path: '/tmp/项目' }, []],
  ['/v1/sessions', 'createSession', { workspaceId: 'w1' }, []],
  ['/v1/sessions/s1/prompt', 'prompt', { requestId: 'r1', text: '你好', mode: 'queue' }, ['s1']],
  ['/v1/sessions/s1/model', 'selectModel', { provider: 'p', model: 'm' }, ['s1']],
  ['/v1/sessions/s1/permission-preset', 'setPermissionPreset', { preset: 'read-only' }, ['s1']],
  ['/v1/attention/a1/answer', 'answerAttention', { decision: 'allow' }, ['a1']],
])

test('写接口：调用对应的外观层操作，参数为 deviceId、路径参数和请求体', async () => {
  for (const [path, op, body, params] of WRITE_ROUTES) {
    const res = await post(path, body)
    assert.equal(res.status, 200, path)
    assert.equal(env.calls.at(-1)?.op, op)
    assert.deepEqual(env.calls.at(-1)?.args, [env.deviceId, ...params, body], path)
  }
  const cancel = await get('/v1/sessions/s1/cancel', { method: 'POST' })
  assert.equal(cancel.status, 200)
  assert.deepEqual(env.calls.at(-1), { op: 'cancel', args: [env.deviceId, 's1'] })
})

test('切换模型和权限模式成功后通知实时流刷新会话摘要；其他接口和失败时不通知', async () => {
  for (const [path, , body] of WRITE_ROUTES) await post(path, body)
  assert.deepEqual(env.changed, ['s1', 's1'])

  env.changed.length = 0
  env.behavior.error = new FacadeError(400, 'BAD_REQUEST', '这个模型当前不可用')
  assert.equal((await post('/v1/sessions/s1/model', { provider: 'p', model: 'm' })).status, 400)
  assert.deepEqual(env.changed, [])
})

test('回答待处理事项会记入审计日志（只有设备和事项 id，没有回答内容）', async () => {
  await post('/v1/attention/a1/answer', { answers: [{ id: 'q1', selected: ['机密内容'] }] })
  assert.deepEqual(env.audit, [{ event: 'attention.answered', detail: { deviceId: env.deviceId, attentionId: 'a1' } }])
})

test('【反向】写接口：没有 token 时 401，且不访问 DSH', async () => {
  for (const [path, , body] of WRITE_ROUTES) assert.equal((await post(path, body, { token: null })).status, 401, path)
  assert.equal((await get('/v1/sessions/s1/cancel', { method: 'POST', token: null })).status, 401)
  assert.equal(env.calls.length, 0)
})

test('【反向】写接口：请求体不是 JSON、Content-Type 不对、请求体过大时不访问 DSH', async () => {
  assert.equal((await post('/v1/sessions/s1/prompt', '{not json')).status, 400)
  assert.equal((await post('/v1/sessions/s1/prompt', '{}', { contentType: 'text/plain' })).status, 415)
  assert.equal((await post('/v1/sessions/s1/prompt', { requestId: 'r', text: 'x'.repeat(600 * 1024) })).status, 413)
  assert.equal((await post('/v1/attention/a1/answer', { pad: 'x'.repeat(20 * 1024) })).status, 413)
  assert.equal(env.calls.length, 0)
})

test('外观层的 409 原样返回', async () => {
  env.behavior.error = new FacadeError(409, 'ATTENTION_RESOLVED', '这项审批或提问已经在别处处理了')
  const res = await post('/v1/attention/a1/answer', { decision: 'allow' })
  assert.equal(res.status, 409)
  assert.equal(res.json().error.code, 'ATTENTION_RESOLVED')
  assert.equal(env.audit.length, 0)
})

test('【反向】项目操作：错误或撤销的 token 不能浏览、创建或登记', async () => {
  for (const token of ['A'.repeat(43), env.token]) {
    if (token === env.token) await env.devices.revoke(env.deviceId)
    assert.equal((await get('/v1/directories', { token })).status, 401)
    assert.equal((await post('/v1/directories', { path: '/tmp', name: 'new' }, { token })).status, 401)
    assert.equal((await post('/v1/workspaces', { path: '/tmp' }, { token })).status, 401)
  }
  assert.equal(env.calls.length, 0)
})
