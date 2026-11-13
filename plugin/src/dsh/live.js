// @ts-check
/**
 * 实时状态：把 DSH 的事件转换成 protocol.md 第 6 节的实时消息（不含传输，传输在 src/server/stream.js）。
 *
 * DSH 侧的事实（docs/research/dsh-session-api.md 5.8）：
 * - `api-session/status [sessionId, running]` 在 Agent 开始、停止运行时广播，用来判断「任务完成」。
 * - 标题变化没有广播；`api-session/removed` 在会话从内存中卸载时也会发出，并不代表删除。
 *   所以会话摘要的变化一律通过重新读取 session/list 再比较得到（有事件时防抖刷新，另有低频的定时刷新）。
 * - `session/follow` 先给出 snapshot，之后是会话日志的事件，以及 `assistant-stream` 帧
 *   （模型正在输出、还没写入日志的内容）。一次输出写入日志后（assistant/message），对应的 stream 才结束。
 */
import { convertPlan } from './plan.js'
import { randomUUID } from 'node:crypto'
import { PLUGIN_ACTOR } from './connection.js'
import { convertRecords, extractTurns, toolSummary, parseToolInput } from './transcript.js'

/** 会话从运行变为空闲后，持续多久才算「任务完成」 */
export const IDLE_DELAY_MS = 8_000
/** 插件启动后补算最近一轮结果的会话数（用户确认） */
export const DIGEST_BACKFILL = 20
/** 补算时同时读取的会话数 */
const DIGEST_CONCURRENCY = 2
/** 有事件时，刷新会话列表前等多久（合并连续的事件） */
const REFRESH_DEBOUNCE_MS = 300
/** 有客户端在线时，定时刷新会话列表的间隔（捕获没有广播的变化，例如标题、归档） */
const REFRESH_INTERVAL_MS = 30_000
/** 流式输出时，同一条消息最多每隔多久推送一次 */
const DRAFT_THROTTLE_MS = 120
/** 会话订阅断开后，重新打开 session/follow 的等待时间 */
const FEED_RETRY_MIN_MS = 500
const FEED_RETRY_MAX_MS = 10_000
/** 增量转换时保留的会话日志条数上限（从本轮开始算） */
const FEED_BUFFER_LIMIT = 3_000
/** 目标、任务清单变了以后多久读一次计划（毫秒） */
const PLAN_DEBOUNCE_MS = 150

/** @param {unknown} value @returns {value is Record<string, any>} */
const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value)

/**
 * @typedef {{ type: 'session.updated', eventId: string, session: import('./facade.js').SessionSummary }
 *   | { type: 'session.removed', eventId: string, sessionId: string }
 *   | { type: 'session.idle', eventId: string, sessionId: string, title: string | null, result?: string }
 *   | { type: 'attention.requested', eventId: string, attention: import('./attention.js').Attention }
 *   | { type: 'attention.resolved', eventId: string, attentionId: string, sessionId: string, by: string | null }} BroadcastEvent
 * attention.resolved 的 by 是回答它的 deviceId（或 null），传输层再换成 this-device / other。
 */

/**
 * @typedef {{ type: 'message.upsert', eventId: string, sessionId: string, rev: number, message: import('./transcript.js').Message & { draft?: true }, replaces?: string }
 *   | { type: 'message.removed', eventId: string, sessionId: string, messageId: string }
 *   | { type: 'turn.updated', eventId: string, sessionId: string, turn: import('./transcript.js').Turn }
 *   | { type: 'plan.updated', eventId: string, sessionId: string, plan: import('./plan.js').Plan }} MessageEvent
 */

// ---------------------------------------------------------------- 流式输出的草稿

/**
 * 正在输出、还没写入会话日志的一条模型回复。
 * @typedef {object} Draft
 * @property {string} attemptId
 * @property {number} turn
 * @property {number} step
 * @property {number} createdAt
 * @property {Map<number, { type: string, text: string, id?: string, name?: string, args?: string }>} blocks 按块序号
 */

/** @param {Draft} draft */
export const draftId = (draft) => `draft:${draft.attemptId}`

/**
 * 把一个 stream chunk 应用到草稿上。支持 session/follow 的实时 chunk，以及 snapshot 中压缩过的
 * `{ type: 'chunk', chunk }`、`{ type: 'text-chunks', index, texts }` 两种记录。
 * @param {Draft} draft @param {unknown} entry
 */
