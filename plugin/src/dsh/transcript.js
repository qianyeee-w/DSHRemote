// @ts-check
/**
 * 把 DSH 会话日志中的原始事件转换成 protocol.md 5.2 的消息结构。
 *
 * DSH 的事件格式会随版本演进（会话头里有 version 字段），所以转换只在这里做，
 * App 永远看不到 DSH 的内部事件（ADR 0007）。事件格式见 docs/research/dsh-session-api.md 5.2，
 * 测试数据是从真实 DSH 录制的（test/fixtures/dsh-<版本>/）。
 *
 * 规则：
 * - 只转换用户真正输入的消息（user/message 且 source.kind 为 user）和模型回复（assistant/message）。
 *   运行时上下文、时间上下文、系统提示、请求头、遥测等内部事件一律丢弃。
 * - 模型回复里的 tool-call 块变成 tool 部件，状态由后面的 tool/result 和 approval/decided 决定。
 * - 工具的输入和输出可能很大，按上限截断并标记 truncated。
 */

import { deriveTurnTokenUsage } from '../vendor/dsh-turn-usage.mjs'

/** 工具输入中单个字符串字段的上限（字符） */
export const TOOL_INPUT_STRING_LIMIT = 2_000
/** 工具输出的上限（字符） */
export const TOOL_OUTPUT_LIMIT = 8_000
/** 命令输出超过上限时保留的开头和末尾（字符，合计不超过 TOOL_OUTPUT_LIMIT；落地方案 B6） */
export const COMMAND_HEAD_LIMIT = 1_000
export const COMMAND_TAIL_LIMIT = 7_000
/** 这些工具的输出是命令的输出：结果通常在末尾，保留开头和末尾 */
const COMMAND_TOOLS = new Set(['bash', 'pwsh'])
/** 工具摘要的上限（字符） */
const SUMMARY_LIMIT = 120
/** 失败原因的上限（字符） */
export const TURN_ERROR_LIMIT = 200
/** 会话摘要里「最后一句回复」的上限（字符） */
export const LAST_REPLY_LIMIT = 120

/**
 * @typedef {{ type: 'text', text: string }
 *   | { type: 'thinking', text: string }
 *   | ToolPart} MessagePart
 */

/**
 * @typedef {object} ToolPart
 * @property {'tool'} type
 * @property {string} callId
 * @property {string} name
 * @property {string} summary
 * @property {'running' | 'done' | 'error' | 'rejected'} status
 * @property {Record<string, unknown>} input
 * @property {string} [output]
 * @property {true} [truncated]
 * @property {{ offset: number, chars: number, lines: number }} [omitted] 命令输出去掉了中间（protocol.md 5.2）
 * @property {true} [outputSaved] DSH 自己截断过结果，完整输出存在电脑上（protocol.md 5.2）
 */

/**
 * @typedef {object} Message
 * @property {string} id
 * @property {'user' | 'assistant'} role
 * @property {MessagePart[]} parts
 * @property {number} createdAt
 * @property {number} [turn] 属于第几轮（protocol.md 5.2）；无法确定时没有
 */

/**
 * 一轮（protocol.md 5.4）。还在运行的轮次没有 endedAt 和 result。
 * @typedef {object} Turn
 * @property {number} turn
 * @property {number} [startedAt]
 * @property {number} [endedAt]
 * @property {'completed' | 'stopped' | 'failed' | 'interrupted' | 'other'} [result]
 * @property {string} [error]
 * @property {'user' | 'other'} [stoppedBy]
 * @property {TurnUsage} [usage] 只在这一轮结束、而且读到了它从开始到结束的全部事件时才有（落地方案 B4）
 */

/**
 * 一轮的用量（protocol.md 5.4）。缓存两项只在每次请求都报告了时才有。
 * @typedef {object} TurnUsage
 * @property {number} totalTokens
 * @property {number} uncachedInputTokens
 * @property {number} outputTokens
 * @property {number} [cacheReadTokens]
 * @property {number} [cacheWriteTokens]
 */

/**
 * DSH 会话日志中的一条记录（session/page 的 records、session/follow 的 event 帧）。
 * @typedef {{ type: 'event', event: { type: string, seq: number, time: number, data: any, surfaceOp?: unknown } }} DshRecord
 */

