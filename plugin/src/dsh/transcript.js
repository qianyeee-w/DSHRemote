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

/** 工具输入中单个字符串字段的上限（字符） */
export const TOOL_INPUT_STRING_LIMIT = 2_000
/** 工具输出的上限（字符） */
export const TOOL_OUTPUT_LIMIT = 8_000
/** 工具摘要的上限（字符） */
const SUMMARY_LIMIT = 120

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
 */

/**
 * @typedef {object} Message
 * @property {string} id
 * @property {'user' | 'assistant'} role
 * @property {MessagePart[]} parts
 * @property {number} createdAt
 */

/**
 * DSH 会话日志中的一条记录（session/page 的 records、session/follow 的 event 帧）。
 * @typedef {{ type: 'event', event: { type: string, seq: number, time: number, data: any, surfaceOp?: unknown } }} DshRecord
 */

/** @param {unknown} value @returns {value is Record<string, any>} */
const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value)

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
function parseToolInput(raw) {
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
 * @returns {Message[]}
 */
export function convertRecords(records) {
  /** @type {Message[]} */
  const messages = []
  /** @type {Map<string, ToolPart>} 本段日志中还没有结果的工具调用 */
  const pendingTools = new Map()
  /** @type {Map<string, string>} 审批 id → callId */
  const approvalCalls = new Map()
  /** @type {Set<string>} 审批被拒绝的 callId */
  const rejectedCalls = new Set()

  for (const record of records) {
    if (!isObject(record) || record.type !== 'event' || !isObject(record.event)) continue
    const event = record.event
    const data = event.data
    if (!isObject(data)) continue
    const time = typeof event.time === 'number' ? event.time : 0

    switch (event.type) {
      case 'user/message': {
        // 只要用户亲自输入的内容；运行时上下文、时间上下文等由 DSH 注入的消息不显示
        if (!isObject(data.source) || data.source.kind !== 'user') break
        if (event.surfaceOp !== undefined && event.surfaceOp !== 'append') break
        const parts = userParts(data.content)
        if (typeof data.id === 'string' && parts.length > 0) messages.push({ id: data.id, role: 'user', parts, createdAt: time })
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
        if (parts.length > 0) messages.push({ id: message.id, role: 'assistant', parts, createdAt: time })
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
        const output = truncate(toolResultText(message.content), TOOL_OUTPUT_LIMIT)
        part.output = output.text
        if (output.truncated) part.truncated = true
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
 * @returns {{ messages: Message[], hasMore: boolean, cursor: string | null }}
 */
export function convertPage(page) {
  const records = Array.isArray(page.records) ? page.records : []
  const hasMore = page.hasMore === true
  if (!hasMore) return { messages: convertRecords(records), hasMore: false, cursor: null }
  const boundary = records.findIndex(isPageBoundary)
  // 整页都没有边界（极少见：一长串工具调用）时不裁剪，避免这些事件永远显示不出来
  const start = boundary > 0 ? boundary : 0
  const cursorSeq = seqOf(records[start])
  return {
    messages: convertRecords(records.slice(start)),
    hasMore: cursorSeq === undefined ? false : cursorSeq > 0,
    cursor: cursorSeq === undefined ? null : String(cursorSeq),
  }
}