export function applyStreamEntry(draft, entry) {
  if (!isObject(entry)) return
  if (entry.type === 'text-chunks' && typeof entry.index === 'number' && Array.isArray(entry.texts)) {
    const block = draft.blocks.get(entry.index) ?? { type: 'text', text: '' }
    block.text += entry.texts.filter((t) => typeof t === 'string').join('')
    draft.blocks.set(entry.index, block)
    return
  }
  const chunk = entry.type === 'chunk' && isObject(entry.chunk) ? entry.chunk : entry
  if (typeof chunk.index !== 'number') return
  const index = chunk.index
  switch (chunk.type) {
    case 'block-start':
      draft.blocks.set(index, { type: typeof chunk.blockType === 'string' ? chunk.blockType : 'text', text: '' })
      break
    case 'text-delta':
    case 'reasoning-delta': {
      const block = draft.blocks.get(index) ?? { type: chunk.type === 'text-delta' ? 'text' : 'reasoning', text: '' }
      if (typeof chunk.text === 'string') block.text += chunk.text
      draft.blocks.set(index, block)
      break
    }
    case 'tool-call-delta': {
      const block = draft.blocks.get(index) ?? { type: 'tool-call', text: '' }
      block.type = 'tool-call'
      if (typeof chunk.id === 'string') block.id = chunk.id
      if (typeof chunk.name === 'string') block.name = chunk.name
      if (typeof chunk.argumentsDelta === 'string') block.args = (block.args ?? '') + chunk.argumentsDelta
      draft.blocks.set(index, block)
      break
    }
    case 'block-end':
      if (isObject(chunk.block)) {
        const b = chunk.block
        if ((b.type === 'text' || b.type === 'reasoning') && typeof b.text === 'string') draft.blocks.set(index, { type: b.type, text: b.text })
        else if (b.type === 'tool-call') draft.blocks.set(index, { type: 'tool-call', text: '', id: String(b.id ?? ''), name: String(b.name ?? ''), args: typeof b.arguments === 'string' ? b.arguments : '' })
      }
      break
    default:
      break // usage、finish 等不影响显示
  }
}

/**
 * 草稿 → 消息结构（protocol.md 5.2），带 draft: true。
 * @param {Draft} draft
 * @returns {import('./transcript.js').Message & { draft: true }}
 */
export function draftMessage(draft) {
  /** @type {import('./transcript.js').MessagePart[]} */
  const parts = []
  for (const index of [...draft.blocks.keys()].sort((a, b) => a - b)) {
    const block = /** @type {NonNullable<ReturnType<Draft['blocks']['get']>>} */ (draft.blocks.get(index))
    if (block.type === 'text' && block.text) parts.push({ type: 'text', text: block.text })
    else if (block.type === 'reasoning' && block.text) parts.push({ type: 'thinking', text: block.text })
    else if (block.type === 'tool-call' && block.name) {
      // 参数还在输出时通常不是完整的 JSON，解析失败就先显示工具名
      const { input } = parseToolInput(block.args ?? '')
      parts.push({ type: 'tool', callId: block.id ?? '', name: block.name, summary: toolSummary(block.name, input), status: 'running', input })
    }
  }
  return { id: draftId(draft), role: 'assistant', parts, createdAt: draft.createdAt, draft: true }
}

// ---------------------------------------------------------------- 单个会话的消息流

/** 记录是否是某个事件类型 @param {unknown} record @param {string} type */
const isEvent = (record, type) => isObject(record) && record.type === 'event' && isObject(record.event) && record.event.type === type

/**
 * 一个会话的实时消息：打开一条 session/follow，增量转换出 message.upsert。
 * 多个手机订阅同一个会话时共用一个 SessionFeed。
 */
