// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import * as der from '../../src/tls/der.js'

const hex = (/** @type {Buffer} */ b) => b.toString('hex')

test('长度：短格式与长格式', () => {
  assert.equal(hex(der.encodeLength(0)), '00')
  assert.equal(hex(der.encodeLength(127)), '7f')
  assert.equal(hex(der.encodeLength(128)), '8180')
  assert.equal(hex(der.encodeLength(255)), '81ff')
  assert.equal(hex(der.encodeLength(256)), '820100')
  assert.equal(hex(der.encodeLength(65535)), '82ffff')
  assert.throws(() => der.encodeLength(-1), RangeError)
  assert.throws(() => der.encodeLength(1.5), RangeError)
})

test('OID：已知向量', () => {
  assert.equal(hex(der.oid('1.2.840.10045.4.3.2')), '06082a8648ce3d040302') // ecdsa-with-SHA256
  assert.equal(hex(der.oid('2.5.4.3')), '0603550403') // commonName
  assert.equal(hex(der.oid('1.3.6.1.5.5.7.3.1')), '06082b06010505070301') // serverAuth
  assert.throws(() => der.oid('3.1'), RangeError)
  assert.throws(() => der.oid('1.40'), RangeError)
  assert.throws(() => der.oid('1.2.x'), RangeError)
})

test('整数：去掉多余前导 0，最高位为 1 时补 0', () => {
  assert.equal(hex(der.smallInteger(0)), '020100')
  assert.equal(hex(der.smallInteger(2)), '020102')
  assert.equal(hex(der.smallInteger(127)), '02017f')
  assert.equal(hex(der.smallInteger(128)), '02020080')
  assert.equal(hex(der.smallInteger(256)), '02020100')
  assert.equal(hex(der.unsignedInteger(Buffer.from([0, 0, 0x7f]))), '02017f')
  assert.equal(hex(der.unsignedInteger(Buffer.from([0xff]))), '020200ff')
  assert.equal(hex(der.unsignedInteger(Buffer.alloc(0))), '020100')
})

test('时间：2050 年前用 UTCTime，之后用 GeneralizedTime', () => {
  assert.equal(der.time(new Date('2026-11-08T05:06:07Z')).toString('latin1'), '\x17\x0d261108050607Z')
  assert.equal(der.time(new Date('2049-12-31T23:59:59Z'))[0], 0x17)
  assert.equal(der.time(new Date('2050-01-01T00:00:00Z')).toString('latin1'), '\x18\x0f20500101000000Z')
  assert.throws(() => der.time(new Date('invalid')), RangeError)
})

test('BIT STRING、BOOLEAN、显式标签、SEQUENCE', () => {
  assert.equal(hex(der.bitString(Buffer.from([0xab]))), '030200ab')
  assert.equal(hex(der.booleanTrue()), '0101ff')
  assert.equal(hex(der.explicit(0, der.smallInteger(2))), 'a003020102')
  assert.equal(hex(der.sequence()), '3000')
  assert.equal(hex(der.sequence(der.nullValue(), der.nullValue())), '300405000500')
  assert.throws(() => der.explicit(31, Buffer.alloc(0)), RangeError)
})

test('长内容使用长格式长度', () => {
  const value = der.octetString(Buffer.alloc(300, 1))
  assert.equal(hex(value.subarray(0, 4)), '0482012c')
  assert.equal(value.length, 304)
})
