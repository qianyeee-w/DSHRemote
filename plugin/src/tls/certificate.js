// @ts-check
/**
 * 生成 ECDSA P-256 自签名证书，并计算公钥指纹。
 *
 * App 只校验公钥指纹（SubjectPublicKeyInfo 的 SHA-256），不校验证书链、主机名和有效期以外的字段，
 * 所以证书只需要能让 TLS 握手成功即可。证书到期后用同一把私钥重新签发，指纹保持不变。
 */
import { generateKeyPairSync, createPublicKey, createPrivateKey, sign, createHash, randomBytes, X509Certificate } from 'node:crypto'
import * as der from './der.js'

const OID = Object.freeze({
  ecdsaWithSha256: '1.2.840.10045.4.3.2',
  commonName: '2.5.4.3',
  basicConstraints: '2.5.29.19',
  keyUsage: '2.5.29.15',
  extKeyUsage: '2.5.29.37',
  serverAuth: '1.3.6.1.5.5.7.3.1',
})

/** 证书有效期（天）。到期前 30 天内会自动用同一把私钥重新签发。 */
export const CERT_VALIDITY_DAYS = 3650
export const RENEW_BEFORE_DAYS = 30
const DAY_MS = 24 * 60 * 60 * 1000

/**
 * 计算公钥指纹：SPKI DER 的 SHA-256，base64url 编码，不加填充。
 * @param {import('node:crypto').KeyObject | string | Buffer} key 公钥、私钥（会取出对应公钥）或 PEM 证书
 * @returns {string}
 */
export function spkiFingerprint(key) {
  const publicKey = typeof key === 'string' || Buffer.isBuffer(key)
    ? new X509Certificate(key).publicKey
    : key.type === 'private' ? createPublicKey(key) : key
  const spki = publicKey.export({ type: 'spki', format: 'der' })
  return createHash('sha256').update(spki).digest('base64url')
}

/** 生成新的 P-256 私钥（PEM，PKCS#8）。 */
export function generatePrivateKeyPem() {
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  return /** @type {string} */ (privateKey.export({ type: 'pkcs8', format: 'pem' }))
}

/** 正数、非零开头的 128 位随机序列号。 */
function serialNumber() {
  const bytes = randomBytes(16)
  bytes[0] = (/** @type {number} */ (bytes[0]) & 0x7f) | 0x01
  return bytes
}

/**
 * 用给定私钥签发一张自签名证书。
 * @param {string} privateKeyPem
 * @param {{ commonName?: string, now?: Date, validityDays?: number }} [options]
 * @returns {string} PEM 格式的证书
 */
export function issueSelfSignedCertificate(privateKeyPem, options = {}) {
  const privateKey = createPrivateKey(privateKeyPem)
  if (privateKey.asymmetricKeyType !== 'ec' || privateKey.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
    throw new Error('只支持 ECDSA P-256 私钥')
  }
  const spki = createPublicKey(privateKey).export({ type: 'spki', format: 'der' })
  const now = options.now ?? new Date()
  const validityDays = options.validityDays ?? CERT_VALIDITY_DAYS
  // 往前留一小时，避免手机时钟略慢时证书被判为「尚未生效」
  const notBefore = new Date(Math.floor(now.getTime() / 1000) * 1000 - 60 * 60 * 1000)
  const notAfter = new Date(notBefore.getTime() + validityDays * DAY_MS)

  const name = der.sequence(der.set(der.sequence(der.oid(OID.commonName), der.utf8String(options.commonName ?? 'DSHRemote'))))
  const signatureAlgorithm = der.sequence(der.oid(OID.ecdsaWithSha256))
  const extensions = der.sequence(
    // basicConstraints：critical，CA:FALSE（空 SEQUENCE）
    der.sequence(der.oid(OID.basicConstraints), der.booleanTrue(), der.octetString(der.sequence())),
    // keyUsage：critical，digitalSignature（bit 0）
    der.sequence(der.oid(OID.keyUsage), der.booleanTrue(), der.octetString(der.tlv(0x03, Buffer.from([0x07, 0x80])))),
    // extKeyUsage：serverAuth
    der.sequence(der.oid(OID.extKeyUsage), der.octetString(der.sequence(der.oid(OID.serverAuth)))),
  )
  const tbsCertificate = der.sequence(
    der.explicit(0, der.smallInteger(2)), // v3
    der.unsignedInteger(serialNumber()),
    signatureAlgorithm,
    name, // issuer
    der.sequence(der.time(notBefore), der.time(notAfter)),
    name, // subject（自签名，与 issuer 相同）
    spki,
    der.explicit(3, extensions),
  )
  const signature = sign('sha256', tbsCertificate, { key: privateKey, dsaEncoding: 'der' })
  const certificate = der.sequence(tbsCertificate, signatureAlgorithm, der.bitString(signature))

  const base64 = certificate.toString('base64').replace(/.{1,64}/g, '$&\n')
  return `-----BEGIN CERTIFICATE-----\n${base64}-----END CERTIFICATE-----\n`
}

/**
 * 证书是否需要重新签发：解析失败、与私钥不匹配、已过期、尚未生效或即将到期。
 * @param {string} certPem
 * @param {string} privateKeyPem
 * @param {Date} [now]
 * @returns {string | undefined} 需要重新签发的原因；不需要时返回 undefined
 */
export function renewalReason(certPem, privateKeyPem, now = new Date()) {
  let cert
  try { cert = new X509Certificate(certPem) } catch { return '证书无法解析' }
  if (spkiFingerprint(cert.publicKey) !== spkiFingerprint(createPrivateKey(privateKeyPem))) return '证书与私钥不匹配'
  if (!cert.verify(cert.publicKey)) return '证书签名无效'
  const validFrom = new Date(cert.validFrom).getTime()
  const validTo = new Date(cert.validTo).getTime()
  if (now.getTime() < validFrom) return '证书尚未生效'
  if (now.getTime() + RENEW_BEFORE_DAYS * DAY_MS >= validTo) return '证书已过期或即将到期'
  return undefined
}