/** @param {unknown} value @returns {value is Record<string, any>} */
const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value)

/**
 * 命令输出（protocol.md 5.2，落地方案 B6）：超过上限时保留开头约 COMMAND_HEAD_LIMIT 个字符和末尾约
 * COMMAND_TAIL_LIMIT 个字符，尽量切在换行处，不切开代理对；去掉中间，返回去掉的位置和多少。
 * @param {string} text
 * @returns {{ text: string, omitted?: { offset: number, chars: number, lines: number } }}
 */
export function clipCommandOutput(text) {
  if (text.length <= TOOL_OUTPUT_LIMIT) return { text }
  // 开头：到上限之前最后一个换行（含换行）；没有换行时直接按字符切
  let headEnd = text.lastIndexOf('\n', COMMAND_HEAD_LIMIT - 1) + 1
  if (headEnd <= 0) headEnd = safeCut(text, COMMAND_HEAD_LIMIT)
  // 末尾：从「倒数上限」位置之后的第一个换行的下一个字符开始；没有时直接按字符切
  const from = text.length - COMMAND_TAIL_LIMIT
  let tailStart = text.indexOf('\n', from) + 1
  if (tailStart <= 0 || tailStart >= text.length) tailStart = safeCut(text, from)
  if (tailStart < headEnd) tailStart = headEnd
  const middle = text.slice(headEnd, tailStart)
  return {
    text: text.slice(0, headEnd) + text.slice(tailStart),
    omitted: { offset: headEnd, chars: middle.length, lines: middle.split('\n').length - 1 },
  }
}

/**
 * DSH 截断过大的工具结果时附在最后的提示（dsh-spill-policy 的 formatSpillNotice）：
 * 「(Omitted N bytes.[ Omitted K images.] Full formatted result stored at: <路径>. <提示>)」，和前面的内容隔一个空行，
 * 也可能只有提示。
 */
const SPILL_NOTICE = /(?:^|\n\n)\((?:Omitted \d+ bytes\.|More bytes were omitted\.|)(?: Omitted [1-9]\d* images\.)? Full formatted result stored at: [^\n]*\)$/

/**
 * bash、pwsh 工具自己截断输出时，在被截断的那一段后面加的一行（dsh-tool-bash / dsh-tool-pwsh 的 streamText）：
 * 「[output truncated; full output: <路径>]」。它只保留输出的末尾。
 */
const STREAM_NOTICE = /\n\[output truncated; full output: [^\n]*\]$/gm

/**
 * 去掉 DSH 附在输出里的截断提示（里面是电脑上的路径，不发给手机），返回剩下的内容和是否截断过（protocol.md 5.2）：
 * 最后的 spill 提示，以及 bash、pwsh 每段输出后面的「[output truncated; full output: …]」。
 * 通用截断策略连接开头和末尾的「[...]」本身就说明这里省略了内容，保留。
 * @param {string} text
 * @returns {{ text: string, saved: boolean }}
 */
export function stripSpillNotice(text) {
  let saved = false
  const match = SPILL_NOTICE.exec(text)
  if (match) {
    text = text.slice(0, match.index)
    saved = true
  }
  const without = text.replace(STREAM_NOTICE, '')
  if (without !== text) saved = true
  return { text: without, saved }
}

/** 在 index 处切开，但不切开代理对：index 落在一对的中间时往后挪一位。 @param {string} text @param {number} index */
function safeCut(text, index) {
  const code = text.charCodeAt(index - 1)
  return code >= 0xd800 && code <= 0xdbff ? index + 1 : index
}

/** @param {string} text @param {number} limit */
function truncate(text, limit) {
  if (text.length <= limit) return { text, truncated: false }
  return { text: `${text.slice(0, limit)}…`, truncated: true }
}

/** @param {string} text */
const firstLine = (text) => text.split(/\r?\n/, 1)[0] ?? ''

/**
 * 把模型给出的工具参数（JSON 字符串）解析成对象，并截断过长的字符串字段。
 * @param {unknown} raw
 * @returns {{ input: Record<string, unknown>, truncated: boolean }}
 */
