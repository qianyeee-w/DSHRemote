// @ts-check
/**
 * 通过真实的 TLS 服务、按指纹固定的客户端，走完配对与认证的整个流程。
 */
import { unusedFacade } from '../helpers/stub-facade.js'
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { generatePrivateKeyPem, issueSelfSignedCertificate, spkiFingerprint } from '../../src/tls/certificate.js'
import { startLanServer } from '../../src/server/https.js'
import { createRouter } from '../../src/server/routes.js'
import { PairingCodes, MAX_FAILED_ATTEMPTS } from '../../src/pairing/codes.js'
import { DeviceRegistry } from '../../src/pairing/devices.js'
import { openStorage } from '../../src/storage.js'
import { pinnedRequest } from '../helpers/pinned-client.js'

const keyPem = generatePrivateKeyPem()
const certPem = issueSelfSignedCertificate(keyPem)
const fingerprint = spkiFingerprint(certPem)
const HOST = '127.0.0.1'

/** @type {{ home: string, codes: PairingCodes, devices: DeviceRegistry, server: import('../../src/server/https.js').LanServer, audit: { event: string, detail?: Record<string, string> }[] }} */
let env

beforeEach(async () => {
  const home = await mkdtemp(join(tmpdir(), 'dshremote-pair-'))
  const storage = await openStorage({ dshHome: home })
  const codes = new PairingCodes()
  const devices = await DeviceRegistry.open(storage)
  /** @type {{ event: string, detail?: Record<string, string> }[]} */
  const audit = []
  const router = createRouter({
    dshStatus: () => ({ version: null, compatible: null }), facade: unusedFacade,
    codes,
    devices,
    audit: (event, detail) => audit.push({ event, ...(detail ? { detail } : {}) }),
  })
  const server = await startLanServer({ identity: { keyPem, certPem }, host: HOST, port: 0, handler: router })
  env = { home, codes, devices, server, audit }
})

afterEach(async () => {
  await env.server.close()
  await env.devices.flush()
  await rm(env.home, { recursive: true, force: true })
})

/** @param {string} path @param {{ method?: string, headers?: Record<string, string>, body?: unknown, rawBody?: string }} [options] */
function call(path, options = {}) {
  const body = options.rawBody ?? (options.body === undefined ? undefined : JSON.stringify(options.body))
  return pinnedRequest({
    host: HOST,
    port: env.server.port(),
    fingerprint,
    path,
    method: options.method ?? (body === undefined ? 'GET' : 'POST'),
    headers: { ...(body === undefined ? {} : { 'content-type': 'application/json' }), ...options.headers },
    ...(body === undefined ? {} : { body }),
  })
}

/** @param {string} code */
const pairBody = (code) => ({ code, deviceName: 'Pixel 8', platform: 'android', appVersion: '0.1.0' })

test('完整流程：配对 → /v1/me → 自行取消配对 → token 失效', async () => {
  const { code } = env.codes.generate()
  const paired = await call('/v1/pair', { body: pairBody(code) })
  assert.equal(paired.status, 200)
  const { deviceId, token, host } = paired.json()
  assert.match(deviceId, /^d_/)
  assert.match(token, /^[A-Za-z0-9_-]{43}$/)
  assert.equal(typeof host.name, 'string')
  assert.equal(paired.headers['cache-control'], 'no-store')

  const me = await call('/v1/me', { headers: { authorization: `Bearer ${token}` } })
  assert.equal(me.status, 200)
  assert.deepEqual(me.json(), { deviceId, name: 'Pixel 8', platform: 'android', createdAt: me.json().createdAt })

  const unpair = await call('/v1/me', { method: 'DELETE', headers: { authorization: `Bearer ${token}` } })
  assert.equal(unpair.status, 204)

  const after = await call('/v1/me', { headers: { authorization: `Bearer ${token}` } })
  assert.equal(after.status, 401)
  assert.equal(after.json().error.code, 'TOKEN_INVALID')
})

test('【反向】配对码只能用一次', async () => {
  const { code } = env.codes.generate()
  assert.equal((await call('/v1/pair', { body: pairBody(code) })).status, 200)
  const again = await call('/v1/pair', { body: pairBody(code) })
  assert.equal(again.status, 400)
  assert.equal(again.json().error.code, 'PAIRING_CODE_INVALID')
})

