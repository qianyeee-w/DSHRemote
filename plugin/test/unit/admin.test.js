// @ts-check
/**
 * 本机配对页：页面内容、DNS rebinding、CSRF、点击劫持、XSS。
 */
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { startAdminServer } from '../../src/admin/server.js'
import { buildPairingUri, escapeHtml } from '../../src/admin/page.js'
import { lanAddresses } from '../../src/admin/addresses.js'
import { openInBrowser } from '../../src/admin/open-browser.js'
import { PairingCodes } from '../../src/pairing/codes.js'
import { DeviceRegistry } from '../../src/pairing/devices.js'
import { openStorage } from '../../src/storage.js'

const FINGERPRINT = 'AbCdEfGh1234567890abcdefghijklmnopqrstuvwxy'

/** @type {{ home: string, codes: PairingCodes, devices: DeviceRegistry, admin: import('../../src/admin/server.js').AdminServer, audit: string[] }} */
let env

beforeEach(async () => {
  const home = await mkdtemp(join(tmpdir(), 'dshremote-admin-'))
  const storage = await openStorage({ dshHome: home })
  const codes = new PairingCodes()
  const devices = await DeviceRegistry.open(storage)
  /** @type {string[]} */
  const audit = []
  const admin = await startAdminServer({
    port: 0,
    codes,
    devices,
    info: () => ({ fingerprint: FINGERPRINT, lanPort: 3190, lanBind: '0.0.0.0', pluginVersion: '0.1.0', dsh: { version: '0.2.2-rc.1', compatible: true, state: 'ready' } }),
    addresses: () => [{ address: '192.168.1.20', interfaceName: 'eth0' }, { address: '100.100.1.2', interfaceName: 'tailscale0' }],
    audit: (event) => audit.push(event),
  })
  env = { home, codes, devices, admin, audit }
})

afterEach(async () => {
  await env.admin.close()
  await env.devices.flush()
  await rm(env.home, { recursive: true, force: true })
})

/**
 * @param {string} path
 * @param {{ method?: string, host?: string, headers?: Record<string, string>, body?: string }} [o]
 * @returns {Promise<{ status: number, headers: http.IncomingHttpHeaders, body: string }>}
 */
function request(path, o = {}) {
  const port = env.admin.port()
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, path, method: o.method ?? 'GET', headers: { host: o.host ?? `127.0.0.1:${port}`, ...o.headers } }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (c) => { body += c })
      res.on('end', () => resolve({ status: res.statusCode ?? 0, headers: res.headers, body }))
    })
    req.on('error', reject)
    if (o.body !== undefined) req.write(o.body)
    req.end()
  })
}

/** 从配对页中取出 CSRF token */
async function csrfToken() {
  const page = await request('/')
  const match = /name="csrf" value="([^"]+)"/.exec(page.body)
  assert.ok(match, '页面中应有 CSRF token')
  return /** @type {string} */ (match[1])
}

/** 一个合法的表单 POST（同源、正确的 token） */
async function post(/** @type {string} */ path, /** @type {Record<string, string>} */ fields = {}, overrides = /** @type {{ origin?: string | null, contentType?: string, csrf?: string | null }} */ ({})) {
  const token = overrides.csrf === undefined ? await csrfToken() : overrides.csrf
  const form = new URLSearchParams({ ...(token === null ? {} : { csrf: token }), ...fields }).toString()
  /** @type {Record<string, string>} */
  const headers = { 'content-type': overrides.contentType ?? 'application/x-www-form-urlencoded' }
  const origin = overrides.origin === undefined ? `http://127.0.0.1:${env.admin.port()}` : overrides.origin
  if (origin !== null) headers.origin = origin
  return request(path, { method: 'POST', headers, body: form })
}

test('页面：安全响应头齐全，没有脚本', async () => {
  const res = await request('/')
  assert.equal(res.status, 200)
  assert.match(String(res.headers['content-type']), /text\/html/)
  assert.match(String(res.headers['content-security-policy']), /default-src 'none'/)
  assert.match(String(res.headers['content-security-policy']), /frame-ancestors 'none'/)
  assert.equal(res.headers['x-frame-options'], 'DENY')
  assert.equal(res.headers['cache-control'], 'no-store')
  // 【回归】no-referrer 会让浏览器提交表单时发送 Origin: null，配对页就无法生成配对码（T10 在 Edge 上发现）
  assert.equal(res.headers['referrer-policy'], 'same-origin')
  assert.doesNotMatch(res.body, /<script/i)
  assert.match(res.body, /AbCdEfGh/) // 指纹前 8 位
  assert.match(res.body, /0\.2\.2-rc\.1/) // DSH 版本
  assert.match(res.body, /data-state="ready">已连接</) // 插件到 DSH 的连接状态
})

