// @ts-check
/**
 * 插件的 TLS 身份：私钥 + 自签名证书，保存在状态目录里。
 *
 * - 第一次运行时生成私钥和证书。
 * - 证书即将到期、与私钥不匹配或损坏时，**用同一把私钥**重新签发：指纹不变，手机不需要重新配对。
 * - 只有私钥不存在（例如用户手动删除）时才生成新私钥：指纹改变，所有手机都需要重新配对。
 */
import { hostname } from 'node:os'
import { generatePrivateKeyPem, issueSelfSignedCertificate, renewalReason, spkiFingerprint } from './certificate.js'

export const KEY_FILE = 'tls-key.pem'
export const CERT_FILE = 'tls-cert.pem'

/**
 * @typedef {object} TlsIdentity
 * @property {string} keyPem 私钥（PEM）。不得写入日志
 * @property {string} certPem 证书（PEM）
 * @property {string} fingerprint 公钥指纹（SPKI SHA-256，base64url）
 * @property {'loaded' | 'created' | 'renewed'} status 本次启动时的处理结果
 * @property {string} [renewReason] status 为 renewed 时的原因
 */

/**
 * @param {import('../storage.js').Storage} storage
 * @param {{ now?: Date, commonName?: string }} [options]
 * @returns {Promise<TlsIdentity>}
 */
export async function loadOrCreateIdentity(storage, options = {}) {
  const now = options.now ?? new Date()
  const commonName = options.commonName ?? `DSHRemote ${hostname()}`.slice(0, 64)
  let keyPem = await storage.readText(KEY_FILE)
  let certPem = await storage.readText(CERT_FILE)
  /** @type {TlsIdentity['status']} */
  let status = 'loaded'
  if (keyPem === undefined) {
    // 私钥要先落盘，再签发证书：即使签发前崩溃，下次启动也会沿用这把私钥
    keyPem = generatePrivateKeyPem()
    await storage.writeText(KEY_FILE, keyPem)
    certPem = undefined
    status = 'created'
  }

  const reason = certPem === undefined ? undefined : renewalReason(certPem, keyPem, now)
  if (certPem === undefined || reason !== undefined) {
    certPem = issueSelfSignedCertificate(keyPem, { commonName, now })
    await storage.writeText(CERT_FILE, certPem)
    if (status === 'loaded') status = 'renewed'
  }

  return {
    keyPem,
    certPem,
    fingerprint: spkiFingerprint(certPem),
    status,
    ...(status === 'renewed' && reason !== undefined ? { renewReason: reason } : {}),
  }
}
