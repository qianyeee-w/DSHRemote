// @ts-check
import { unusedFacade } from '../helpers/stub-facade.js'
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import { generatePrivateKeyPem, issueSelfSignedCertificate, spkiFingerprint } from '../../src/tls/certificate.js'
import { startLanServer } from '../../src/server/https.js'
import { createRouter, PROTOCOL_VERSION } from '../../src/server/routes.js'
import { pinnedRequest, connectPinned, PinMismatchError } from '../helpers/pinned-client.js'
import { PairingCodes } from '../../src/pairing/codes.js'

/** 不涉及设备的测试用的路由上下文：没有任何已配对设备 */
const stubDevices = /** @type {any} */ ({ authenticate: () => undefined })
const baseCtx = () => ({ dshStatus: () => ({ version: null, compatible: null }), facade: unusedFacade, codes: new PairingCodes(), devices: stubDevices })

const keyPem = generatePrivateKeyPem()
const certPem = issueSelfSignedCertificate(keyPem)
const fingerprint = spkiFingerprint(certPem)
const wrongFingerprint = spkiFingerprint(issueSelfSignedCertificate(generatePrivateKeyPem()))

let handled = 0
const router = createRouter(baseCtx())
/** @type {import('../../src/server/https.js').LanServer} */
let lan
const HOST = '127.0.0.1'

before(async () => {
  lan = await startLanServer({
    identity: { keyPem, certPem },
    host: HOST,
    port: 0,
    handler: (req, res) => { handled++; router(req, res) },
  })
})
after(async () => { await lan.close() })

test('指纹正确：能访问 /v1/info（无需认证）', async () => {
  const res = await pinnedRequest({ host: HOST, port: lan.port(), fingerprint, path: '/v1/info' })
  assert.equal(res.status, 200)
  assert.match(String(res.headers['content-type']), /application\/json/)
  assert.equal(res.headers['cache-control'], 'no-store')
  const body = res.json()
  assert.equal(body.protocol, PROTOCOL_VERSION)
  assert.equal(typeof body.pluginVersion, 'string')
  assert.equal(body.dsh.baseline, '0.2.2-rc.1')
})

test('【反向】指纹错误：拒绝连接，并且没有任何请求到达服务器', async () => {
  const before = handled
  await assert.rejects(
    pinnedRequest({ host: HOST, port: lan.port(), fingerprint: wrongFingerprint, path: '/v1/info' }),
    (error) => error instanceof PinMismatchError && /** @type {PinMismatchError} */ (error).actual === fingerprint,
  )
  await new Promise((r) => setTimeout(r, 100))
  assert.equal(handled, before, '指纹不匹配时，不应发出任何请求')
})

test('【反向】明文 HTTP：得不到任何 HTTP 响应', async () => {
  await assert.rejects(new Promise((resolve, reject) => {
    const req = http.get({ host: HOST, port: lan.port(), path: '/v1/info', timeout: 3000 }, (res) => {
      resolve(res.statusCode)
    })
    req.on('error', reject)
    req.on('timeout', () => { req.destroy(new Error('timeout')) })
  }))
})

test('【反向】明文 HTTP：服务器不返回任何明文内容', async () => {
  const received = await new Promise((resolve) => {
    const socket = net.connect(lan.port(), HOST, () => {
      socket.write('GET /v1/info HTTP/1.1\r\nHost: x\r\n\r\n')
    })
    let data = ''
    socket.on('data', (chunk) => { data += chunk.toString('latin1') })
    socket.on('close', () => resolve(data))
    socket.on('error', () => resolve(data))
    setTimeout(() => socket.destroy(), 3000)
  })
  assert.doesNotMatch(String(received), /HTTP\/1\.[01]/)
  assert.doesNotMatch(String(received), /pluginVersion/)
})

test('【反向】TLS 1.1 及以下被拒绝', async () => {
  await assert.rejects(connectPinned({ host: HOST, port: lan.port(), fingerprint, minVersion: 'TLSv1', maxVersion: 'TLSv1.1' }))
})

test('TLS 1.3 可用', async () => {
  const socket = await connectPinned({ host: HOST, port: lan.port(), fingerprint, minVersion: 'TLSv1.3' })
  assert.equal(socket.getProtocol(), 'TLSv1.3')
  socket.destroy()
})

test('未知路径返回 404，错误方法返回 405，错误格式统一', async () => {
  const notFound = await pinnedRequest({ host: HOST, port: lan.port(), fingerprint, path: '/v1/nope' })
  assert.equal(notFound.status, 404)
  assert.deepEqual(Object.keys(notFound.json()), ['error'])
  assert.equal(notFound.json().error.code, 'NOT_FOUND')

  const wrongMethod = await pinnedRequest({ host: HOST, port: lan.port(), fingerprint, path: '/v1/info', method: 'POST', body: '{}' })
  assert.equal(wrongMethod.status, 405)
  assert.equal(wrongMethod.headers.allow, 'GET')
  assert.equal(wrongMethod.json().error.code, 'METHOD_NOT_ALLOWED')
})

test('【反向】DSH 的接口路径不会被转发（安全红线第 8 条）', async () => {
  for (const path of ['/api/session/list', '/api/remote.mux', '/', '/?token=abc']) {
    const res = await pinnedRequest({ host: HOST, port: lan.port(), fingerprint, path })
    assert.equal(res.status, 404, path)
  }
})

test('【反向】需要认证的接口：没有有效 token 时一律拒绝，处理函数不会执行', async () => {
  const strict = createRouter(baseCtx(), {
    routes: [{ method: 'GET', path: '/v1/me', auth: true, handle: () => assert.fail('不应执行') }],
  })
  const server = await startLanServer({ identity: { keyPem, certPem }, host: HOST, port: 0, handler: strict })
  try {
    const res = await pinnedRequest({ host: HOST, port: server.port(), fingerprint, path: '/v1/me' })
    assert.equal(res.status, 401)
    assert.equal(res.json().error.code, 'TOKEN_INVALID')
  } finally {
    await server.close()
  }
})

test('处理函数抛错时返回 500，且不泄露错误细节', async () => {
  /** @type {unknown[]} */
  const errors = []
  const failing = createRouter(baseCtx(), {
    routes: [{ method: 'GET', path: '/v1/boom', auth: false, handle: () => { throw new Error('secret-detail /home/me/.dsh') } }],
    onError: (error) => errors.push(error),
  })
  const server = await startLanServer({ identity: { keyPem, certPem }, host: HOST, port: 0, handler: failing })
  try {
    const res = await pinnedRequest({ host: HOST, port: server.port(), fingerprint, path: '/v1/boom' })
    assert.equal(res.status, 500)
    assert.doesNotMatch(res.body, /secret-detail|\.dsh/)
    assert.equal(errors.length, 1)
  } finally {
    await server.close()
  }
})
