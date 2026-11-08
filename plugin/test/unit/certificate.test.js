// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { X509Certificate, createPublicKey, createHash, generateKeyPairSync } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { writeFileSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  generatePrivateKeyPem, issueSelfSignedCertificate, spkiFingerprint, renewalReason,
  CERT_VALIDITY_DAYS, RENEW_BEFORE_DAYS,
} from '../../src/tls/certificate.js'

const DAY = 24 * 60 * 60 * 1000
const key = generatePrivateKeyPem()
const certPem = issueSelfSignedCertificate(key, { commonName: 'DSHRemote test' })
const cert = new X509Certificate(certPem)

test('证书能被 Node 解析，字段正确', () => {
  assert.equal(cert.subject, 'CN=DSHRemote test')
  assert.equal(cert.issuer, 'CN=DSHRemote test')
  assert.equal(cert.ca, false)
  assert.deepEqual(cert.keyUsage, ['1.3.6.1.5.5.7.3.1']) // extKeyUsage: serverAuth
  assert.equal(cert.publicKey.asymmetricKeyType, 'ec')
  const days = (new Date(cert.validTo).getTime() - new Date(cert.validFrom).getTime()) / DAY
  assert.equal(Math.round(days), CERT_VALIDITY_DAYS)
})

test('证书签名可以用它自己的公钥验证（自签名）', () => {
  assert.equal(cert.verify(cert.publicKey), true)
  // 叶子证书（CA:FALSE、没有 keyCertSign），所以 OpenSSL 不把它当作签发者；这是预期行为
  assert.equal(cert.subject, cert.issuer)
  const other = new X509Certificate(issueSelfSignedCertificate(generatePrivateKeyPem()))
  assert.equal(cert.verify(other.publicKey), false)
})

test('序列号是正数且每次不同', () => {
  const a = new X509Certificate(issueSelfSignedCertificate(key))
  const b = new X509Certificate(issueSelfSignedCertificate(key))
  assert.notEqual(a.serialNumber, b.serialNumber)
  assert.ok(Number.parseInt(a.serialNumber.slice(0, 2), 16) < 0x80)
})

test('openssl 也能解析并认可这张证书（有 openssl 时才运行）', (t) => {
  try { execFileSync('openssl', ['version'], { stdio: 'ignore' }) } catch { t.skip('没有 openssl'); return }
  const dir = mkdtempSync(join(tmpdir(), 'dshremote-cert-'))
  try {
    const file = join(dir, 'cert.pem')
    writeFileSync(file, certPem)
    const text = execFileSync('openssl', ['x509', '-in', file, '-noout', '-text'], { encoding: 'utf8' })
    assert.match(text, /Version: 3 \(0x2\)/)
    assert.match(text, /Signature Algorithm: ecdsa-with-SHA256/)
    assert.match(text, /CA:FALSE/)
    assert.match(text, /Digital Signature/)
    assert.match(text, /TLS Web Server Authentication/)
    const verify = execFileSync('openssl', ['verify', '-CAfile', file, '-purpose', 'sslserver', file], { encoding: 'utf8' })
    assert.match(verify, /: OK/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('指纹：SPKI DER 的 SHA-256（base64url），证书和私钥算出的结果一致', () => {
  const expected = createHash('sha256').update(createPublicKey(key).export({ type: 'spki', format: 'der' })).digest('base64url')
  assert.equal(spkiFingerprint(certPem), expected)
  assert.equal(spkiFingerprint(cert.publicKey), expected)
  assert.equal(spkiFingerprint(createPublicKey(key)), expected)
  assert.match(expected, /^[A-Za-z0-9_-]{43}$/)
})

test('同一把私钥重新签发的证书，指纹不变；换私钥后指纹改变', () => {
  assert.equal(spkiFingerprint(issueSelfSignedCertificate(key)), spkiFingerprint(certPem))
  assert.notEqual(spkiFingerprint(issueSelfSignedCertificate(generatePrivateKeyPem())), spkiFingerprint(certPem))
})

test('只接受 ECDSA P-256 私钥', () => {
  const rsa = /** @type {string} */ (generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({ type: 'pkcs8', format: 'pem' }))
  assert.throws(() => issueSelfSignedCertificate(rsa), /P-256/)
  const p384 = /** @type {string} */ (generateKeyPairSync('ec', { namedCurve: 'secp384r1' }).privateKey.export({ type: 'pkcs8', format: 'pem' }))
  assert.throws(() => issueSelfSignedCertificate(p384), /P-256/)
})

test('renewalReason：新证书不需要续期', () => {
  assert.equal(renewalReason(certPem, key), undefined)
})

test('renewalReason：过期、即将到期、尚未生效、不匹配、损坏都需要重新签发', () => {
  const longAgo = new Date(Date.now() - (CERT_VALIDITY_DAYS + 1) * DAY)
  assert.match(String(renewalReason(issueSelfSignedCertificate(key, { now: longAgo }), key)), /过期/)
  const nearExpiry = new Date(Date.now() - (CERT_VALIDITY_DAYS - RENEW_BEFORE_DAYS + 1) * DAY)
  assert.match(String(renewalReason(issueSelfSignedCertificate(key, { now: nearExpiry }), key)), /到期/)
  const future = new Date(Date.now() + 10 * DAY)
  assert.match(String(renewalReason(issueSelfSignedCertificate(key, { now: future }), key)), /尚未生效/)
  assert.match(String(renewalReason(certPem, generatePrivateKeyPem())), /不匹配/)
  assert.match(String(renewalReason('not a certificate', key)), /无法解析/)
})
