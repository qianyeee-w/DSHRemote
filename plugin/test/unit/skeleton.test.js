// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile, mkdtemp, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DEFAULT_CONFIG } from '../../src/config.js'
import * as plugin from '../../index.js'
import { DSH_BASELINE_VERSION, SUPPORTED_DSH_RANGE } from '../../src/dsh/versions.js'

const pkg = JSON.parse(await readFile(new URL('../../package.json', import.meta.url), 'utf8'))

test('插件导出 name / inject / apply', () => {
  assert.equal(plugin.name, 'dshremote')
  assert.deepEqual(plugin.inject, ['webServer', 'connection'])
  assert.equal(typeof plugin.apply, 'function')
})

test('只注入允许的 DSH 服务（plugin/AGENTS.md）', () => {
  for (const service of plugin.inject) {
    assert.ok(['webServer', 'connection'].includes(service), `不允许注入 ${service}`)
  }
})

test('支持的 DSH 版本范围只有一个来源：package.json 与 versions.js 一致', () => {
  assert.equal(pkg.engines.dsh, SUPPORTED_DSH_RANGE)
  assert.ok(SUPPORTED_DSH_RANGE.startsWith(`>=${DSH_BASELINE_VERSION} `))
})

test('enabled: false 时不注册任何生命周期', () => {
  let effects = 0
  const ctx = { effect: () => { effects++ }, logger: { info() {} } }
  plugin.apply(ctx, { enabled: false })
  assert.equal(effects, 0)
})

test('默认启用：注册一个生命周期，打开状态目录，卸载函数可调用', async () => {
  const home = await mkdtemp(join(tmpdir(), 'dshremote-skeleton-'))
  const previous = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    /** @type {Promise<() => Promise<void>> | undefined} */
    let pending
    const ctx = {
      /** @param {() => Promise<() => Promise<void>>} fn */
      effect: (fn) => { pending = fn() },
      logger: { info() {}, warn() {}, error() {} },
    }
    // 端口用 0 之外的随机高位端口，避免和本机已有服务冲突
    const lanPort = 20000 + Math.floor(Math.random() * 20000)
    plugin.apply(ctx, { enabled: true, lanBind: '127.0.0.1', lanPort, adminPort: lanPort + 1, openAdminOnFirstRun: false })
    assert.ok(pending, '应当注册生命周期')
    const dispose = await pending
    assert.ok((await stat(join(home, 'dshremote'))).isDirectory())
    assert.ok((await stat(join(home, 'dshremote', 'tls-cert.pem'))).isFile())
    assert.equal(typeof dispose, 'function')
    await dispose()
  } finally {
    if (previous === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = previous
    await rm(home, { recursive: true, force: true })
  }
})

test('配置非法时 apply 直接抛出，让 DSH 把插件标记为加载失败', () => {
  const ctx = { effect() { assert.fail('不应注册生命周期') }, logger: { info() {} } }
  assert.throws(() => plugin.apply(ctx, { lanPort: 'abc' }), /配置错误/)
  assert.throws(() => plugin.apply(ctx, { unknownKey: 1 }), /未知的配置项/)
})

test('cordis.patch.yml 的默认配置与 DEFAULT_CONFIG 一致', async () => {
  const yml = await readFile(new URL('../../cordis.patch.yml', import.meta.url), 'utf8')
  for (const [key, value] of Object.entries(DEFAULT_CONFIG)) {
    assert.match(yml, new RegExp(`^\\s+${key}: ${String(value).replaceAll('.', '\\.')}$`, 'm'), `cordis.patch.yml 中 ${key} 应为 ${value}`)
  }
})
