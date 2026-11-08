// @ts-check
/**
 * DSH 连接层：插件访问 DSH 的**唯一入口**。
 *
 * 协议细节见 docs/research/dsh-session-api.md 第 2、3 节：
 * - 登录：用 `authenticatedUrl()` 拿到带启动 token 的地址，GET 一次换取 owner cookie（ADR 0008）。
 * - 一元调用：POST /api/<endpoint>，信封 { type: 'client-request', rpcId, method, payload: { args } }。
 * - 流：一条 WebSocket（/api/remote.mux）承载多条逻辑流（open / item / end / error / cancel）。
 * - 应用事件：mux 上的 `$events` 流；waterfall 事件（审批、提问）通过一元调用 `$events/result` 回答。
 *
 * 可靠性：
 * - DSH 的端口每次启动都可能变化，每次请求都实时读取。端口变化或 cookie 失效（401）时自动重新登录。
 * - mux 断开后按指数退避重连（0.5 秒到 10 秒，带抖动），`$events` 自动重新订阅；
 *   其他流会收到 carrier-lost 错误，由上层决定是否重新打开。
 *
 * 预留设计（docs/roadmap.md 第 2 条）：每次调用都必须说明是替哪个设备发起的（deviceId），
 * 以后改为非 owner 的受限身份时，只需要修改这里。
 */
import http from 'node:http'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'

const UNARY_TIMEOUT_MS = 30_000
const LOGIN_TIMEOUT_MS = 10_000
const MUX_OPEN_TIMEOUT_MS = 10_000
/** DSH 每 2 秒发一次 Ping；超过这个时间没有任何活动就认为连接已死 */
const MUX_IDLE_TIMEOUT_MS = 10_000
const BACKOFF_MIN_MS = 500
const BACKOFF_MAX_MS = 10_000
const MAX_RESPONSE_BYTES = 32 * 1024 * 1024

/** 代表插件自身发起的调用（例如订阅事件），不对应任何手机。 */
export const PLUGIN_ACTOR = 'plugin'

/** DSH 当前不可用：还没启动完成、端口不可达、登录失败等。上层应返回 503 DSH_UNAVAILABLE。 */
export class DshUnavailableError extends Error {
  /** @param {string} message @param {unknown} [cause] */
  constructor(message, cause) {
    super(message, cause === undefined ? undefined : { cause })
    this.name = 'DshUnavailableError'
  }
}

/**
 * @typedef {{ ok: true, value: unknown } | { ok: false, error: { code: string, message: string, details?: unknown } }} RemoteResult
 */

/**
 * @typedef {object} StreamHandlers
 * @property {(value: unknown) => void} onItem
 * @property {() => void} [onEnd]
 * @property {(error: { code: string, message: string }) => void} [onError] 包括 mux 断开（code 为 carrier-lost）
 */

/**
 * @typedef {object} StreamHandle
 * @property {string} streamId
 * @property {(value: unknown) => void} send 发送上行数据
 * @property {() => void} cancel
 */

/**
 * @typedef {{ type: 'emit', event: string, args: unknown[] }
 *   | { type: 'waterfall', event: string, eventId: string, agentId: string, request: Record<string, unknown> }
 *   | { type: 'cancel', eventId: string }} DshAppEvent
 */

/**
 * @typedef {object} ConnectionStatus
 * @property {'connecting' | 'ready' | 'unavailable' | 'closed'} state
 * @property {string | undefined} lastError 最近一次失败的原因（不含任何秘密）
 */

/**
 * @typedef {object} DshConnectionOptions
 * @property {() => number | undefined} getPort 实时读取 DSH 的端口（ctx.webServer.port）
 * @property {(baseUrl: string) => string} authenticatedUrl ctx.connection.authenticatedUrl
 * @property {(level: 'info' | 'warn' | 'error', message: string) => void} [log]
 * @property {() => number} [random] 测试时注入
 * @property {{ idleTimeoutMs?: number, backoffMinMs?: number, backoffMaxMs?: number }} [timing] 测试时注入
 */

