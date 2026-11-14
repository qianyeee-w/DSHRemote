// @ts-check
/**
 * 按公钥指纹固定证书的 HTTPS 客户端，模拟 Android App 的校验方式：
 * 不使用系统证书链，TLS 握手完成后先比对服务器公钥指纹，**不一致就断开，不发送任何请求数据**。
 */
import tls from 'node:tls'
import http from 'node:http'
import { createHash } from 'node:crypto'

const CONNECT_TIMEOUT_MS = 10_000

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
    // 被防火墙丢弃的连接不会报错，只会一直等待；超时后给出明确的错误
    socket.setTimeout(CONNECT_TIMEOUT_MS, () => socket.destroy(new Error(`连接 ${host}:${port} 超时（${CONNECT_TIMEOUT_MS / 1000} 秒）`)))
    socket.once('secureConnect', () => {
      socket.setTimeout(0)
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
 * @returns {Promise<{ status: number, headers: import('node:http').IncomingHttpHeaders, body: string, raw: Buffer, json: () => any }>}
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
      /** @type {Buffer[]} */
      const chunks = []
      res.on('data', (chunk) => { chunks.push(chunk) })
      res.on('end', () => {
        socket.destroy()
        const raw = Buffer.concat(chunks)
        const body = raw.toString('utf8')
        resolve({ status: res.statusCode ?? 0, headers: res.headers, body, raw, json: () => JSON.parse(body) })
      })
    })
    req.on('error', (error) => { socket.destroy(); reject(error) })
    if (options.body !== undefined) req.write(options.body)
    req.end()
  })
}

/**
 * 通过指纹固定的连接打开 WebSocket（模拟 App 连接 /v1/stream）。
 * 握手被拒绝时 reject，错误中带 HTTP 状态码（error.status）。
 * @param {{ host: string, port: number, fingerprint: string, path?: string, headers?: Record<string, string> }} options
 * @returns {Promise<import('ws').WebSocket>}
 */
export async function pinnedWebSocket({ host, port, fingerprint, path = '/v1/stream', headers = {} }) {
  const { default: WebSocket } = await import('ws')
  const socket = await connectPinned({ host, port, fingerprint })
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`wss://${host}:${port}${path}`, { headers, createConnection: () => socket, perMessageDeflate: false })
    ws.once('open', () => resolve(ws))
    ws.once('unexpected-response', (_req, res) => {
      let body = ''
      res.on('data', (c) => { body += c })
      res.on('end', () => reject(Object.assign(new Error(`握手被拒绝：${res.statusCode}`), { status: res.statusCode, headers: res.headers, body })))
    })
    ws.once('error', reject)
  })
}

/**
 * 收集 WebSocket 消息，提供「等到某条消息出现」。
 * @param {import('ws').WebSocket} ws
 */
export function collectMessages(ws) {
  /** @type {any[]} */
  const messages = []
  /** @type {{ code: number, reason: string } | undefined} */
  let closed
  ws.on('message', (data) => messages.push(JSON.parse(String(data))))
  ws.on('close', (code, reason) => { closed = { code, reason: String(reason) } })
  return {
    messages,
    closed: () => closed,
    /** @param {(m: any) => boolean} predicate @param {number} [timeoutMs] */
    async waitFor(predicate, timeoutMs = 10_000) {
      const end = Date.now() + timeoutMs
      while (Date.now() < end) {
        const found = messages.find(predicate)
        if (found) return found
        await new Promise((r) => setTimeout(r, 20))
      }
      throw new Error(`等待消息超时，已收到：${JSON.stringify(messages.map((m) => m.type))}`)
    },
    async waitClosed(timeoutMs = 10_000) {
      const end = Date.now() + timeoutMs
      while (Date.now() < end) {
        if (closed) return closed
        await new Promise((r) => setTimeout(r, 20))
      }
      throw new Error('等待连接关闭超时')
    },
  }
}
