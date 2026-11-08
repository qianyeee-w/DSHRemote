// @ts-check
/**
 * 待处理事项（审批、提问）的跟踪与回答，protocol.md 5.3。
 *
 * DSH 的行为（api/gateway/src/index.ts，docs/research/dsh-session-api.md 5.7）：
 * - 审批和提问以 waterfall 事件发给所有 owner 的 `$events` 客户端；任一客户端回答后，
 *   其他客户端收到 `cancel` 帧。任务被停止时也会收到 `cancel`。
 * - 新订阅的客户端会立即收到所有还没处理的 waterfall（重放），所以断线重连后能恢复。
 * - 已经被别人处理掉的事件，再用 `$events/result` 回答时 DSH 静默忽略，仍然返回成功。
 *   所以「别处已处理」只能通过 `cancel` 帧判断：我们自己回答成功时不会收到 cancel，
 *   回答时或回答后不久收到 cancel，说明别人先处理了（409）。
 */
import { DshUnavailableError, PLUGIN_ACTOR } from './connection.js'
import { FacadeError } from './facade.js'
import { toolSummary, parseToolInput } from './transcript.js'

/** 回答成功后，再等多久看有没有 cancel 帧（说明别人先处理了） */
const ANSWER_GRACE_MS = 300
/** 重连后，旧事项在这段时间内没有被 DSH 重放，就认为已经处理掉了 */
const RESYNC_WINDOW_MS = 2_000
/** 为审批补充工具调用详情时，最多等多久 */
const ENRICH_TIMEOUT_MS = 2_000
/** 记住最近处理掉的事项，重复回答时返回 409 而不是 404 */
const RESOLVED_MEMORY = 500
/** 单个问题、选项、自由文本的长度上限 */
const ANSWER_TEXT_LIMIT = 4_000

/** @param {unknown} value @returns {value is Record<string, any>} */
const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value)

/**
 * @typedef {object} ApprovalDetail
 * @property {string} toolName
 * @property {string} reason 给人看的原因（优先中文）
 * @property {string} [summary] 工具调用的一行说明，例如「运行命令：npm test」
 * @property {Record<string, unknown>} [input] 工具调用的参数（已截断）
 */

/**
 * @typedef {object} QuestionDetail
 * @property {{ id: string, question: string, header: string, options: { label: string, description?: string }[], multiSelect: boolean }[]} questions
 */

/**
 * @typedef {object} Attention
 * @property {string} attentionId
 * @property {string} sessionId
 * @property {'approval' | 'question'} kind
 * @property {ApprovalDetail} [approval]
 * @property {QuestionDetail} [question]
 * @property {number} createdAt
 */

/**
 * @typedef {{ type: 'requested', attention: Attention }
 *   | { type: 'resolved', attentionId: string, sessionId: string, by: string | null }} AttentionChange
 * by：回答它的设备的 deviceId；在别处处理掉或任务被停止时为 null（DSH 不区分这两种情况）。
 */

/** @param {unknown} request @returns {ApprovalDetail} */
function approvalDetail(request) {
  const r = isObject(request) ? request : {}
  const display = isObject(r.displayReason) ? r.displayReason : {}
  const reason = [display.zh, display.en, r.reason].find((v) => typeof v === 'string')
  return { toolName: typeof r.toolName === 'string' ? r.toolName : '', reason: typeof reason === 'string' ? reason : '' }
}

/** @param {unknown} request @returns {QuestionDetail | undefined} */
function questionDetail(request) {
  const r = isObject(request) ? request : {}
  if (!Array.isArray(r.questions)) return undefined
  const questions = r.questions.filter(isObject).filter((q) => typeof q.id === 'string').map((q) => ({
    id: String(q.id),
    question: typeof q.question === 'string' ? q.question : '',
    header: typeof q.header === 'string' ? q.header : '',
    options: (Array.isArray(q.options) ? q.options : []).filter(isObject).filter((o) => typeof o.label === 'string').map((o) => {
      /** @type {{ label: string, description?: string }} */
      const option = { label: String(o.label) }
      if (typeof o.description === 'string') option.description = o.description
      return option
    }),
    multiSelect: q.multiSelect === true,
  }))
  return questions.length > 0 ? { questions } : undefined
}

/**
 * 校验 App 发来的回答，转换成 DSH 的 outcome value。格式不对时抛出 400。
 * @param {Attention} attention
 * @param {unknown} answer
 */
