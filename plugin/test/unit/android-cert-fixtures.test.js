// @ts-check
/**
 * App 的跨实现指纹测试用的固定数据（android/app/src/sharedTest/resources/plugin-certs/）。
 *
 * App 的测试检查「App 算出的指纹 = fingerprints.json」，这里检查「插件算出的指纹 = fingerprints.json」。
 * 两边任何一边的算法变了，都会有一边的测试失败。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { X509Certificate } from 'node:crypto'
import { spkiFingerprint } from '../../src/tls/certificate.js'

const dir = fileURLToPath(new URL('../../../android/app/src/sharedTest/resources/plugin-certs/', import.meta.url))

test('fingerprints.json 和插件对每张证书算出的指纹一致', () => {
  /** @type {Record<string, string>} */
  const expected = JSON.parse(readFileSync(`${dir}fingerprints.json`, 'utf8'))
  const certs = readdirSync(dir).filter((f) => f.endsWith('.crt')).sort()
  assert.deepEqual(Object.keys(expected).sort(), certs)
  for (const name of certs) {
    assert.equal(spkiFingerprint(readFileSync(`${dir}${name}`, 'utf8')), expected[name], name)
  }
})

test('固定数据覆盖「同一把私钥重新签发」和「不同私钥」两种情况', () => {
  const read = (/** @type {string} */ name) => new X509Certificate(readFileSync(`${dir}${name}`))
  const a1 = read('key-a-cert-1.crt')
  const a2 = read('key-a-cert-2.crt')
  const b = read('key-b-cert.crt')
  assert.notEqual(a1.serialNumber, a2.serialNumber)
  assert.equal(spkiFingerprint(a1.toString()), spkiFingerprint(a2.toString()))
  assert.notEqual(spkiFingerprint(a1.toString()), spkiFingerprint(b.toString()))
})

test('固定数据中没有私钥（红线第 5 条）', () => {
  for (const name of readdirSync(dir)) {
    assert.doesNotMatch(readFileSync(`${dir}${name}`, 'utf8'), /PRIVATE KEY/, name)
  }
})
