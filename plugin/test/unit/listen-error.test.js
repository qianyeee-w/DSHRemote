// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { listenError } from '../../src/listen-error.js'

/** @param {string} code */
const errno = (code) => Object.assign(new Error(`listen ${code}: 0.0.0.0:3190`), { code })

test('端口被占用：指出是哪个端口、常见原因和要改的配置项，保留原始错误', () => {
  const cause = errno('EADDRINUSE')
  const lan = listenError('lan', 3190, cause)
  assert.match(lan.message, /^局域网端口 3190 已被其他程序占用，插件没能启动/)
  assert.match(lan.message, /VS Code 的端口转发/)
  assert.match(lan.message, /lanPort/)
  assert.equal(lan.cause, cause)
  const admin = listenError('admin', 3191, errno('EADDRINUSE'))
  assert.match(admin.message, /^配对页端口 3191 已被其他程序占用/)
  assert.match(admin.message, /adminPort/)
  assert.doesNotMatch(admin.message, /lanPort/)
})

test('其他监听错误：给出错误码和要检查的配置项', () => {
  assert.match(listenError('lan', 3190, errno('EADDRNOTAVAIL')).message, /^局域网端口 3190 监听失败（EADDRNOTAVAIL）.*lanPort 和 lanBind/)
  assert.match(listenError('admin', 3191, errno('EACCES')).message, /^配对页端口 3191 监听失败（EACCES）.*adminPort$/)
  assert.match(listenError('admin', 3191, new Error('奇怪的错误')).message, /监听失败（奇怪的错误）/)
})
