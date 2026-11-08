// @ts-check
/**
 * 按公钥指纹固定证书的 HTTPS 客户端，模拟 Android App 的校验方式：
 * 不使用系统证书链，TLS 握手完成后先比对服务器公钥指纹，**不一致就断开，不发送任何请求数据**。
 */
import tls from 'node:tls'
import http from 'node:http'
import { createHash } from 'node:crypto'

export class PinMismatchError extends Error {
  /** @param {string} actual */
  constructor(actual) {
    super('服务器证书指纹不匹配')
    this.name = 'PinMismatchError'
    this.actual = actual
  }
}

/** @param {import('node:tls').TLSSocket} socket */
function peerFingerprint(socket) {
  const cert = socket.getPeerX509Certificate()
  if (!cert) throw new Error('服务器没有提供证书')
  return createHash('sha256').update(cert.publicKey.export({ type: 'spki', format: 'der' })).digest('base64url')
}

/**
 * 建立一条已经通过指纹校验的 TLS 连接。
 * @param {{ host: string, port: number, fingerprint: string, minVersion?: import('node:tls').SecureVersion, maxVersion?: import('node:tls').SecureVersion }} options
 * @returns {Promise<import('node:tls').TLSSocket>}
 */
export function connectPinned({ host, port, fingerprint, minVersion, maxVersion }) {
  return new Promise((resolve, reject) => {
    const socket = tls.connect({
      host,
      port,
      // 不使用证书链校验；改为下面的指纹校验（与 App 的做法一致）
      rejectUnauthorized: false,
      ...(minVersion ? { minVersion } : {}),
      ...(maxVersion ? { maxVersion } : {}),
    })
    socket.once('error', reject)
    socket.once('secureConnect', () => {
      let actual
      try { actual = peerFingerprint(socket) } catch (error) { socket.destroy(); reject(error); return }
      if (actual !== fingerprint) {
        socket.destroy()
        reject(new PinMismatchError(actual))
        return
      }
      socket.off('error', reject)
      resolve(socket)
    })
  })
}

/**
 * 通过指纹固定的连接发出一个 HTTP 请求。
 * @param {{ host: string, port: number, fingerprint: string, method?: string, path: string, headers?: Record<string, string>, body?: string }} options
 * @returns {Promise<{ status: number, headers: import('node:http').IncomingHttpHeaders, body: string, json: () => any }>}
 */
export async function pinnedRequest(options) {
  const socket = await connectPinned(options)
  return new Promise((resolve, reject) => {
    const req = http.request({
      method: options.method ?? 'GET',
      path: options.path,
      headers: { host: `${options.host}:${options.port}`, ...options.headers },
      createConnection: () => socket,
    }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (chunk) => { body += chunk })
      res.on('end', () => {
        socket.destroy()
        resolve({ status: res.statusCode ?? 0, headers: res.headers, body, json: () => JSON.parse(body) })
      })
    })
    req.on('error', (error) => { socket.destroy(); reject(error) })
    if (options.body !== undefined) req.write(options.body)
    req.end()
  })
}