/** @param {string} actor */
function assertActor(actor) {
  if (actor !== PLUGIN_ACTOR && !/^d_[A-Za-z0-9_-]{12}$/.test(actor)) throw new TypeError(`非法的调用方：${actor}`)
}

export class DshConnection {
  #options
  /** @type {{ port: number, cookie: string } | undefined} */
  #session
  /** @type {Promise<{ port: number, cookie: string }> | undefined} */
  #loggingIn
  /** @type {WebSocket | undefined} */
  #socket
  /** @type {Promise<WebSocket> | undefined} */
  #socketOpening
  /** @type {Map<string, StreamHandlers>} */
  #streams = new Map()
  /** @type {Set<(event: DshAppEvent) => void>} */
  #eventListeners = new Set()
  /** @type {Set<(status: ConnectionStatus) => void>} */
  #statusListeners = new Set()
  /** @type {string | undefined} 当前 $events 流的 clientId，回答 waterfall 时需要 */
  #clientId
  /** @type {StreamHandle | undefined} */
  #eventsStream
  #backoff
  #idleTimeoutMs
  #backoffMinMs
  #backoffMaxMs
  /** @type {NodeJS.Timeout | undefined} */
  #reconnectTimer
  /** @type {NodeJS.Timeout | undefined} */
  #idleTimer
  #closed = false
  /** @type {ConnectionStatus} */
  #status = { state: 'connecting', lastError: undefined }

  /** @param {DshConnectionOptions} options */
  constructor(options) {
    this.#options = options
    this.#idleTimeoutMs = options.timing?.idleTimeoutMs ?? MUX_IDLE_TIMEOUT_MS
    this.#backoffMinMs = options.timing?.backoffMinMs ?? BACKOFF_MIN_MS
    this.#backoffMaxMs = options.timing?.backoffMaxMs ?? BACKOFF_MAX_MS
    this.#backoff = this.#backoffMinMs
  }

  /** @returns {ConnectionStatus} */
  status() {
    return { ...this.#status }
  }

  /** @param {(status: ConnectionStatus) => void} listener @returns {() => void} 取消监听 */
  onStatus(listener) {
    this.#statusListeners.add(listener)
    return () => this.#statusListeners.delete(listener)
  }

  /** @param {ConnectionStatus['state']} state @param {string} [lastError] */
  #setStatus(state, lastError) {
    if (this.#status.state === state && this.#status.lastError === lastError) return
    this.#status = { state, lastError }
    for (const listener of this.#statusListeners) {
      try { listener(this.status()) } catch { /* 监听者的错误不影响连接 */ }
    }
  }

  #port() {
    const port = this.#options.getPort()
    if (typeof port !== 'number' || !Number.isInteger(port) || port <= 0) throw new DshUnavailableError('DSH 还没有开始监听')
    return port
  }

  // ---------------------------------------------------------------- 登录

  /**
   * 拿到当前端口对应的有效 cookie；端口变化时重新登录。
   * @param {{ force?: boolean }} [options]
   */
  async #ensureSession(options = {}) {
    const port = this.#port()
    if (!options.force && this.#session?.port === port) return this.#session
    if (!this.#loggingIn) {
      this.#loggingIn = this.#login(port).finally(() => { this.#loggingIn = undefined })
    }
    return this.#loggingIn
  }

  /** @param {number} port */
  async #login(port) {
    let target
    try {
      target = new URL(this.#options.authenticatedUrl(`http://127.0.0.1:${port}/`))
    } catch (error) {
      throw new DshUnavailableError('无法生成 DSH 登录地址', error)
    }
    const response = await new Promise((resolve, reject) => {
      const req = http.request({
        host: '127.0.0.1',
        port,
        path: `${target.pathname}${target.search}`,
        method: 'GET',
        headers: { host: `127.0.0.1:${port}` },
        timeout: LOGIN_TIMEOUT_MS,
      }, (res) => { res.resume(); resolve(res) })
      req.on('timeout', () => req.destroy(new Error('登录超时')))
      req.on('error', reject)
      req.end()
    }).catch((error) => { throw new DshUnavailableError('无法连接 DSH', error) })

    const setCookie = /** @type {import('node:http').IncomingMessage} */ (response).headers['set-cookie']?.[0]
    const cookie = setCookie?.split(';', 1)[0]
    if (!cookie || !cookie.includes('=')) {
      throw new DshUnavailableError(`DSH 登录失败（HTTP ${/** @type {import('node:http').IncomingMessage} */ (response).statusCode}）`)
    }
    this.#session = { port, cookie }
    return this.#session
  }

