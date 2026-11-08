// @ts-check
/**
 * dev-server 的纯函数：参数解析，以及从配对页 HTML 中读取配对码、设备、连接状态。
 * HTML 用真实的 renderAdminPage 生成，配对页改版时这里会失败，提醒同步修改 dev-server。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import qrcode from '../../src/vendor/qrcode.mjs'
import { renderAdminPage } from '../../src/admin/page.js'
import {
  DEFAULT_LAN_PORT, defaultHome, startOptions, parseCsrf, parsePairingCode, parseDshState, parseDevices, renderQr, groupFingerprint,
} from '../../scripts/dev-server-lib.mjs'

const FINGERPRINT = 'AbCdEfGh1234567890abcdefghijklmnopqrstuvwxy'

/** @param {Partial<import('../../src/admin/page.js').AdminView>} overrides */
function page(overrides = {}) {
  return renderAdminPage({
    csrfToken: 'csrf-token-1',
    pluginVersion: '0.1.0',
    dsh: { version: '0.2.2-rc.1', compatible: true, state: 'ready' },
    hostName: 'dev-box',
    fingerprint: FINGERPRINT,
    lanPort: 3290,
    lanBind: '0.0.0.0',
    addresses: [{ address: '192.168.3.26', interfaceName: 'eth0' }],
    selectedAddress: '192.168.3.26',
    pairing: { code: undefined, expiresAt: undefined, lockedUntil: undefined },
    devices: [],
    notice: undefined,
    ...overrides,
  })
}

test('默认端口不是插件默认的 3190 / 3191，配对页端口默认为局域网端口 + 1', () => {
  assert.equal(DEFAULT_LAN_PORT, 3290)
  assert.deepEqual(startOptions({}), { lanPort: 3290, adminPort: 3291, bind: '0.0.0.0' })
  assert.deepEqual(startOptions({ port: '4000' }), { lanPort: 4000, adminPort: 4001, bind: '0.0.0.0' })
  assert.deepEqual(startOptions({ port: '4000', 'admin-port': '5000', bind: '127.0.0.1' }), { lanPort: 4000, adminPort: 5000, bind: '127.0.0.1' })
})

test('非法的端口和监听地址直接报错', () => {
  for (const port of ['0', '65536', 'abc', '-1', '3290x', '']) assert.throws(() => startOptions({ port }), /--port/)
  assert.throws(() => startOptions({ port: '65535' }), /--admin-port/)
  assert.throws(() => startOptions({ port: '4000', 'admin-port': '4000' }), /不能相同/)
  assert.throws(() => startOptions({ bind: 'localhost' }), /IPv4/)
})

test('数据目录：环境变量优先，否则放在平台的缓存目录，不在仓库里', () => {
  assert.match(defaultHome({ DSHREMOTE_DEV_HOME: '/tmp/x' }, 'linux'), /x$/)
  assert.equal(defaultHome({ XDG_CACHE_HOME: '/c' }, 'linux').replace(/\\/g, '/').endsWith('/c/dshremote-dev'), true)
  assert.match(defaultHome({ LOCALAPPDATA: 'C:\\Users\\me\\AppData\\Local' }, 'win32'), /dshremote-dev$/)
})

test('从配对页读取 CSRF token、连接状态和配对码', () => {
  const idle = page({ dsh: { version: '0.2.2-rc.1', compatible: true, state: 'connecting' } })
  assert.equal(parseCsrf(idle), 'csrf-token-1')
  assert.equal(parseDshState(idle), 'connecting')
  assert.equal(parsePairingCode(idle), undefined)

  const withCode = page({ pairing: { code: 'K7M2Q9XP', expiresAt: Date.now() + 60_000, lockedUntil: undefined } })
  assert.equal(parseDshState(withCode), 'ready')
  assert.equal(parsePairingCode(withCode), 'K7M2Q9XP')
})

test('从配对页读取设备列表，设备名中的特殊字符被还原', () => {
  assert.deepEqual(parseDevices(page()), [])
  const html = page({
    devices: [
      { deviceId: 'd_abc', name: 'Pixel <8> & "me"', platform: 'android', appVersion: '0.1.0', createdAt: 1, lastSeenAt: 2 },
      { deviceId: 'd_def', name: '小米', platform: 'android', appVersion: '0.2.0', createdAt: 3, lastSeenAt: 4 },
    ],
  })
  const devices = parseDevices(html)
  assert.deepEqual(devices.map((d) => d.deviceId), ['d_abc', 'd_def'])
  assert.equal(devices[0]?.name, 'Pixel <8> & "me"')
  assert.equal(devices[1]?.client, 'android 0.2.0')
})

test('终端二维码：每个字符表示上下两个模块，内容与 qrcode 生成的一致', () => {
  const text = 'dshremote://pair?v=1&h=192.168.3.26&p=3290'
  const lines = renderQr(text)
  const qr = qrcode(0, 'M')
  qr.addData(text)
  qr.make()
  const size = qr.getModuleCount()
  // 上下各留 2 个模块的空白，两行合成一个字符
  assert.equal(lines.length, Math.ceil((size + 4) / 2))
  // 要匹配的正是终端的 ANSI 颜色转义序列
  // eslint-disable-next-line no-control-regex
  const cells = (/** @type {string} */ line) => [...line.matchAll(/\x1b\[38;5;(\d+)m\x1b\[48;5;(\d+)m▀/g)].map((m) => [m[1] === '16', m[2] === '16'])
  const first = cells(lines[1] ?? '')
  assert.equal(first.length, size + 4)
  // 第 2 行字符对应模块的第 0、1 行（前面是 2 行空白）
  for (let c = 0; c < size; c += 1) {
    assert.deepEqual(first[c + 2], [qr.isDark(0, c), qr.isDark(1, c)])
  }
  for (const line of lines) assert.ok(line.endsWith('\x1b[0m'))
})

test('指纹每 4 个字符一组', () => {
  assert.equal(groupFingerprint('ABCDEFGHIJ'), 'ABCD EFGH IJ')
})