export function parseToolInput(raw) {
  /** @type {unknown} */
  let parsed
  try { parsed = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { parsed = undefined }
  if (!isObject(parsed)) return { input: {}, truncated: typeof raw === 'string' && raw.length > 0 }
  let truncated = false
  /** @param {unknown} value @param {number} depth @returns {unknown} */
  const clip = (value, depth) => {
    if (typeof value === 'string') {
      const t = truncate(value, TOOL_INPUT_STRING_LIMIT)
      if (t.truncated) truncated = true
      return t.text
    }
    if (depth > 4) { truncated = true; return null }
    if (Array.isArray(value)) return value.slice(0, 50).map((v) => clip(v, depth + 1))
    if (isObject(value)) return Object.fromEntries(Object.entries(value).slice(0, 50).map(([k, v]) => [k, clip(v, depth + 1)]))
    return value
  }
  return { input: /** @type {Record<string, unknown>} */ (clip(parsed, 0)), truncated }
}

/**
 * 给工具调用生成一行人能看懂的摘要。不认识的工具只显示工具名。
 * @param {string} name @param {Record<string, unknown>} input
 */
export function toolSummary(name, input) {
  const str = (/** @type {string} */ key) => (typeof input[key] === 'string' ? String(input[key]) : undefined)
  /** @type {string | undefined} */
  let summary
  switch (name) {
    case 'bash':
    case 'pwsh':
      summary = str('command') === undefined ? undefined : `运行命令：${firstLine(str('command') ?? '')}`
      break
    case 'read':
    case 'read_image':
      summary = str('file_path') && `读取：${str('file_path')}`
      break
    case 'write':
      summary = str('file_path') && `写入：${str('file_path')}`
      break
    case 'edit':
      summary = str('file_path') && `编辑：${str('file_path')}`
      break
    case 'grep':
    case 'glob':
      summary = str('pattern') && `搜索：${str('pattern')}`
      break
    case 'web_fetch':
      summary = str('url') && `打开网页：${str('url')}`
      break
    case 'web_search':
      summary = str('query') && `搜索网页：${str('query')}`
      break
    case 'ask_user_question': {
      const first = Array.isArray(input.questions) && isObject(input.questions[0]) ? input.questions[0].question : undefined
      summary = typeof first === 'string' ? `提问：${first}` : '提问'
      break
    }
    default:
      summary = undefined
  }
  return truncate(firstLine(summary || name), SUMMARY_LIMIT).text
}

/** @param {unknown} content @returns {string} */
function toolResultText(content) {
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content.map((block) => {
    if (isObject(block) && block.type === 'text' && typeof block.text === 'string') return block.text
    if (isObject(block) && block.type === 'image') return '[图片]'
    return ''
  }).filter(Boolean).join('\n')
}

/**
 * 用户消息的内容块：文本原样保留，图片和文件用占位文字表示（v1 不传输附件）。
 * @param {unknown} content @returns {MessagePart[]}
 */
function userParts(content) {
  if (!Array.isArray(content)) return []
  /** @type {MessagePart[]} */
  const parts = []
  for (const block of content) {
    if (!isObject(block)) continue
    if (block.type === 'text' && typeof block.text === 'string' && block.text !== '') parts.push({ type: 'text', text: block.text })
    else if (block.type === 'image') parts.push({ type: 'text', text: '[图片]' })
    else if (block.type === 'file') {
      const name = isObject(block.attachment) && typeof block.attachment.name === 'string' ? block.attachment.name : ''
      parts.push({ type: 'text', text: name ? `[文件：${name}]` : '[文件]' })
    }
  }
  return parts
}

/**
 * 把一段连续的会话日志转换成消息列表。
 *
 * 同一段日志里，tool/result 和 approval/decided 会更新前面 tool 部件的状态。
 * 工具调用没有结果、而本轮已经结束（例如被用户停止）时，状态记为 error。
 * @param {readonly unknown[]} records
 * @param {number} [startTurn] 这段日志开始之前最近一个带 turn 的事件所在的轮次（这段日志里推断不出时用它）
 * @returns {Message[]}
 */
export function convertRecords(records, startTurn) {
  /** @type {Message[]} */
  const messages = []
  /** @type {Map<string, ToolPart>} 本段日志中还没有结果的工具调用 */
  const pendingTools = new Map()
  /** @type {Map<string, string>} 审批 id → callId */
  const approvalCalls = new Map()
  /** @type {Set<string>} 审批被拒绝的 callId */
  const rejectedCalls = new Set()
  /** 当前所在的轮次。一页开头的消息所属的轮次由这一页第一个轮次事件推断 */
  let currentTurn = leadingTurn(records) ?? startTurn

  for (const record of records) {
    if (!isObject(record) || record.type !== 'event' || !isObject(record.event)) continue
    const event = record.event
    const data = event.data
    if (!isObject(data)) continue
    const time = typeof event.time === 'number' ? event.time : 0
    // 用户消息不带 turn：跟着它前面最近一个带 turn 的事件（同一轮的 turn/start、step/start）
    currentTurn = turnOf(record) ?? currentTurn

    switch (event.type) {
      case 'user/message': {
        // 只要用户亲自输入的内容；运行时上下文、时间上下文等由 DSH 注入的消息不显示
        if (!isObject(data.source) || data.source.kind !== 'user') break
        if (event.surfaceOp !== undefined && event.surfaceOp !== 'append') break
        const parts = userParts(data.content)
        if (typeof data.id === 'string' && parts.length > 0) messages.push(withTurn({ id: data.id, role: 'user', parts, createdAt: time }, currentTurn))
        break
      }

      case 'assistant/message': {
        const message = data.message
        if (!isObject(message) || typeof message.id !== 'string' || !Array.isArray(message.content)) break
        /** @type {MessagePart[]} */
        const parts = []
        for (const block of message.content) {
          if (!isObject(block)) continue
          if (block.type === 'text' && typeof block.text === 'string' && block.text !== '') parts.push({ type: 'text', text: block.text })
          else if (block.type === 'reasoning' && typeof block.text === 'string' && block.text !== '') parts.push({ type: 'thinking', text: block.text })
          else if (block.type === 'tool-call' && typeof block.id === 'string' && typeof block.name === 'string') {
            const { input, truncated } = parseToolInput(block.arguments)
            /** @type {ToolPart} */
            const part = { type: 'tool', callId: block.id, name: block.name, summary: toolSummary(block.name, input), status: 'running', input }
            if (truncated) part.truncated = true
            parts.push(part)
            pendingTools.set(block.id, part)
          }
        }
        if (parts.length > 0) messages.push(withTurn({ id: message.id, role: 'assistant', parts, createdAt: time }, isTurnNumber(data.turn) ? data.turn : currentTurn))
        break
      }
      case 'approval/asked':
        if (typeof data.id === 'string' && typeof data.callId === 'string') approvalCalls.set(data.id, data.callId)
        break
      case 'approval/decided': {
        const callId = typeof data.id === 'string' ? approvalCalls.get(data.id) : undefined
        if (callId && data.outcome === 'rejected') rejectedCalls.add(callId)
        break
      }
      case 'tool/result': {
        const message = data.message
        if (!isObject(message)) break
        const callId = typeof message.toolCallId === 'string' ? message.toolCallId : undefined
        const part = callId ? pendingTools.get(callId) : undefined
        if (!callId || !part) break
        const { text, saved } = stripSpillNotice(toolResultText(message.content))
        if (saved) { part.outputSaved = true; part.truncated = true }
        if (COMMAND_TOOLS.has(part.name)) {
          const output = clipCommandOutput(text)
          part.output = output.text
          if (output.omitted) { part.truncated = true; part.omitted = output.omitted }
        } else {
          const output = truncate(text, TOOL_OUTPUT_LIMIT)
          part.output = output.text
          if (output.truncated) part.truncated = true
        }
        part.status = message.isError === true ? (rejectedCalls.has(callId) ? 'rejected' : 'error') : 'done'
        pendingTools.delete(callId)
        break
      }
      case 'turn/end':
        for (const part of pendingTools.values()) part.status = 'error'
        pendingTools.clear()
        break
      default:
        // 其他事件（请求头、遥测、标题、任务登记等）不转发给 App
        break
    }
  }
  return messages
}

/** @param {unknown} value @returns {value is number} */
const isTurnNumber = (value) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 1

/** @param {Message} message @param {number | undefined} turn @returns {Message} */
const withTurn = (message, turn) => (turn === undefined ? message : { ...message, turn })

/**
 * 一页开头的消息属于哪一轮。DSH 的大多数事件（step/start、tool/call、assistant/message、turn/end……）都带 turn，
 * 只有 user/message 不带：按这一页第一个带 turn 的事件推断，它是 turn/start N 时是第 N-1 轮，其他事件就是第 N 轮。
 * 一个都没有时无法确定。
 * @param {readonly unknown[]} records
 * @returns {number | undefined}
 */
function leadingTurn(records) {
  for (const record of records) {
    const turn = turnOf(record)
    if (turn === undefined) continue
    if (isObject(record) && isObject(record.event) && record.event.type === 'turn/start') return turn > 1 ? turn - 1 : undefined
    return turn
  }
  return undefined
}

/**
 * 一段日志里最后一个带 turn 的事件所在的轮次。用来补上下一页开头的消息所属的轮次：
 * 那一页以用户消息开头、里面又没有任何带 turn 的事件时，只能从更早的日志里得知。
 * @param {readonly unknown[]} records
 * @returns {number | undefined}
 */
export function turnBefore(records) {
  for (let i = records.length - 1; i >= 0; i--) {
    const turn = turnOf(records[i])
    if (turn !== undefined) return turn
  }
  return undefined
}

/** 事件自带的轮次。 @param {unknown} record @returns {number | undefined} */
function turnOf(record) {
  if (!isObject(record) || !isObject(record.event) || !isObject(record.event.data)) return undefined
  const turn = record.event.data.turn
  return isTurnNumber(turn) ? turn : undefined
}

/**
 * DSH 的 turn/end 结束原因 → protocol.md 5.4 的 result。DSH 0.2.2 的原因见
 * dsh-session 的 TurnEndReasonMap；它允许插件扩展，不认识的一律是 other。
 * @param {unknown} reason
 * @returns {Pick<Turn, 'result' | 'error' | 'stoppedBy'>}
 */
export function turnResult(reason) {
  const kind = isObject(reason) ? reason.kind : undefined
  switch (kind) {
    case 'completed':
    case 'max-tokens':
    case 'forked':
      return { result: 'completed' }
    case 'aborted': {
      const cause = isObject(reason) && isObject(reason.reason) ? reason.reason.kind : undefined
      return { result: 'stopped', stoppedBy: cause === 'user' ? 'user' : 'other' }
    }
    case 'error': {
      const error = isObject(reason) && isObject(reason.error) ? reason.error : {}
      const text = typeof error.message === 'string' && error.message.trim() !== '' ? error.message : typeof error.code === 'string' ? error.code : ''
      const clean = text.replace(/\s+/g, ' ').trim()
      return clean === '' ? { result: 'failed' } : { result: 'failed', error: truncate(clean, TURN_ERROR_LIMIT).text }
    }
    case 'interrupted':
      return { result: 'interrupted' }
    default:
      return { result: 'other' }
  }
}

/**
 * 从一段会话日志里得出各轮的起止和结果（protocol.md 5.4），按轮次编号排序。
 * @param {readonly unknown[]} records
 * @returns {Turn[]}
 */
export function extractTurns(records) {
  /** @type {Map<number, Turn>} */
  const turns = new Map()
  /** 从 turn/start 起收集的事件，到 turn/end 时算这一轮的用量 @type {{ turn: number, events: unknown[] } | undefined} */
  let open
  for (const record of records) {
    if (!isObject(record) || record.type !== 'event' || !isObject(record.event)) continue
    if (open) open.events.push(record.event)
    if (!isObject(record.event.data)) continue
    const { type, data } = record.event
    if ((type !== 'turn/start' && type !== 'turn/end') || !isTurnNumber(data.turn)) continue
    const time = typeof record.event.time === 'number' ? record.event.time : undefined
    const turn = turns.get(data.turn) ?? { turn: data.turn }
    if (type === 'turn/start') {
      if (time !== undefined) turn.startedAt = time
      open = { turn: data.turn, events: [record.event] }
    } else {
      if (time !== undefined) turn.endedAt = time
      Object.assign(turn, turnResult(data.reason))
      const usage = open?.turn === data.turn ? turnUsage(open.events) : undefined
      if (usage) turn.usage = usage
      open = undefined
    }
    turns.set(data.turn, turn)
  }
  return [...turns.values()].sort((a, b) => a.turn - b.turn)
}

/**
 * 一轮的用量：用 DSH 官方界面的算法（src/vendor/dsh-turn-usage.mjs）。事件不完整、数字对不上时没有，
 * 不给不完整的小计（protocol.md 5.4）。只转发协议里的几项。
 * @param {readonly unknown[]} events 这一轮从 turn/start 到 turn/end 的事件
 * @returns {TurnUsage | undefined}
 */
export function turnUsage(events) {
  let usage
  try {
    usage = deriveTurnTokenUsage(events)
  } catch {
    // 事件格式和这个版本的算法对不上（DSH 升级后可能出现）：这一轮不给用量
    return undefined
  }
  if (!usage) return undefined
  /** @type {TurnUsage} */
  const result = { totalTokens: usage.totalTokens, uncachedInputTokens: usage.uncachedInputTokens, outputTokens: usage.outputTokens }
  if (usage.cacheReadTokens !== undefined) result.cacheReadTokens = usage.cacheReadTokens
  if (usage.cacheWriteTokens !== undefined) result.cacheWriteTokens = usage.cacheWriteTokens
  return result
}

/**
 * 会话摘要里的最近一轮（protocol.md 5.1）：最后一个已经结束的轮次，和那一轮模型最后一段回复的开头。
 * @param {readonly unknown[]} records 会话日志的末尾（最新一页）
 * @returns {{ lastTurn: { result: NonNullable<Turn['result']>, endedAt: number | null, error?: string } | null, lastReply: string | null }}
 */
export function turnDigest(records) {
  const ended = extractTurns(records).filter((t) => t.result !== undefined)
  const last = ended[ended.length - 1]
  if (!last || !last.result) return { lastTurn: null, lastReply: null }
  /** @type {{ result: NonNullable<Turn['result']>, endedAt: number | null, error?: string }} */
  const lastTurn = { result: last.result, endedAt: last.endedAt ?? null }
  if (last.error) lastTurn.error = last.error
  let reply = null
  for (const message of convertRecords(records)) {
    if (message.role !== 'assistant' || message.turn !== last.turn) continue
    for (const part of message.parts) if (part.type === 'text' && part.text.trim() !== '') reply = part.text
  }
  const clean = reply?.replace(/\s+/g, ' ').trim()
  return { lastTurn, lastReply: clean ? truncate(clean, LAST_REPLY_LIMIT).text : null }
}

/**
 * 可以作为一页开头的事件：从这里开始，后面的工具调用都能找到它们所属的模型回复。
 * @param {unknown} record
 */
function isPageBoundary(record) {
  if (!isObject(record) || !isObject(record.event)) return false
  const type = record.event.type
  return type === 'turn/start' || type === 'user/message' || type === 'assistant/message'
}

/** @param {unknown} record */
const seqOf = (record) => (isObject(record) && isObject(record.event) && typeof record.event.seq === 'number' ? record.event.seq : undefined)

/**
 * 转换 session/page 返回的一页历史。
 *
 * DSH 按消息数切页，切点可能落在一次工具调用中间：这一页开头的 tool/call、tool/result
 * 所属的模型回复在更早的一页里。这里把开头这些「孤儿」事件去掉，并把翻页游标设在第一个
 * 完整的边界上——下一次用这个游标向前翻页时，这些事件会和它们的模型回复一起返回。
 * @param {{ records?: unknown, hasMore?: unknown }} page
 * @param {number} [startTurn] 这一页之前最近一个带 turn 的事件所在的轮次，见 {@link turnBefore}
 * @returns {{ messages: Message[], hasMore: boolean, cursor: string | null, turns: Turn[] }}
 */
export function convertPage(page, startTurn) {
  const records = Array.isArray(page.records) ? page.records : []
  const hasMore = page.hasMore === true
  if (!hasMore) return { messages: convertRecords(records, startTurn), hasMore: false, cursor: null, turns: extractTurns(records) }
  const boundary = records.findIndex(isPageBoundary)
  // 整页都没有边界（极少见：一长串工具调用）时不裁剪，避免这些事件永远显示不出来
  const start = boundary > 0 ? boundary : 0
  const cursorSeq = seqOf(records[start])
  const kept = records.slice(start)
  return {
    messages: convertRecords(kept, startTurn),
    hasMore: cursorSeq === undefined ? false : cursorSeq > 0,
    cursor: cursorSeq === undefined ? null : String(cursorSeq),
    turns: extractTurns(kept),
  }
}
