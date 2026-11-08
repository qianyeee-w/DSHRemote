// @ts-check
/**
 * 实时流 `/v1/stream`（protocol.md 第 6 节）：局域网 HTTPS 服务上的 WebSocket。
 *
 * 安全：
 * - 和其他接口一样只在 TLS 上提供（同一个 HTTPS 服务的 upgrade）。
 * - 握手时必须带有效的设备 token（Authorization: Bearer），否则 401，不升级。
 * - 带 Origin 头的握手一律拒绝：App 不发送 Origin，浏览器一定发送，以此挡住网页发起的连接。
 * - 设备被撤销时，立即关闭它的所有连接（close code 4401）。
 * - 限制每台设备的连接数、单条消息大小、每条连接订阅的会话数；客户端读得太慢（积压过多）时断开。
 */
import { WebSocketServer } from 'ws'
import { randomUUID } from 'node:crypto'
import { bearerToken, PROTOCOL_VERSION } from './routes.js'

export const STREAM_PATH = '/v1/stream'
/** 关闭码：token 被撤销 / DSH 暂时不可用 / 违反协议 */
export const CLOSE_REVOKED = 4401
export const CLOSE_TRY_AGAIN = 1013
const CLOSE_POLICY = 1008
const MAX_CONNECTIONS_PER_DEVICE = 4
const MAX_SUBSCRIPTIONS = 20
/** App 发来的单条消息上限 */
const MAX_INBOUND_BYTES = 4 * 1024
/** 发送缓冲区积压超过这个值就断开（客户端读得太慢或网络卡住） */
const MAX_BUFFERED_BYTES = 8 * 1024 * 1024
const PING_INTERVAL_MS = 20_000

/**
 * 外观层中实时流用到的部分（src/dsh/live.js 的 LiveHub）。
 * @typedef {Pick<import('../dsh/live.js').LiveHub, 'onEvent' | 'snapshot' | 'subscribe'>} StreamHub
 */

/**
 * @param {import('node:stream').Duplex} socket @param {number} status @param {string} code @param {string} message
 * @param {Record<string, string>} [headers]
 */
function rejectUpgrade(socket, status, code, message, headers = {}) {
  const body = JSON.stringify({ error: { code, message } })
  const reason = { 400: 'Bad Request', 401: 'Unauthorized', 403: 'Forbidden', 404: 'Not Found', 429: 'Too Many Requests' }[status] ?? 'Error'
  const lines = [
    `HTTP/1.1 ${status} ${reason}`,
    'Content-Type: application/json; charset=utf-8',
    `Content-Length: ${Buffer.byteLength(body)}`,
    'Cache-Control: no-store',
    'Connection: close',
    ...Object.entries(headers).map(([k, v]) => `${k}: ${v}`),
  ]
  socket.end(`${lines.join('\r\n')}\r\n\r\n${body}`)
}

/**
 * 把实时流挂到局域网 HTTPS 服务上。
 * @param {{
 *   server: import('node:https').Server,
 *   devices: import('../pairing/devices.js').DeviceRegistry,
 *   hub: StreamHub,
 *   audit?: (event: string, detail?: Record<string, string>) => void,
 *   pingIntervalMs?: number,
 * }} options
 * @returns {{ close: () => void, connections: () => number }}
 */
