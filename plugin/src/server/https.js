// @ts-check
/**
 * 局域网 HTTPS 服务。
 *
 * 安全红线：局域网端口只提供 TLS（TLS 1.2 及以上）。收到明文 HTTP 时，TLS 握手失败，
 * 连接直接关闭，不返回任何明文内容。
 */
import https from 'node:https'

/** 单个请求头总大小上限，防止超大请求头消耗内存。 */
const MAX_HEADER_SIZE = 16 * 1024

/**
 * @typedef {object} LanServer
 * @property {import('node:https').Server} server
 * @property {() => number} port 实际监听的端口（测试中用 0 让系统分配）
 * @property {() => Promise<void>} close
 */

/**
 * @param {{
 *   identity: { keyPem: string, certPem: string },
 *   host: string,
 *   port: number,
 *   handler: (req: import('node:http').IncomingMessage, res: import('node:http').ServerResponse) => void,
 * }} options
 * @returns {Promise<LanServer>}
 */
export async function startLanServer({ identity, host, port, handler }) {
  const server = https.createServer({
    key: identity.keyPem,
    cert: identity.certPem,
    minVersion: 'TLSv1.2',
    maxHeaderSize: MAX_HEADER_SIZE,
    requestTimeout: 30_000,
    headersTimeout: 15_000,
  }, handler)
  // 握手失败（包括明文 HTTP、错误的协议版本）只关闭这条连接，不影响服务
  server.on('tlsClientError', (_error, socket) => { socket.destroy() })
  server.on('clientError', (_error, socket) => { socket.destroy() })

  /** @type {Set<import('node:stream').Duplex>} */
  const sockets = new Set()
  server.on('connection', (socket) => {
    sockets.add(socket)
    socket.on('close', () => sockets.delete(socket))
  })

  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, host, () => {
      server.off('error', reject)
      resolve(undefined)
    })
  })

  return {
    server,
    port: () => {
      const address = server.address()
      return typeof address === 'object' && address !== null ? address.port : port
    },
    close: () => new Promise((resolve) => {
      server.close(() => resolve())
      for (const socket of sockets) socket.destroy()
    }),
  }
}