test('生成配对码 → 页面显示二维码和配对码；取消后消失', async () => {
  const generated = await post('/pair-code')
  assert.equal(generated.status, 303)
  assert.equal(generated.headers.location, '/')
  const code = env.codes.status().code
  assert.ok(code)
  const page = await request('/')
  assert.match(page.body, /<svg/)
  assert.ok(page.body.includes(code.slice(0, 4)) && page.body.includes(code.slice(4)))
  assert.match(page.body, /192\.168\.1\.20/)
  assert.match(page.body, /http-equiv="refresh"/)

  assert.equal((await post('/pair-code/cancel')).status, 303)
  assert.equal(env.codes.status().code, undefined)
  assert.doesNotMatch((await request('/')).body, /<svg/)
})

test('多个地址时可以选择二维码使用的地址，非法的地址参数被忽略', async () => {
  await post('/pair-code')
  const tailnet = await request('/?addr=100.100.1.2')
  assert.match(tailnet.body, /<strong class="mono">100\.100\.1\.2<\/strong>/)
  const bogus = await request('/?addr=8.8.8.8')
  assert.match(bogus.body, /<strong class="mono">192\.168\.1\.20<\/strong>/)
})

test('撤销设备', async () => {
  const { device, token } = await env.devices.register({ name: 'Pixel 8', platform: 'android', appVersion: '0.1.0' })
  assert.match((await request('/')).body, /Pixel 8/)
  const res = await post('/devices/revoke', { deviceId: device.deviceId })
  assert.equal(res.status, 303)
  assert.equal(env.devices.authenticate(token), undefined)
  const page = await request('/')
  assert.match(page.body, /已撤销该设备/)
  assert.doesNotMatch(page.body, /Pixel 8/)
  assert.ok(env.audit.includes('admin.device-revoked'))
})

test('【反向】DNS rebinding：Host 不是本机地址时拒绝（包括页面本身，防止读出 CSRF token 和配对码）', async () => {
  env.codes.generate()
  const port = env.admin.port()
  for (const host of ['evil.example', `evil.example:${port}`, `127.0.0.1:${port + 1}`, `127.0.0.1.evil.example:${port}`, `192.168.1.20:${port}`]) {
    const res = await request('/', { host })
    assert.equal(res.status, 403, `host=${host}`)
    assert.doesNotMatch(res.body, /csrf|<svg/)
  }
  assert.equal((await request('/', { host: `localhost:${port}` })).status, 200)
})

test('【反向】没有 Host 头或 Host 为空时拒绝（用原始 socket 发送，避免客户端自动补全 Host）', async () => {
  const port = env.admin.port()
  for (const raw of ['GET / HTTP/1.0\r\n\r\n', 'GET / HTTP/1.1\r\nHost:\r\nConnection: close\r\n\r\n']) {
    const response = await new Promise((resolve) => {
      const socket = net.connect(port, '127.0.0.1', () => socket.write(raw))
      let data = ''
      socket.on('data', (c) => { data += c })
      socket.on('close', () => resolve(data))
      socket.on('error', () => resolve(data))
    })
    assert.match(String(response), /^HTTP\/1\.[01] (400|403)/, JSON.stringify(raw))
    assert.doesNotMatch(String(response), /csrf/)
  }
})

test('【反向】CSRF：缺少 Origin、Origin 不对、Origin 为 null 时拒绝，且状态不变', async () => {
  const { device, token } = await env.devices.register({ name: 'Pixel 8', platform: 'android', appVersion: '0.1.0' })
  for (const origin of [null, 'http://evil.example', 'null', `http://127.0.0.1:${env.admin.port() + 1}`, `https://127.0.0.1:${env.admin.port()}`]) {
    assert.equal((await post('/pair-code', {}, { origin })).status, 403, `origin=${origin}`)
    assert.equal((await post('/devices/revoke', { deviceId: device.deviceId }, { origin })).status, 403, `origin=${origin}`)
  }
  assert.equal(env.codes.status().code, undefined)
  assert.ok(env.devices.authenticate(token), '设备不应被撤销')
  assert.ok(env.audit.includes('admin.csrf-rejected'))
})

test('【反向】CSRF：Origin 正确但 token 缺失或错误时拒绝', async () => {
  for (const csrf of [null, '', 'x', 'A'.repeat(43)]) {
    assert.equal((await post('/pair-code', {}, { csrf })).status, 403, `csrf=${csrf}`)
  }
  assert.equal(env.codes.status().code, undefined)
})

test('【反向】CSRF：Content-Type 不是表单时拒绝（防止 text/plain 之类的跨站简单请求）', async () => {
  for (const contentType of ['text/plain', 'application/json', 'multipart/form-data; boundary=x']) {
    assert.equal((await post('/pair-code', {}, { contentType })).status, 415, contentType)
  }
  assert.equal(env.codes.status().code, undefined)
})

