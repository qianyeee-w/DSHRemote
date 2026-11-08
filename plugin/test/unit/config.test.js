// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseConfig, DEFAULT_CONFIG, ConfigError } from '../../src/config.js'

test('没有配置时使用默认值', () => {
  assert.deepEqual(parseConfig(undefined), DEFAULT_CONFIG)
  assert.deepEqual(parseConfig(null), DEFAULT_CONFIG)
  assert.deepEqual(parseConfig({}), DEFAULT_CONFIG)
  assert.equal(DEFAULT_CONFIG.lanPort, 3190)
  assert.equal(DEFAULT_CONFIG.adminPort, 3191)
})

test('合法的覆盖值被采用', () => {
  const config = parseConfig({ lanPort: 4000, adminPort: 4001, lanBind: '192.168.1.10', openAdminOnFirstRun: false, enabled: false })
  assert.equal(config.lanPort, 4000)
  assert.equal(config.adminPort, 4001)
  assert.equal(config.lanBind, '192.168.1.10')
  assert.equal(config.openAdminOnFirstRun, false)
  assert.equal(config.enabled, false)
  assert.equal(parseConfig({ lanBind: '::' }).lanBind, '::')
})

test('返回的配置不可修改', () => {
  const config = parseConfig({ lanPort: 4000 })
  assert.throws(() => { /** @type {any} */ (config).lanPort = 1 })
})

test('非法端口报错', () => {
  for (const lanPort of [0, 65536, -1, 3190.5, '3190', null, NaN]) {
    assert.throws(() => parseConfig({ lanPort }), ConfigError, `lanPort=${String(lanPort)}`)
  }
  assert.throws(() => parseConfig({ adminPort: 70000 }), ConfigError)
})

test('两个端口相同时报错', () => {
  assert.throws(() => parseConfig({ lanPort: 5000, adminPort: 5000 }), /不能相同/)
})

test('lanBind 只接受 IP 字面量', () => {
  for (const lanBind of ['localhost', 'my-pc', '', '300.1.1.1', 123, '0.0.0.0:3190']) {
    assert.throws(() => parseConfig({ lanBind }), ConfigError, `lanBind=${String(lanBind)}`)
  }
})

test('布尔值必须是真正的布尔类型', () => {
  for (const enabled of ['true', 1, 0, null]) {
    assert.throws(() => parseConfig({ enabled }), ConfigError, `enabled=${String(enabled)}`)
  }
  assert.throws(() => parseConfig({ openAdminOnFirstRun: 'no' }), ConfigError)
})

test('拼错或未知的配置项报错', () => {
  assert.throws(() => parseConfig({ lanport: 4000 }), /未知的配置项 "lanport"/)
  assert.throws(() => parseConfig({ tailscale: true }), ConfigError)
})

test('config 不是对象时报错', () => {
  for (const raw of ['x', 1, true, [1]]) {
    assert.throws(() => parseConfig(raw), ConfigError)
  }
})