export function attachStream({ server, devices, hub, audit, pingIntervalMs = PING_INTERVAL_MS }) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_INBOUND_BYTES, perMessageDeflate: false })
  /** @type {Map<string, Set<import('ws').WebSocket>>} deviceId → 连接 */
  const byDevice = new Map()

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:stream').Duplex} socket
   * @param {Buffer} head
   */
  const onUpgrade = (req, socket, head) => {
    socket.on('error', () => socket.destroy())
    let path
    try { path = new URL(req.url ?? '/', 'https://dshremote.invalid').pathname } catch { path = '' }
    if (path !== STREAM_PATH) { rejectUpgrade(socket, 404, 'NOT_FOUND', '接口不存在'); return }
    if (req.headers.origin !== undefined) { rejectUpgrade(socket, 403, 'BAD_REQUEST', '不接受来自网页的连接'); return }
    const device = devices.authenticate(bearerToken(req))
    if (!device) {
      rejectUpgrade(socket, 401, 'TOKEN_INVALID', '设备 token 无效或已被撤销，请重新配对', { 'WWW-Authenticate': 'Bearer' })
      return
    }
    if ((byDevice.get(device.deviceId)?.size ?? 0) >= MAX_CONNECTIONS_PER_DEVICE) {
      rejectUpgrade(socket, 429, 'BAD_REQUEST', '这台设备的连接数过多')
      return
    }
    wss.handleUpgrade(req, socket, head, (ws) => onConnection(ws, device.deviceId))
  }

  /** @param {import('ws').WebSocket} ws @param {string} deviceId */
  const onConnection = (ws, deviceId) => {
    let set = byDevice.get(deviceId)
    if (!set) { set = new Set(); byDevice.set(deviceId, set) }
    set.add(ws)
    audit?.('stream.connected', { deviceId })

    /** hello 发出之前到达的广播先排队，保证 App 先收到 hello */
    /** @type {object[] | undefined} */
    let pending = []
    /** @type {Map<string, () => void>} sessionId → 取消订阅 */
    const subscriptions = new Map()
    let alive = true

    /** @param {object} message */
    const send = (message) => {
      if (ws.readyState !== ws.OPEN) return
      if (ws.bufferedAmount > MAX_BUFFERED_BYTES) { ws.terminate(); return }
      ws.send(JSON.stringify(message))
    }
    /** @param {object} message */
    const sendOrQueue = (message) => { if (pending) pending.push(message); else send(message) }

    const offBroadcast = hub.onEvent((event) => {
      if (event.type === 'attention.resolved') {
        const { by, ...rest } = event
        sendOrQueue({ ...rest, by: by === deviceId ? 'this-device' : 'other' })
      } else {
        sendOrQueue(event)
      }
    })

    hub.snapshot(deviceId).then((state) => {
      send({ type: 'hello', eventId: randomUUID(), protocol: PROTOCOL_VERSION, sessions: state.sessions, attention: state.attention })
      const queued = pending ?? []
      pending = undefined
      for (const message of queued) send(message)
    }, () => {
      send({ type: 'error', eventId: randomUUID(), code: 'DSH_UNAVAILABLE', message: 'DSH 暂时不可用，请稍后重连' })
      ws.close(CLOSE_TRY_AGAIN, 'dsh unavailable')
    })

    ws.on('message', (data, isBinary) => {
      if (isBinary) { ws.close(CLOSE_POLICY, 'binary not supported'); return }
      /** @type {any} */
      let message
      try { message = JSON.parse(String(data)) } catch { message = undefined }
      const sessionId = message?.sessionId
      if ((message?.type !== 'subscribe' && message?.type !== 'unsubscribe') || typeof sessionId !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(sessionId)) {
        send({ type: 'error', eventId: randomUUID(), code: 'BAD_REQUEST', message: '无法识别的消息' })
        return
      }
      if (message.type === 'subscribe') {
        if (subscriptions.has(sessionId)) return
        if (subscriptions.size >= MAX_SUBSCRIPTIONS) {
          send({ type: 'error', eventId: randomUUID(), code: 'BAD_REQUEST', message: `同时订阅的会话不能超过 ${MAX_SUBSCRIPTIONS} 个` })
          return
        }
        subscriptions.set(sessionId, hub.subscribe(sessionId, (event) => send(event)))
      } else {
        subscriptions.get(sessionId)?.()
        subscriptions.delete(sessionId)
      }
    })

    // 心跳：上一次 ping 之后没有收到 pong，就认为连接已死
    ws.on('pong', () => { alive = true })
    const heartbeat = setInterval(() => {
      if (!alive) { ws.terminate(); return }
      alive = false
      ws.ping()
    }, pingIntervalMs)

    ws.on('close', () => {
      clearInterval(heartbeat)
      offBroadcast()
      for (const off of subscriptions.values()) off()
      subscriptions.clear()
      set?.delete(ws)
      if (set?.size === 0) byDevice.delete(deviceId)
    })
    ws.on('error', () => ws.terminate())
  }

  const offRevoke = devices.onRevoke((deviceId) => {
    for (const ws of byDevice.get(deviceId) ?? []) ws.close(CLOSE_REVOKED, 'token revoked')
  })
  server.on('upgrade', onUpgrade)

  return {
    close: () => {
      server.off('upgrade', onUpgrade)
      offRevoke()
      for (const set of byDevice.values()) for (const ws of set) ws.terminate()
      wss.close()
    },
    connections: () => [...byDevice.values()].reduce((n, s) => n + s.size, 0),
  }
}