export function toDshAnswer(attention, answer) {
  const bad = (/** @type {string} */ message) => new FacadeError(400, 'BAD_REQUEST', message)
  if (!isObject(answer)) throw bad('回答必须是 JSON 对象')
  if (attention.kind === 'approval') {
    if (answer.decision === 'allow') return 'allowed-once'
    if (answer.decision === 'reject') return 'rejected'
    throw bad('decision 必须是 allow 或 reject')
  }
  const questions = attention.question?.questions ?? []
  if (!Array.isArray(answer.answers) || answer.answers.length === 0 || answer.answers.length > questions.length) {
    throw bad('answers 必须是非空数组，并且不能多于问题数')
  }
  const seen = new Set()
  const answers = answer.answers.map((/** @type {unknown} */ item) => {
    if (!isObject(item) || typeof item.id !== 'string') throw bad('每个回答都要有 id')
    const question = questions.find((q) => q.id === item.id)
    if (!question) throw bad('回答的 id 不属于这个提问')
    if (seen.has(item.id)) throw bad('同一个问题回答了多次')
    seen.add(item.id)
    const selected = item.selected ?? []
    if (!Array.isArray(selected) || selected.some((s) => typeof s !== 'string' || s.length > ANSWER_TEXT_LIMIT)) throw bad('selected 必须是字符串数组')
    if (!question.multiSelect && selected.length > 1) throw bad('这个问题只能选一项')
    if (selected.some((s) => !question.options.some((o) => o.label === s))) throw bad('selected 中有不存在的选项')
    /** @type {{ id: string, selected: string[], custom?: string }} */
    const out = { id: item.id, selected: /** @type {string[]} */ (selected) }
    if (item.custom !== undefined) {
      if (typeof item.custom !== 'string' || item.custom.length > ANSWER_TEXT_LIMIT) throw bad(`custom 必须是不超过 ${ANSWER_TEXT_LIMIT} 个字符的字符串`)
      if (item.custom.trim() !== '') out.custom = item.custom
    }
    if (out.selected.length === 0 && out.custom === undefined) throw bad('每个问题至少选一项或填写 custom')
    return out
  })
  return { answers }
}

export class AttentionTracker {
  #dsh
  #now
  #graceMs
  #resyncMs
  /** @type {Map<string, Attention>} */
  #items = new Map()
  /** @type {Map<string, string>} 最近处理掉的事项 → sessionId */
  #resolved = new Map()
  /** @type {Set<string>} 正在回答的事项 */
  #answering = new Set()
  /** @type {Set<string>} 回答期间收到了 cancel 的事项 */
  #cancelledWhileAnswering = new Set()
  /** @type {Set<string> | undefined} 重连后等待 DSH 重放确认的旧事项 */
  #unconfirmed
  /** @type {NodeJS.Timeout | undefined} */
  #resyncTimer
  /** @type {Set<(change: AttentionChange) => void>} */
  #listeners = new Set()
  #wasReady = false
  /** @type {(() => void)[]} */
  #unsubscribe = []

  /**
   * @param {import('./connection.js').DshConnection} dsh
   * @param {{ now?: () => number, graceMs?: number, resyncMs?: number }} [options]
   */
  constructor(dsh, options = {}) {
    this.#dsh = dsh
    this.#now = options.now ?? Date.now
    this.#graceMs = options.graceMs ?? ANSWER_GRACE_MS
    this.#resyncMs = options.resyncMs ?? RESYNC_WINDOW_MS
    this.#unsubscribe.push(dsh.onEvent((event) => this.#onEvent(event)))
    this.#unsubscribe.push(dsh.onStatus((status) => this.#onStatus(status.state)))
    this.#wasReady = dsh.status().state === 'ready'
  }

  /** @param {(change: AttentionChange) => void} listener @returns {() => void} */
  onChange(listener) {
    this.#listeners.add(listener)
    return () => this.#listeners.delete(listener)
  }

