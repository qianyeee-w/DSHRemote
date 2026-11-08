// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  PairingCodes, normalizeCode, CODE_ALPHABET, CODE_LENGTH, CODE_TTL_MS, MAX_FAILED_ATTEMPTS, LOCK_MS,
} from '../../src/pairing/codes.js'

/** @param {import('../../src/pairing/codes.js').VerifyResult} r */
const reason = (r) => (r.ok ? 'ok' : r.reason)

function clock(start = 1_000_000) {
  let t = start
  return { now: () => t, advance: (/** @type {number} */ ms) => { t += ms } }
}

test('字符集不含容易混淆的字符，配对码长度为 8', () => {
  for (const c of '0O1IL') assert.ok(!CODE_ALPHABET.includes(c), c)
  assert.equal(CODE_ALPHABET.length, 31)
  const codes = new PairingCodes()
  for (let i = 0; i < 200; i++) {
    const { code } = codes.generate()
    assert.equal(code.length, CODE_LENGTH)
    for (const c of code) assert.ok(CODE_ALPHABET.includes(c))
  }
})

test('配对码是随机的', () => {
  const codes = new PairingCodes()
  const seen = new Set(Array.from({ length: 200 }, () => codes.generate().code))
  assert.equal(seen.size, 200)
})

test('正确的配对码可以使用一次，用后作废', () => {
  const codes = new PairingCodes()
  const { code } = codes.generate()
  assert.deepEqual(codes.verify(code), { ok: true })
  assert.deepEqual(codes.verify(code), { ok: false, reason: 'none' })
})

test('输入格式宽松：大小写、空格、连字符不影响', () => {
  assert.equal(normalizeCode(' ab-cd ef gh '), 'ABCDEFGH')
  const codes = new PairingCodes()
  const { code } = codes.generate()
  const messy = `${code.slice(0, 4).toLowerCase()} - ${code.slice(4)}`
  assert.deepEqual(codes.verify(messy), { ok: true })
})

test('【反向】5 分钟后过期', () => {
  const c = clock()
  const codes = new PairingCodes({ now: c.now })
  const { code, expiresAt } = codes.generate()
  assert.equal(expiresAt, c.now() + CODE_TTL_MS)
  c.advance(CODE_TTL_MS)
  assert.deepEqual(codes.verify(code), { ok: false, reason: 'expired' })
  assert.deepEqual(codes.verify(code), { ok: false, reason: 'none' })
})

test('【反向】生成新码后旧码立刻失效', () => {
  const codes = new PairingCodes()
  const old = codes.generate().code
  const fresh = codes.generate().code
  assert.notEqual(old, fresh)
  assert.equal(codes.verify(old).ok, false)
  assert.deepEqual(codes.verify(fresh), { ok: true })
})

test('【反向】没有生成过配对码时，任何输入都失败', () => {
  const codes = new PairingCodes()
  assert.deepEqual(codes.verify('ABCDEFGH'), { ok: false, reason: 'none' })
})

test('【反向】连续输错 5 次：配对码作废，并锁定 1 分钟；锁定期间正确的码也不行', () => {
  const c = clock()
  const codes = new PairingCodes({ now: c.now })
  const { code } = codes.generate()
  for (let i = 1; i < MAX_FAILED_ATTEMPTS; i++) assert.deepEqual(codes.verify('WRONGWRO'), { ok: false, reason: 'invalid' })
  assert.deepEqual(codes.verify('WRONGWRO'), { ok: false, reason: 'locked', retryAfterMs: LOCK_MS })
  assert.equal(codes.verify(code).ok, false)
  assert.equal(reason(codes.verify(code)), 'locked')

  c.advance(LOCK_MS)
  // 锁定结束后，原来的码已经作废
  assert.deepEqual(codes.verify(code), { ok: false, reason: 'none' })
})

test('长度不同的输入算作输错，同样计入次数', () => {
  const codes = new PairingCodes()
  codes.generate()
  for (let i = 1; i < MAX_FAILED_ATTEMPTS; i++) assert.equal(reason(codes.verify('X')), 'invalid')
  assert.equal(reason(codes.verify('')), 'locked')
})

test('输对之后清零错误次数', () => {
  const codes = new PairingCodes()
  codes.generate()
  for (let i = 1; i < MAX_FAILED_ATTEMPTS; i++) codes.verify('WRONGWRO')
  const { code } = codes.generate()
  for (let i = 1; i < MAX_FAILED_ATTEMPTS; i++) assert.equal(reason(codes.verify('WRONGWRO')), 'invalid')
  assert.deepEqual(codes.verify(code), { ok: true })
})

test('在本机重新生成配对码会解除锁定', () => {
  const codes = new PairingCodes()
  codes.generate()
  for (let i = 0; i < MAX_FAILED_ATTEMPTS; i++) codes.verify('WRONGWRO')
  assert.ok(codes.status().lockedUntil)
  const { code } = codes.generate()
  assert.equal(codes.status().lockedUntil, undefined)
  assert.deepEqual(codes.verify(code), { ok: true })
})

test('status 和 clear', () => {
  const c = clock()
  const codes = new PairingCodes({ now: c.now })
  assert.deepEqual(codes.status(), { code: undefined, expiresAt: undefined, lockedUntil: undefined })
  const { code, expiresAt } = codes.generate()
  assert.deepEqual(codes.status(), { code, expiresAt, lockedUntil: undefined })
  codes.clear()
  assert.equal(codes.status().code, undefined)
  assert.equal(reason(codes.verify(code)), 'none')
})