export class SessionFeed {
  #dsh
  #sessionId
  #emit
  #now
  #throttleMs
  /** @type {unknown[]} 从本轮开始的会话日志 */
  #buffer = []
  /** @type {Map<string, string>} 消息 id → 上次推送的内容（JSON） */
  #sent = new Map()
  /** @type {Map<string, number>} 消息 id → rev */
  #revs = new Map()
  /** @type {Map<number, string>} 轮次 → 上次推送的内容（JSON） */
  #sentTurns = new Map()
  /** @type {Map<string, Draft>} attemptId → 草稿 */
  #drafts = new Map()
  /** @type {Map<string, NodeJS.Timeout>} 草稿的节流定时器 */
  #draftTimers = new Map()
  /** @type {import('./connection.js').StreamHandle | undefined} */
  #stream
  #closed = false
  /** 是否已经收到过 snapshot（之后的 snapshot 都是断线重连） */
  #seeded = false
  #retryMs = FEED_RETRY_MIN_MS
  /** @type {NodeJS.Timeout | undefined} */
  #retryTimer
  /** 上次推送（或订阅时读到）的计划（JSON），没变就不推送 @type {string | undefined} */
  #planJson
  /** @type {NodeJS.Timeout | undefined} */
  #planTimer
  #planDebounceMs

  /**
   * @param {import('./connection.js').DshConnection} dsh
   * @param {string} sessionId
   * @param {(event: MessageEvent) => void} emit
   * @param {{ now?: () => number, draftThrottleMs?: number, planDebounceMs?: number }} [options]
   */
  constructor(dsh, sessionId, emit, options = {}) {
    this.#dsh = dsh
    this.#sessionId = sessionId
    this.#emit = emit
    this.#now = options.now ?? Date.now
    this.#throttleMs = options.draftThrottleMs ?? DRAFT_THROTTLE_MS
    this.#planDebounceMs = options.planDebounceMs ?? PLAN_DEBOUNCE_MS
    void this.#open()
  }

  async #open() {
    if (this.#closed) return
    try {
      const handle = await this.#dsh.openStream('session/follow', {
        request: { address: { kind: 'session', sessionId: this.#sessionId }, activate: true, assistantStream: true, maxMessages: 20 },
      }, {
        onItem: (value) => this.#onFrame(value),
        onEnd: () => this.#reopenLater(),
        onError: () => this.#reopenLater(),
      }, { actor: PLUGIN_ACTOR })
      if (this.#closed) { handle.cancel(); return }
      this.#stream = handle
    } catch {
      this.#reopenLater()
    }
  }

  #reopenLater() {
    this.#stream = undefined
    if (this.#closed || this.#retryTimer) return
    this.#retryTimer = setTimeout(() => { this.#retryTimer = undefined; void this.#open() }, this.#retryMs)
    this.#retryMs = Math.min(this.#retryMs * 2, FEED_RETRY_MAX_MS)
  }

  /** @param {unknown} frame */
  #onFrame(frame) {
    if (!isObject(frame)) return
    if (frame.type === 'snapshot') {
      this.#retryMs = FEED_RETRY_MIN_MS
      this.#onSnapshot(frame)
    } else if (frame.type === 'event') {
      this.#onRecord(frame)
    } else if (frame.type === 'assistant-stream' && isObject(frame.frame)) {
      this.#onStreamFrame(frame.frame)
    }
  }

  /**
   * snapshot：App 已经通过 /v1/sessions/{id}/messages 读过历史，这里只用来建立增量转换的起点。
   * 重新打开（断线重连）时，和之前推送过的内容比较，只推送变化的部分。
   * @param {Record<string, any>} frame
   */
  #onSnapshot(frame) {
    const records = Array.isArray(frame.records) ? frame.records : []
    let start = 0
    for (let i = records.length - 1; i >= 0; i--) {
      if (isEvent(records[i], 'turn/start')) { start = i; break }
    }
    this.#buffer = records.slice(start)
    this.#convert({ silent: !this.#seeded })
    // 订阅时 App 自己读计划；断线重连后计划可能变了，重新读一次，变了才推送
    this.#schedulePlan({ silent: !this.#seeded })
    this.#seeded = true
    // 正在输出的回复
    const active = isObject(frame.assistantStream) && isObject(frame.assistantStream.activeAttempt) ? frame.assistantStream.activeAttempt : undefined
    for (const draft of this.#drafts.values()) this.#dropDraft(draft, active?.attemptId !== draft.attemptId)
    if (active && typeof active.attemptId === 'string') {
      const draft = this.#startDraft(active)
      for (const entry of Array.isArray(active.stream) ? active.stream : []) applyStreamEntry(draft, entry)
      this.#flushDraft(draft)
    }
  }