  /** @param {AttentionChange} change */
  #emit(change) {
    for (const listener of this.#listeners) {
      try { listener(change) } catch { /* 单个监听者的错误不影响其他监听者 */ }
    }
  }

  /**
   * 当前所有待处理事项，按出现时间排序。
   * @param {string} [sessionId] 只返回这个会话的
   */
  list(sessionId) {
    const all = [...this.#items.values()].sort((a, b) => a.createdAt - b.createdAt)
    return sessionId === undefined ? all : all.filter((a) => a.sessionId === sessionId)
  }

  /** @param {string} attentionId */
  get(attentionId) {
    return this.#items.get(attentionId)
  }

  /** @param {'connecting' | 'ready' | 'unavailable' | 'closed'} state */
  #onStatus(state) {
    if (state === 'ready' && !this.#wasReady) {
      this.#wasReady = true
      // 重连：DSH 会立即重放还没处理的事项。过一段时间还没被重放的，说明断线期间已经处理掉了
      if (this.#items.size > 0) {
        this.#unconfirmed = new Set(this.#items.keys())
        clearTimeout(this.#resyncTimer)
        this.#resyncTimer = setTimeout(() => this.#finishResync(), this.#resyncMs)
      }
    } else if (state !== 'ready') {
      this.#wasReady = false
    }
  }

  #finishResync() {
    const stale = this.#unconfirmed ?? new Set()
    this.#unconfirmed = undefined
    for (const attentionId of stale) this.#resolve(attentionId, null)
  }

  /** @param {import('./connection.js').DshAppEvent} event */
  #onEvent(event) {
    if (event.type === 'cancel') {
      if (this.#answering.has(event.eventId)) this.#cancelledWhileAnswering.add(event.eventId)
      else this.#resolve(event.eventId, null)
      return
    }
    if (event.type !== 'waterfall') return
    const kind = event.event === 'approval/request' ? 'approval' : event.event === 'user-questions/request' ? 'question' : undefined
    if (!kind) return
    this.#unconfirmed?.delete(event.eventId)
    if (this.#items.has(event.eventId)) return // 重连后的重放
    /** @type {Attention} */
    const attention = { attentionId: event.eventId, sessionId: event.agentId, kind, createdAt: this.#now() }
    if (kind === 'approval') {
      attention.approval = approvalDetail(event.request)
    } else {
      const question = questionDetail(event.request)
      if (!question) return // 格式不认识的提问不转发，仍然可以在 Desktop 上回答
      attention.question = question
    }
    this.#items.set(attention.attentionId, attention)
    const callId = isObject(event.request) && typeof event.request.callId === 'string' ? event.request.callId : undefined
    if (kind === 'approval' && callId) {
      void this.#enrich(attention, callId).finally(() => {
        if (this.#items.get(attention.attentionId) === attention) this.#emit({ type: 'requested', attention })
      })
    } else {
      this.#emit({ type: 'requested', attention })
    }
  }

  /**
   * 审批请求里只有工具名和原因，看不到要执行的命令。从会话日志的末尾找到对应的 tool/call，
   * 补上摘要和参数，让手机上能看清楚批准的是什么。找不到时不补。
   * @param {Attention} attention @param {string} callId
   */
  async #enrich(attention, callId) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), ENRICH_TIMEOUT_MS)
    try {
      const result = await this.#dsh.call('session/page', { request: { address: { kind: 'session', sessionId: attention.sessionId }, maxMessages: 2 } }, { actor: PLUGIN_ACTOR, signal: controller.signal })
      if (!result.ok || !isObject(result.value) || !Array.isArray(result.value.records)) return
      const call = result.value.records.map((r) => (isObject(r) && isObject(r.event) ? r.event : undefined))
        .find((e) => e?.type === 'tool/call' && isObject(e.data) && e.data.callId === callId)
      if (!call || !attention.approval) return
      const name = typeof call.data.name === 'string' ? call.data.name : attention.approval.toolName
      const { input } = parseToolInput(call.data.arguments)
      attention.approval.summary = toolSummary(name, input)
      attention.approval.input = input
    } catch {
      // 补充信息失败不影响审批本身
    } finally {
      clearTimeout(timer)
    }
  }

  /** @param {string} attentionId @param {string | null} by */
  #resolve(attentionId, by) {
    const attention = this.#items.get(attentionId)
    if (!attention) return
    this.#items.delete(attentionId)
    this.#resolved.set(attentionId, attention.sessionId)
    if (this.#resolved.size > RESOLVED_MEMORY) this.#resolved.delete(/** @type {string} */ (this.#resolved.keys().next().value))
    this.#emit({ type: 'resolved', attentionId, sessionId: attention.sessionId, by })
  }

  /**
   * 回答一个待处理事项（POST /v1/attention/{id}/answer）。
   * @param {string} deviceId 回答的设备
   * @param {string} attentionId
   * @param {unknown} answer App 发来的回答（protocol.md 5.3）
   * @returns {Promise<{ attentionId: string }>}
   */
  async answer(deviceId, attentionId, answer) {
    const attention = this.#items.get(attentionId)
    if (!attention || this.#answering.has(attentionId)) {
      if (attention || this.#resolved.has(attentionId)) throw new FacadeError(409, 'ATTENTION_RESOLVED', '这项审批或提问已经在别处处理了')
      throw new FacadeError(404, 'NOT_FOUND', '待处理事项不存在')
    }
    const value = toDshAnswer(attention, answer)
    this.#answering.add(attentionId)
    try {
      const result = await this.#dsh.answerEvent(attentionId, { kind: 'result', value }, { actor: deviceId })
      if (!result.ok) throw new FacadeError(502, 'DSH_ERROR', `DSH 无法完成这个操作（${result.error.code}）`)
      // DSH 对「已经被别人处理掉」的回答静默返回成功；等一小会儿，看有没有 cancel 帧
      if (!this.#cancelledWhileAnswering.has(attentionId)) await new Promise((r) => setTimeout(r, this.#graceMs))
      if (this.#cancelledWhileAnswering.has(attentionId)) {
        this.#resolve(attentionId, null)
        throw new FacadeError(409, 'ATTENTION_RESOLVED', '这项审批或提问已经在别处处理了')
      }
      this.#resolve(attentionId, deviceId)
      return { attentionId }
    } catch (error) {
      if (error instanceof DshUnavailableError || (error instanceof FacadeError && error.status !== 409)) {
        // 没有回答成功：如果期间收到了 cancel，事项已经处理掉了；否则保持待处理
        if (this.#cancelledWhileAnswering.has(attentionId)) this.#resolve(attentionId, null)
      }
      throw error
    } finally {
      this.#answering.delete(attentionId)
      this.#cancelledWhileAnswering.delete(attentionId)
    }
  }

  close() {
    clearTimeout(this.#resyncTimer)
    for (const off of this.#unsubscribe) off()
    this.#listeners.clear()
  }
}