test('【反向】错误的配对码：400，响应中不出现正确的配对码', async () => {
  const { code } = env.codes.generate()
  const res = await call('/v1/pair', { body: pairBody('WRONGWRO') })
  assert.equal(res.status, 400)
  assert.equal(res.json().error.code, 'PAIRING_CODE_INVALID')
  assert.ok(!res.body.includes(code))
})

test('【反向】输错 5 次后锁定：429 + Retry-After，锁定期间正确的码也不行', async () => {
  const { code } = env.codes.generate()
  for (let i = 1; i < MAX_FAILED_ATTEMPTS; i++) assert.equal((await call('/v1/pair', { body: pairBody('WRONGWRO') })).status, 400)
  const locked = await call('/v1/pair', { body: pairBody('WRONGWRO') })
  assert.equal(locked.status, 429)
  assert.equal(locked.json().error.code, 'PAIRING_LOCKED')
  assert.equal(locked.headers['retry-after'], '60')
  const correct = await call('/v1/pair', { body: pairBody(code) })
  assert.equal(correct.status, 429)
  assert.deepEqual(env.devices.list(), [])
})

test('【反向】格式不对的请求不消耗尝试次数', async () => {
  const { code } = env.codes.generate()
  for (let i = 0; i < MAX_FAILED_ATTEMPTS + 2; i++) {
    const res = await call('/v1/pair', { body: { code: 'WRONGWRO' } }) // 缺少 deviceName 等字段
    assert.equal(res.status, 400)
    assert.equal(res.json().error.code, 'BAD_REQUEST')
  }
  assert.equal((await call('/v1/pair', { body: pairBody(code) })).status, 200)
})

test('【反向】请求体检查：Content-Type、大小、JSON 格式、字段格式', async () => {
  env.codes.generate()
  const wrongType = await call('/v1/pair', { rawBody: JSON.stringify(pairBody('X')), headers: { 'content-type': 'text/plain' } })
  assert.equal(wrongType.status, 415)
  const tooLarge = await call('/v1/pair', { body: { ...pairBody('X'), padding: 'x'.repeat(5000) } })
  assert.equal(tooLarge.status, 413)
  const notJson = await call('/v1/pair', { rawBody: '{ nope' })
  assert.equal(notJson.status, 400)
  for (const body of [
    { ...pairBody('X'), deviceName: '' },
    { ...pairBody('X'), deviceName: 'x'.repeat(65) },
    { ...pairBody('X'), deviceName: 'bad\u0000name' },
    { ...pairBody('X'), platform: 'Android OS' },
    { ...pairBody('X'), appVersion: '1.0; drop' },
    { ...pairBody('X'), code: 123 },
    [1, 2],
  ]) {
    const res = await call('/v1/pair', { body })
    assert.equal(res.status, 400, JSON.stringify(body))
  }
})

test('【反向】token 缺失、格式不对、换了认证方式、不存在：一律 401，并带 WWW-Authenticate', async () => {
  const { code } = env.codes.generate()
  const { token } = (await call('/v1/pair', { body: pairBody(code) })).json()
  for (const authorization of [undefined, '', 'Bearer', `Basic ${token}`, `bearer ${token}`, `Bearer ${token} extra`, `Bearer ${'A'.repeat(43)}`, `Bearer ${token.slice(0, -1)}`]) {
    const res = await call('/v1/me', { headers: authorization === undefined ? {} : { authorization } })
    assert.equal(res.status, 401, String(authorization))
    assert.equal(res.headers['www-authenticate'], 'Bearer')
    assert.ok(!res.body.includes(token))
  }
})

test('【安全】审计记录和磁盘上都没有 token 和配对码', async () => {
  const { code } = env.codes.generate()
  await call('/v1/pair', { body: pairBody('WRONGWRO') })
  const { token } = (await call('/v1/pair', { body: pairBody(code) })).json()
  await env.devices.flush()
  const serialized = JSON.stringify(env.audit)
  assert.ok(!serialized.includes(token))
  assert.ok(!serialized.includes(code))
  assert.ok(!serialized.includes('WRONGWRO'))
  assert.deepEqual(env.audit.map((a) => a.event), ['pair.rejected', 'pair.accepted'])
  const raw = await readFile(join(env.home, 'dshremote', 'devices.json'), 'utf8')
  assert.ok(!raw.includes(token))
})

test('/v1/info 仍然无需认证', async () => {
  assert.equal((await call('/v1/info')).status, 200)
})