  // ---------------------------------------------------------------- 一元调用

  /**
   * 调用 DSH 的一元接口。
   *
   * 注意：args 的字段名就是 DSH Host 方法的参数名（例如 session/list 要传 { _request: {} }），
   * 见 docs/research/dsh-session-api.md 3.1。
   * @param {string} endpoint 例如 'session/list'
   * @param {Record<string, unknown>} args
   * @param {{ actor: string, signal?: AbortSignal }} context actor：替哪个设备调用（deviceId），插件自身用 PLUGIN_ACTOR
   * @returns {Promise<RemoteResult>} DSH 返回的业务结果；连接问题抛出 DshUnavailableError
   */
  async call(endpoint, args, context) {
    assertActor(context.actor)
    // 每一段都以字母开头（第一段允许 $，例如 $events/result），不允许 ..、空段和查询参数
    if (!/^\$?[A-Za-z][A-Za-z0-9_-]*(\/[A-Za-z][A-Za-z0-9_.-]*)+$/.test(endpoint) || endpoint.includes('..')) {
      throw new TypeError(`非法的接口名：${endpoint}`)
    }
    let session = await this.#ensureSession()
    let res = await this.#post(session, endpoint, args, context.signal)
    if (res.status === 401) {
      // cookie 失效（例如 DSH 重启后签名密钥变化），重新登录一次
      session = await this.#ensureSession({ force: true })
      res = await this.#post(session, endpoint, args, context.signal)
    }
    if (res.status !== 200) throw new DshUnavailableError(`DSH 返回 HTTP ${res.status}`)
    /** @type {any} */
    let envelope
    try { envelope = JSON.parse(res.body) } catch { throw new DshUnavailableError('DSH 返回的不是 JSON') }
    const result = envelope?.result
    if (envelope?.type !== 'server-response' || typeof result !== 'object' || result === null || typeof result.ok !== 'boolean') {
      throw new DshUnavailableError('DSH 返回的格式不符合预期')
    }
    return result.ok
      ? { ok: true, value: result.value }
      : { ok: false, error: { code: String(result.error?.code ?? 'unknown'), message: String(result.error?.message ?? ''), details: result.error?.details } }
  }

