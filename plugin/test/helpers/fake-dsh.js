// @ts-check
/**
 * 模拟 DSH Web 服务，按 docs/research/dsh-session-api.md 实测的协议实现：
 * - GET /?token=<启动 token> → 303 + Set-Cookie
 * - POST /api/<endpoint>：要求 cookie；信封为 client-request / server-response；参数名不对时返回 gateway/arguments-invalid
 * - WebSocket /api/remote.mux：要求 cookie；open / item / end / error / cancel 帧；`$events` 先发 ready 帧
 * - restart()：换端口、换启动 token、旧 cookie 全部失效（模拟 Desktop 重启）
 *
 * 只用于单元测试。真实 DSH 的行为由 test/contract/ 的契约测试验证。
 */
import http from 'node:http'
import { randomUUID } from 'node:crypto'
import { WebSocketServer } from 'ws'

/**
 * @typedef {(args: Record<string, unknown>) => { ok: true, value: unknown } | { ok: false, error: { code: string, message: string } }} UnaryHandler
 */

export class FakeDsh {
  /** @type {http.Server | undefined} */
  server
  /** @type {WebSocketServer | undefined} */
  wss
  port = 0
  launchToken = ''
  /** @type {Set<string>} */
  validCookies = new Set()
  logins = 0
  /** @type {{ endpoint: string, args: Record<string, unknown> }[]} */
  calls = []
  /** @type {Map<string, UnaryHandler>} */
  handlers = new Map()
  /** 一共打开过多少条 $events 流 */
  eventsOpens = 0
  /** @type {Set<{ socket: import('ws').WebSocket, eventsStreamId: string | undefined, clientId: string }>} */
  clients = new Set()
  /** 是否发送心跳 Ping */
  ping = true

  constructor() {
    this.handlers.set('session/list', (args) => ('_request' in args
      ? { ok: true, value: { items: [] } }
      : { ok: false, error: { code: 'gateway/arguments-invalid', message: 'missing "_request"' } }))
    this.handlers.set('$events/result', () => ({ ok: true, value: undefined }))
  }

  async start() {
    this.launchToken = randomUUID()
    this.validCookies = new Set()
    const server = http.createServer((req, res) => this.#onRequest(req, res))
    const wss = new WebSocketServer({ noServer: true })
    server.on('upgrade', (req, socket, head) => {
      const cookie = String(req.headers.cookie ?? '').split(';').map((c) => c.trim()).find((c) => c.startsWith('dsh='))
      if (req.url !== '/api/remote.mux' || !cookie || !this.validCookies.has(cookie)) {
        socket.end('HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n')
        return
      }
      wss.handleUpgrade(req, socket, head, (ws) => this.#onSocket(ws))
    })
    await new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(undefined)))
    const address = server.address()
    this.port = typeof address === 'object' && address ? address.port : 0
    this.server = server
    this.wss = wss
    return this
  }

  /** 模拟 DSH 重启：关闭所有连接，换端口和启动 token，旧 cookie 失效。 */
  async restart() {
    await this.stop()
    await this.start()
  }

  async stop() {
    for (const client of this.clients) client.socket.terminate()
    this.clients.clear()
    this.wss?.close()
    if (this.server) {
      this.server.closeAllConnections()
      await new Promise((resolve) => this.server?.close(() => resolve(undefined)))
    }
    this.server = undefined
  }

  /** ctx.connection.authenticatedUrl 的替身 */
  authenticatedUrl = (/** @type {string} */ base) => {
    const url = new URL(base)
    url.searchParams.set('token', this.launchToken)
    return url.toString()
  }

  /** 使所有 cookie 失效（但不换端口），模拟签名密钥变化 */
  invalidateCookies() {
    this.validCookies.clear()
  }

  /** @param {string} event @param {unknown[]} args */
  emit(event, args) {
    for (const c of this.clients) this.#sendEvent(c, { type: 'emit', event, args })
  }

  /** @param {string} event @param {Record<string, unknown>} request @returns {string} eventId */
  waterfall(event, request, agentId = 'session-1') {
    const eventId = randomUUID()
    for (const c of this.clients) this.#sendEvent(c, { type: 'waterfall', event, eventId, agentId, request })
    return eventId
  }

  /** @param {string} eventId */
  cancelEvent(eventId) {
    for (const c of this.clients) this.#sendEvent(c, { type: 'cancel', eventId })
  }

  /** @param {{ socket: import('ws').WebSocket, eventsStreamId: string | undefined }} client @param {unknown} value */
  #sendEvent(client, value) {
    if (client.eventsStreamId) client.socket.send(JSON.stringify({ type: 'item', streamId: client.eventsStreamId, value }))
  }

  /** @param {import('ws').WebSocket} socket */
  #onSocket(socket) {
    const client = { socket, clientId: randomUUID(), /** @type {string | undefined} */ eventsStreamId: undefined }
    this.clients.add(client)
    const pinger = setInterval(() => { if (this.ping) socket.ping() }, 200)
    socket.on('close', () => { clearInterval(pinger); this.clients.delete(client) })
    socket.on('message', (data) => {
      /** @type {any} */
      const frame = JSON.parse(String(data))
      if (frame.type === 'open' && frame.endpoint === '$events') {
        this.eventsOpens++
        client.eventsStreamId = frame.streamId
        socket.send(JSON.stringify({ type: 'item', streamId: frame.streamId, value: { type: 'ready', clientId: client.clientId, host: { home: '/home/test' } } }))
      } else if (frame.type === 'open' && frame.endpoint === 'test/echo') {
        for (const value of /** @type {unknown[]} */ (frame.payload?.args?.items ?? [])) {
          socket.send(JSON.stringify({ type: 'item', streamId: frame.streamId, value }))
        }
        socket.send(JSON.stringify({ type: 'end', streamId: frame.streamId }))
      } else if (frame.type === 'open' && frame.endpoint === 'test/fail') {
        socket.send(JSON.stringify({ type: 'error', streamId: frame.streamId, error: { code: 'gateway/input-invalid', message: 'bad' } }))
      }
      // test/hold：只打开不结束，用于测试断线
    })
  }

  /** @param {http.IncomingMessage} req @param {http.ServerResponse} res */
  #onRequest(req, res) {
    const url = new URL(req.url ?? '/', 'http://x')
    if (req.method === 'GET' && url.pathname === '/') {
      if (url.searchParams.get('token') !== this.launchToken) { res.writeHead(401); res.end(); return }
      const cookie = `dsh=${randomUUID()}`
      this.validCookies.add(cookie)
      this.logins++
      res.writeHead(303, { location: './', 'set-cookie': `${cookie}; Path=/; HttpOnly; SameSite=Strict` })
      res.end()
      return
    }
    if (req.method === 'POST' && url.pathname.startsWith('/api/')) {
      const cookie = String(req.headers.cookie ?? '')
      if (!this.validCookies.has(cookie)) { req.resume(); res.writeHead(401); res.end(); return }
      let body = ''
      req.on('data', (c) => { body += c })
      req.on('end', () => {
        const envelope = JSON.parse(body)
        const endpoint = url.pathname.slice('/api/'.length)
        const args = envelope.payload?.args ?? {}
        this.calls.push({ endpoint, args })
        const handler = this.handlers.get(endpoint)
        const result = handler ? handler(args) : { ok: false, error: { code: 'gateway/not-found', message: endpoint } }
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ type: 'server-response', rpcId: envelope.rpcId, result }))
      })
      return
    }
    res.writeHead(404)
    res.end()
  }
}
