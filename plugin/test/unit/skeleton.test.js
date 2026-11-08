// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
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

test('默认启用：注册一个生命周期，卸载函数可调用', () => {
  /** @type {(() => void) | undefined} */
  let dispose
  const ctx = {
    /** @param {() => () => void} fn */
    effect: (fn) => { dispose = fn() },
    logger: { info() {} },
  }
  plugin.apply(ctx, { enabled: true })
  assert.equal(typeof dispose, 'function')
  dispose?.()
})