  /**
   * @param {{ port: number, cookie: string }} session
   * @param {string} endpoint
   * @param {Record<string, unknown>} args
   * @param {AbortSignal | undefined} signal
   * @returns {Promise<{ status: number, body: string }>}
   */
  #post(session, endpoint, args, signal) {
    const body = JSON.stringify({ type: 'client-request', rpcId: randomUUID(), method: endpoint, payload: { args } })
    return new Promise((resolve, reject) => {
      const req = http.request({
        host: '127.0.0.1',
        port: session.port,
        path: `/api/${endpoint}`,
        method: 'POST',
        headers: {
          host: `127.0.0.1:${session.port}`,
          cookie: session.cookie,
          'content-type': 'application/json',
          'content-length': Buffer.byteLength(body),
        },
        timeout: UNARY_TIMEOUT_MS,
        ...(signal ? { signal } : {}),
      }, (res) => {
        /** @type {Buffer[]} */
        const chunks = []
        let size = 0
        res.on('data', (chunk) => {
          size += chunk.length
          if (size > MAX_RESPONSE_BYTES) { req.destroy(new Error('DSH 响应过大')); return }
          chunks.push(chunk)
        })
        res.on('end', () => resolve({ status: res.statusCode ?? 0, body: Buffer.concat(chunks).toString('utf8') }))
        res.on('error', reject)
      })
      req.on('timeout', () => req.destroy(new Error('DSH 调用超时')))
      req.on('error', (error) => reject(new DshUnavailableError(`调用 DSH 失败：${error.message}`, error)))
      req.end(body)
    })
  }

  // ---------------------------------------------------------------- mux

  /** @returns {Promise<WebSocket>} */
  async #ensureSocket() {
    if (this.#closed) throw new DshUnavailableError('连接已关闭')
    if (this.#socket && this.#socket.readyState === WebSocket.OPEN) return this.#socket
    if (!this.#socketOpening) {
      this.#socketOpening = this.#openSocket().finally(() => { this.#socketOpening = undefined })
    }
    return this.#socketOpening
  }

  async #openSocket() {
    let session = await this.#ensureSession()
    try {
      return await this.#connectSocket(session)
    } catch (error) {
      if (/** @type {{ status?: number }} */ (error).status === 401) {
        session = await this.#ensureSession({ force: true })
        return this.#connectSocket(session)
      }
      throw error
    }
  }

  /** @param {{ port: number, cookie: string }} session @returns {Promise<WebSocket>} */
  #connectSocket(session) {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(`ws://127.0.0.1:${session.port}/api/remote.mux`, {
        headers: { host: `127.0.0.1:${session.port}`, cookie: session.cookie },
        handshakeTimeout: MUX_OPEN_TIMEOUT_MS,
        perMessageDeflate: true,
        maxPayload: MAX_RESPONSE_BYTES,
      })
      socket.once('unexpected-response', (_req, res) => {
        res.resume()
        const error = Object.assign(new DshUnavailableError(`mux 握手失败（HTTP ${res.statusCode}）`), { status: res.statusCode })
        socket.terminate()
        reject(error)
      })
      socket.once('error', (error) => reject(new DshUnavailableError(`mux 连接失败：${error.message}`, error)))
      socket.once('open', () => {
        socket.removeAllListeners('error')
        this.#attachSocket(socket)
        resolve(socket)
      })
    })
  }

  /** @param {WebSocket} socket */
  #attachSocket(socket) {
    this.#socket = socket
    this.#backoff = this.#backoffMinMs
    const touch = () => {
      clearTimeout(this.#idleTimer)
      this.#idleTimer = setTimeout(() => socket.terminate(), this.#idleTimeoutMs)
      this.#idleTimer.unref?.()
    }
    touch()
    socket.on('ping', touch)
    socket.on('pong', touch)
    socket.on('message', (data) => {
      touch()
      /** @type {any} */
      let frame
      try { frame = JSON.parse(String(data)) } catch { return }
      if (typeof frame?.streamId !== 'string') return
      const handlers = this.#streams.get(frame.streamId)
      if (!handlers) return
      if (frame.type === 'item') {
        handlers.onItem(frame.value)
      } else if (frame.type === 'end') {
        this.#streams.delete(frame.streamId)
        handlers.onEnd?.()
      } else if (frame.type === 'error') {
        this.#streams.delete(frame.streamId)
        handlers.onError?.({ code: String(frame.error?.code ?? 'unknown'), message: String(frame.error?.message ?? '') })
      }
    })
    socket.on('error', () => { /* 由 close 统一处理 */ })
    socket.on('close', () => {
      clearTimeout(this.#idleTimer)
      if (this.#socket === socket) this.#socket = undefined
      this.#clientId = undefined
      this.#eventsStream = undefined
      const orphaned = [...this.#streams.values()]
      this.#streams.clear()
      for (const handlers of orphaned) handlers.onError?.({ code: 'carrier-lost', message: 'mux 连接断开' })
      if (!this.#closed) {
        this.#setStatus('connecting', 'mux 连接断开，正在重连')
        this.#scheduleReconnect()
      }
    })
  }

  /**
   * 打开一条逻辑流。
   * @param {string} endpoint
   * @param {Record<string, unknown>} args
   * @param {StreamHandlers} handlers
   * @param {{ actor: string }} context
   * @returns {Promise<StreamHandle>}
   */
  async openStream(endpoint, args, handlers, context) {
    assertActor(context.actor)
    const socket = await this.#ensureSocket()
    const streamId = randomUUID()
    this.#streams.set(streamId, handlers)
    socket.send(JSON.stringify({ type: 'open', streamId, endpoint, payload: { args } }))
    return {
      streamId,
      send: (value) => {
        if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'item', streamId, value }))
      },
      cancel: () => {
        if (!this.#streams.delete(streamId)) return
        if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'cancel', streamId }))
      },
    }
  }

  // ---------------------------------------------------------------- $events

  /**
   * 订阅 DSH 的应用事件。第一次订阅时开始连接；断线后自动重连并重新订阅。
   * @param {(event: DshAppEvent) => void} listener
   * @returns {() => void} 取消订阅
   */
  onEvent(listener) {
    this.#eventListeners.add(listener)
    if (!this.#eventsStream && !this.#reconnectTimer) this.#connectEvents()
    return () => this.#eventListeners.delete(listener)
  }

  async #connectEvents() {
    if (this.#closed) return
    try {
      this.#eventsStream = await this.openStream('$events', {}, {
        onItem: (value) => this.#onEventItem(value),
        onEnd: () => this.#socket?.terminate(),
        onError: (error) => {
          if (error.code !== 'carrier-lost') this.#socket?.terminate()
        },
      }, { actor: PLUGIN_ACTOR })
    } catch (error) {
      this.#setStatus('unavailable', error instanceof Error ? error.message : String(error))
      this.#scheduleReconnect()
    }
  }

  /** @param {unknown} value */
  #onEventItem(value) {
    const item = /** @type {any} */ (value)
    if (item?.type === 'ready' && typeof item.clientId === 'string') {
      this.#clientId = item.clientId
      this.#setStatus('ready')
      return
    }
    /** @type {DshAppEvent | undefined} */
    let event
    if (item?.type === 'emit' && typeof item.event === 'string' && Array.isArray(item.args)) {
      event = { type: 'emit', event: item.event, args: item.args }
    } else if (item?.type === 'waterfall' && typeof item.event === 'string' && typeof item.eventId === 'string') {
      event = { type: 'waterfall', event: item.event, eventId: item.eventId, agentId: String(item.agentId ?? ''), request: item.request ?? {} }
    } else if (item?.type === 'cancel' && typeof item.eventId === 'string') {
      event = { type: 'cancel', eventId: item.eventId }
    }
    if (!event) return
    for (const listener of this.#eventListeners) {
      try { listener(event) } catch { /* 单个监听者的错误不影响其他监听者 */ }
    }
  }

  /**
   * 回答一个 waterfall 事件（审批、提问）。
   * @param {string} eventId
   * @param {{ kind: 'result', value?: unknown } | { kind: 'next' }} outcome
   * @param {{ actor: string }} context
   * @returns {Promise<RemoteResult>}
   */
  async answerEvent(eventId, outcome, context) {
    const clientId = this.#clientId
    if (!clientId) throw new DshUnavailableError('事件流还没有就绪')
    return this.call('$events/result', { clientId, eventId, outcome }, context)
  }

  #scheduleReconnect() {
    if (this.#closed || this.#reconnectTimer || this.#eventListeners.size === 0) return
    const random = this.#options.random ?? Math.random
    const delay = Math.round(this.#backoff * (0.75 + random() * 0.5))
    this.#backoff = Math.min(this.#backoff * 2, this.#backoffMaxMs)
    this.#reconnectTimer = setTimeout(() => {
      this.#reconnectTimer = undefined
      this.#connectEvents()
    }, delay)
    this.#reconnectTimer.unref?.()
  }

  /** 关闭连接并停止重连。 */
  close() {
    this.#closed = true
    clearTimeout(this.#reconnectTimer)
    clearTimeout(this.#idleTimer)
    this.#eventListeners.clear()
    this.#socket?.terminate()
    this.#setStatus('closed')
  }
}