test('【反向】会改变状态的接口不接受 GET；未知路径 404；超大表单 413', async () => {
  assert.equal((await request('/pair-code')).status, 405)
  assert.equal((await request('/devices/revoke?deviceId=x')).status, 405)
  assert.equal((await request('/nope')).status, 404)
  assert.equal((await post('/pair-code', { padding: 'x'.repeat(5000) })).status, 413)
  assert.equal(env.codes.status().code, undefined)
})

test('【反向】XSS：设备名中的 HTML 被转义', async () => {
  await env.devices.register({ name: '<script>alert(1)</script><img src=x onerror=alert(1)>', platform: 'android', appVersion: '0.1.0' })
  const page = await request('/')
  assert.doesNotMatch(page.body, /<script>alert/)
  assert.doesNotMatch(page.body, /<img src=x/)
  assert.match(page.body, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/)
})

test('锁定状态显示在页面上；重新生成配对码可解除', async () => {
  env.codes.generate()
  for (let i = 0; i < 5; i++) env.codes.verify('WRONGWRO')
  assert.match((await request('/')).body, /已锁定/)
  await post('/pair-code')
  assert.equal(env.codes.status().lockedUntil, undefined)
})

test('配对二维码内容符合 protocol.md 2.1', () => {
  const uri = buildPairingUri({ host: '192.168.1.20', port: 3190, fingerprint: FINGERPRINT, code: 'K7M2Q9XP', name: '我的 电脑&x' })
  const parsed = new URL(uri)
  assert.equal(parsed.protocol, 'dshremote:')
  assert.equal(parsed.searchParams.get('v'), '1')
  assert.equal(parsed.searchParams.get('h'), '192.168.1.20')
  assert.equal(parsed.searchParams.get('p'), '3190')
  assert.equal(parsed.searchParams.get('fp'), FINGERPRINT)
  assert.equal(parsed.searchParams.get('c'), 'K7M2Q9XP')
  assert.equal(parsed.searchParams.get('n'), '我的 电脑&x')
})

test('escapeHtml', () => {
  assert.equal(escapeHtml(`<a href="x">'&'</a>`), '&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;')
})

test('地址排序：家用局域网优先，跳过回环和 IPv6；指定监听地址时只用它', () => {
  /** @type {any} */
  const interfaces = {
    lo: [{ family: 'IPv4', address: '127.0.0.1', internal: true }],
    tailscale0: [{ family: 'IPv4', address: '100.101.2.3', internal: false }],
    docker0: [{ family: 'IPv4', address: '172.17.0.1', internal: false }],
    eth0: [{ family: 'IPv4', address: '192.168.1.20', internal: false }, { family: 'IPv6', address: 'fe80::1', internal: false }],
    wlan0: [{ family: 'IPv4', address: '10.0.0.5', internal: false }],
  }
  // docker0 是虚拟网卡，排在 Tailscale 后面
  assert.deepEqual(lanAddresses('0.0.0.0', interfaces).map((a) => a.address), ['192.168.1.20', '10.0.0.5', '100.101.2.3', '172.17.0.1'])
  assert.deepEqual(lanAddresses('10.0.0.5', interfaces).map((a) => a.address), ['10.0.0.5'])
})

test('【回归】地址排序：Windows 上虚拟机、WSL 的虚拟网卡排在真实网卡后面（T10 中 VMnet1 曾排在以太网前面）', () => {
  /** @type {any} */
  const interfaces = {
    'VMware Network Adapter VMnet1': [{ family: 'IPv4', address: '192.168.142.1', internal: false }],
    'VMware Network Adapter VMnet8': [{ family: 'IPv4', address: '192.168.80.1', internal: false }],
    'vEthernet (WSL (Hyper-V firewall))': [{ family: 'IPv4', address: '172.25.0.1', internal: false }],
    'VirtualBox Host-Only Network': [{ family: 'IPv4', address: '192.168.56.1', internal: false }],
    以太网: [{ family: 'IPv4', address: '192.168.3.25', internal: false }],
    Tailscale: [{ family: 'IPv4', address: '100.88.1.2', internal: false }],
    'Loopback Pseudo-Interface 1': [{ family: 'IPv4', address: '127.0.0.1', internal: true }],
  }
  const order = lanAddresses('0.0.0.0', interfaces).map((a) => a.interfaceName)
  assert.deepEqual(order.slice(0, 2), ['以太网', 'Tailscale'])
  assert.ok(order.slice(2).every((name) => /VMware|vEthernet|VirtualBox/.test(name)))
})

test('只允许用浏览器打开本机配对页', async () => {
  await assert.rejects(openInBrowser('http://evil.example/'), /只允许/)
  await assert.rejects(openInBrowser('file:///etc/passwd'), /只允许/)
  await assert.rejects(openInBrowser('http://127.0.0.1:3191/x'), /只允许/)
})