  /** @param {Record<string, any>} record */
  #onRecord(record) {
    if (isEvent(record, 'turn/start')) this.#buffer = []
    this.#buffer.push(record)
    if (this.#buffer.length > FEED_BUFFER_LIMIT) this.#buffer.splice(0, this.#buffer.length - FEED_BUFFER_LIMIT)
    // 只有可能影响消息的事件才需要重新转换
    const type = isObject(record.event) ? record.event.type : undefined
    if (['user/message', 'assistant/message', 'tool/result', 'approval/decided', 'turn/start', 'turn/end'].includes(String(type))) this.#convert({ silent: false })
    // 目标、任务清单变了；新一轮开始时 DSH 会清空任务清单（protocol.md「计划」）
    if (['todo/write', 'goal/change', 'turn/start'].includes(String(type))) this.#schedulePlan({ silent: false })
  }

  /**
   * 稍等一下再读计划：同一时刻的几条事件只读一次。silent：只记录，不推送。
   * @param {{ silent: boolean }} options
   */
  #schedulePlan(options) {
    if (this.#closed) return
    let silent = options.silent
    if (this.#planTimer) { clearTimeout(this.#planTimer); silent = false }
    this.#planTimer = setTimeout(() => { this.#planTimer = undefined; void this.#refreshPlan(silent) }, this.#planDebounceMs)
  }

  /** @param {boolean} silent */
  async #refreshPlan(silent) {
    let result
    try {
      result = await this.#dsh.call('session/projections', { request: { sessionId: this.#sessionId } }, { actor: PLUGIN_ACTOR })
    } catch {
      return // 连不上 DSH：等下一次事件或重连
    }
    if (this.#closed || !result.ok) return
    const plan = convertPlan(result.value)
    const json = JSON.stringify(plan)
    if (json === this.#planJson) return
    this.#planJson = json
    if (!silent) this.#emit({ type: 'plan.updated', eventId: randomUUID(), sessionId: this.#sessionId, plan })
  }

  /** @param {{ silent: boolean }} options silent：只记录，不推送 */
  #convert(options) {
    for (const message of convertRecords(this.#buffer)) {
      const json = JSON.stringify(message)
      if (this.#sent.get(message.id) === json) continue
      this.#sent.set(message.id, json)
      if (options.silent) { this.#revs.set(message.id, 0); continue }
      /** @type {string | undefined} */
      let replaces
      if (message.role === 'assistant') {
        const draft = this.#draftFor(message.id)
        if (draft) { replaces = draftId(draft); this.#dropDraft(draft, false) }
      }
      this.#upsert(message, replaces)
    }
    // 一轮开始和结束（protocol.md 6.1 turn.updated）
    for (const turn of extractTurns(this.#buffer)) {
      const json = JSON.stringify(turn)
      if (this.#sentTurns.get(turn.turn) === json) continue
      this.#sentTurns.set(turn.turn, json)
      if (!options.silent) this.#emit({ type: 'turn.updated', eventId: randomUUID(), sessionId: this.#sessionId, turn })
    }
    if (this.#sentTurns.size > 50) {
      for (const key of [...this.#sentTurns.keys()].sort((a, b) => a - b).slice(0, this.#sentTurns.size - 50)) this.#sentTurns.delete(key)
    }
    // 只保留最近的记录，防止长时间订阅时无限增长
    if (this.#sent.size > 500) {
      const keep = new Set(convertRecords(this.#buffer).map((m) => m.id))
      for (const id of [...this.#sent.keys()]) if (!keep.has(id)) { this.#sent.delete(id); this.#revs.delete(id) }
    }
  }

  /**
   * 一条刚写入日志的模型回复对应哪个草稿：按 assistant/message 事件的 turn、step 匹配。
   * @param {string} messageId
   */
  #draftFor(messageId) {
    const record = this.#buffer.find((r) => isEvent(r, 'assistant/message') && /** @type {any} */ (r).event.data?.message?.id === messageId)
    const data = /** @type {any} */ (record)?.event?.data
    if (!data) return undefined
    return [...this.#drafts.values()].find((d) => d.turn === data.turn && d.step === data.step)
  }

  /** @param {import('./transcript.js').Message & { draft?: true }} message @param {string} [replaces] */
  #upsert(message, replaces) {
    const rev = (this.#revs.get(message.id) ?? 0) + 1
    this.#revs.set(message.id, rev)
    /** @type {MessageEvent} */
    const event = { type: 'message.upsert', eventId: randomUUID(), sessionId: this.#sessionId, rev, message }
    if (replaces) event.replaces = replaces
    this.#emit(event)
  }

  /** @param {Record<string, any>} frame */
  #startDraft(frame) {
    /** @type {Draft} */
    const draft = {
      attemptId: String(frame.attemptId),
      turn: typeof frame.turn === 'number' ? frame.turn : -1,
      step: typeof frame.step === 'number' ? frame.step : -1,
      createdAt: this.#now(),
      blocks: new Map(),
    }
    this.#drafts.set(draft.attemptId, draft)
    return draft
  }

  /** @param {Record<string, any>} frame */
  #onStreamFrame(frame) {
    if (typeof frame.attemptId !== 'string') return
    if (frame.type === 'start') {
      this.#startDraft(frame)
      return
    }
    const draft = this.#drafts.get(frame.attemptId)
    if (!draft) return
    if (frame.type === 'chunk') {
      applyStreamEntry(draft, frame.chunk)
      this.#scheduleDraft(draft)
    } else if (frame.type === 'end') {
      // committed 到 assistant/message 时，草稿通常已经在收到那条事件时被替换掉了；
      // 其他结局（abandoned，或写成 assistant/attempt 后重试）删除草稿
      const committedMessage = isObject(frame.outcome) && frame.outcome.kind === 'committed' && frame.outcome.eventType === 'assistant/message'
      if (!committedMessage) this.#dropDraft(draft, true)
    }
  }

  /** @param {Draft} draft */
  #scheduleDraft(draft) {
    if (this.#draftTimers.has(draft.attemptId)) return
    this.#draftTimers.set(draft.attemptId, setTimeout(() => {
      this.#draftTimers.delete(draft.attemptId)
      if (this.#drafts.get(draft.attemptId) === draft) this.#flushDraft(draft)
    }, this.#throttleMs))
  }

  /** @param {Draft} draft */
  #flushDraft(draft) {
    const message = draftMessage(draft)
    if (message.parts.length === 0) return
    const json = JSON.stringify(message)
    if (this.#sent.get(message.id) === json) return
    this.#sent.set(message.id, json)
    this.#upsert(message)
  }

  /** @param {Draft} draft @param {boolean} notify 是否通知 App 删除这条草稿 */
  #dropDraft(draft, notify) {
    this.#drafts.delete(draft.attemptId)
    clearTimeout(this.#draftTimers.get(draft.attemptId))
    this.#draftTimers.delete(draft.attemptId)
    const id = draftId(draft)
    const wasSent = this.#sent.has(id)
    this.#sent.delete(id)
    this.#revs.delete(id)
    if (notify && wasSent) this.#emit({ type: 'message.removed', eventId: randomUUID(), sessionId: this.#sessionId, messageId: id })
  }

  close() {
    this.#closed = true
    clearTimeout(this.#retryTimer)
    clearTimeout(this.#planTimer)
    for (const timer of this.#draftTimers.values()) clearTimeout(timer)
    this.#draftTimers.clear()
    this.#stream?.cancel()
  }
}

// ---------------------------------------------------------------- 广播状态

/**
 * 所有手机共享的实时状态：会话摘要的变化、任务完成、待处理事项。
 */
export class LiveHub {
  #dsh
  #facade
  #attention
  #now
  #idleMs
  #debounceMs
  #intervalMs
  /** @type {Set<(event: BroadcastEvent) => void>} */
  #listeners = new Set()
  /** @type {Map<string, string> | undefined} sessionId → 上次的会话摘要（JSON）；undefined 表示还没有基准 */
  #summaries
  /** @type {Map<string, import('./facade.js').SessionSummary>} */
  #latest = new Map()
  /** @type {Map<string, NodeJS.Timeout>} 正在等待「任务完成」的会话 */
  #idleTimers = new Map()
  /** @type {NodeJS.Timeout | undefined} */
  #debounceTimer
  /** @type {NodeJS.Timeout | undefined} */
  #intervalTimer
  #refreshing = false
  #refreshAgain = false
  /** 是否已经为最近的会话补算过最近一轮的结果 */
  #backfilled = false
  /** @type {Map<string, { feed: SessionFeed, listeners: Set<(event: MessageEvent) => void> }>} */
  #feeds = new Map()
  /** @type {(() => void)[]} */
  #unsubscribe = []

  /**
   * @param {import('./connection.js').DshConnection} dsh
   * @param {import('./facade.js').Facade} facade
   * @param {import('./attention.js').AttentionTracker} attention
   * @param {{ now?: () => number, idleMs?: number, debounceMs?: number, intervalMs?: number }} [options]
   */
  constructor(dsh, facade, attention, options = {}) {
    this.#dsh = dsh
    this.#facade = facade
    this.#attention = attention
    this.#now = options.now ?? Date.now
    this.#idleMs = options.idleMs ?? IDLE_DELAY_MS
    this.#debounceMs = options.debounceMs ?? REFRESH_DEBOUNCE_MS
    this.#intervalMs = options.intervalMs ?? REFRESH_INTERVAL_MS
    this.#unsubscribe.push(dsh.onEvent((event) => this.#onDshEvent(event)))
    this.#unsubscribe.push(attention.onChange((change) => this.#onAttention(change)))
    // DSH 重连后，断线期间的变化没有事件，刷新一次
    this.#unsubscribe.push(dsh.onStatus((status) => { if (status.state === 'ready') this.#scheduleRefresh() }))
  }

  /**
   * 订阅广播事件。有订阅者时才定时刷新会话列表。
   * @param {(event: BroadcastEvent) => void} listener
   * @returns {() => void}
   */
  onEvent(listener) {
    this.#listeners.add(listener)
    if (!this.#intervalTimer) {
      this.#intervalTimer = setInterval(() => this.#scheduleRefresh(), this.#intervalMs)
      this.#intervalTimer.unref?.()
    }
    return () => {
      this.#listeners.delete(listener)
      if (this.#listeners.size === 0) { clearInterval(this.#intervalTimer); this.#intervalTimer = undefined }
    }
  }

  /** @param {BroadcastEvent} event */
  #broadcast(event) {
    for (const listener of this.#listeners) {
      try { listener(event) } catch { /* 单个连接的错误不影响其他连接 */ }
    }
  }

  /**
   * 连接建立时的完整状态（hello）。同时把它作为之后比较的基准。
   * @param {string} actor
   */
  async snapshot(actor) {
    const { sessions } = await this.#facade.sessions(actor)
    this.#setBaseline(sessions)
    if (!this.#backfilled) {
      this.#backfilled = true
      void this.#backfill(sessions)
    }
    return { sessions, attention: this.#attention.list() }
  }

  /**
   * 插件启动后，为最近更新的 [DIGEST_BACKFILL] 个会话读取最近一轮的结果（用户确认：只补最近的），
   * 每次 [DIGEST_CONCURRENCY] 个，读完推送变化。更早的会话等它们下一次任务结束。
   * @param {import('./facade.js').SessionSummary[]} sessions
   */
  async #backfill(sessions) {
    const queue = [...sessions].sort((a, b) => b.updatedAt - a.updatedAt).slice(0, DIGEST_BACKFILL)
      .map((s) => s.sessionId).filter((id) => !this.#facade.hasDigest(id))
    const worker = async () => {
      for (let id = queue.shift(); id !== undefined; id = queue.shift()) {
        try { await this.#facade.refreshDigest(PLUGIN_ACTOR, id) } catch { /* 读不到就算了，等下一次任务结束 */ }
      }
    }
    await Promise.all(Array.from({ length: DIGEST_CONCURRENCY }, worker))
    this.#scheduleRefresh()
  }

  /** @param {import('./facade.js').SessionSummary[]} sessions */
  #setBaseline(sessions) {
    this.#summaries = new Map(sessions.map((s) => [s.sessionId, JSON.stringify(s)]))
    this.#latest = new Map(sessions.map((s) => [s.sessionId, s]))
  }

  /** @param {import('./connection.js').DshAppEvent} event */
  #onDshEvent(event) {
    if (event.type !== 'emit' || !event.event.startsWith('api-session/')) return
    if (event.event === 'api-session/status') {
      const [sessionId, running] = event.args
      if (typeof sessionId === 'string') this.#onRunning(sessionId, running === true)
    }
    this.#scheduleRefresh()
  }

  /** @param {string} sessionId @param {boolean} running */
  #onRunning(sessionId, running) {
    clearTimeout(this.#idleTimers.get(sessionId))
    this.#idleTimers.delete(sessionId)
    if (running) return
    // 停下来了：读一次日志末尾，更新最近一轮的结果和最后一句回复，读完推送会话摘要的变化
    this.#facade.refreshDigest(PLUGIN_ACTOR, sessionId).then(() => this.#scheduleRefresh(), () => {})
    // 停下来后持续 8 秒都没有再运行，才算任务完成；有待处理事项时不算（在等人回答）
    this.#idleTimers.set(sessionId, setTimeout(() => {
      this.#idleTimers.delete(sessionId)
      if (this.#attention.list(sessionId).length > 0) return
      /** @type {BroadcastEvent} */
      const event = { type: 'session.idle', eventId: randomUUID(), sessionId, title: this.#latest.get(sessionId)?.title ?? null }
      const result = this.#facade.digest(sessionId)?.lastTurn?.result
      if (result) event.result = result
      this.#broadcast(event)
    }, this.#idleMs))
  }

  /** @param {import('./attention.js').AttentionChange} change */
  #onAttention(change) {
    if (change.type === 'requested') {
      this.#broadcast({ type: 'attention.requested', eventId: randomUUID(), attention: change.attention })
    } else {
      this.#broadcast({ type: 'attention.resolved', eventId: randomUUID(), attentionId: change.attentionId, sessionId: change.sessionId, by: change.by })
    }
    // 会话的 status（waiting）和 attention 列表随之变化
    this.#scheduleRefresh()
  }

  /** 会话的设置被改动了（切换模型、权限模式）：尽快重新读取会话列表并推送变化。 */
  refreshSoon() {
    this.#scheduleRefresh()
  }

  #scheduleRefresh() {
    if (this.#listeners.size === 0) return
    clearTimeout(this.#debounceTimer)
    this.#debounceTimer = setTimeout(() => { void this.#refresh() }, this.#debounceMs)
  }

  /** 重新读取会话列表，和上次比较，推送变化。 */
  async #refresh() {
    if (this.#refreshing) { this.#refreshAgain = true; return }
    this.#refreshing = true
    try {
      const { sessions } = await this.#facade.sessions(PLUGIN_ACTOR)
      const previous = this.#summaries
      if (previous) {
        const seen = new Set()
        for (const session of sessions) {
          seen.add(session.sessionId)
          if (previous.get(session.sessionId) !== JSON.stringify(session)) {
            this.#broadcast({ type: 'session.updated', eventId: randomUUID(), session })
          }
        }
        for (const sessionId of previous.keys()) {
          if (!seen.has(sessionId)) this.#broadcast({ type: 'session.removed', eventId: randomUUID(), sessionId })
        }
      }
      this.#setBaseline(sessions)
    } catch {
      // DSH 暂时不可用：等下一次事件或定时刷新
    } finally {
      this.#refreshing = false
      if (this.#refreshAgain) { this.#refreshAgain = false; this.#scheduleRefresh() }
    }
  }

  /**
   * 订阅一个会话的实时消息。多个订阅者共用一条 session/follow，最后一个取消时关闭。
   * @param {string} sessionId
   * @param {(event: MessageEvent) => void} listener
   * @returns {() => void}
   */
  subscribe(sessionId, listener) {
    let entry = this.#feeds.get(sessionId)
    if (!entry) {
      /** @type {Set<(event: MessageEvent) => void>} */
      const listeners = new Set()
      const feed = new SessionFeed(this.#dsh, sessionId, (event) => {
        for (const l of listeners) {
          try { l(event) } catch { /* 单个连接的错误不影响其他连接 */ }
        }
      }, { now: this.#now })
      entry = { feed, listeners }
      this.#feeds.set(sessionId, entry)
    }
    entry.listeners.add(listener)
    const current = entry
    return () => {
      current.listeners.delete(listener)
      if (current.listeners.size === 0 && this.#feeds.get(sessionId) === current) {
        current.feed.close()
        this.#feeds.delete(sessionId)
      }
    }
  }

  close() {
    for (const off of this.#unsubscribe) off()
    for (const timer of this.#idleTimers.values()) clearTimeout(timer)
    clearTimeout(this.#debounceTimer)
    clearInterval(this.#intervalTimer)
    for (const { feed } of this.#feeds.values()) feed.close()
    this.#feeds.clear()
    this.#listeners.clear()
  }
}
