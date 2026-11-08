// @ts-check
/**
 * 假的 Anthropic Messages 服务，按剧本回复，用来驱动 DSH 调用工具。
 * 改编自 docs/research/probe-0.2.2-rc.1/fake-llm.mjs。
 *
 * 剧本由最后一条用户消息里的关键词决定：
 *   "ASK"   → 调用 ask_user_question
 *   "SHELL" → 调用 shell 工具（名称从请求的 tools 里自动找）
 *   "SLOW"  → 慢慢输出很长的文本，用来测试取消
 *   其他     → 直接回复一段文本（包含 FAKE_REPLY）
 * 收到 tool_result 后回复一段文本结束本轮。
 */
import http from 'node:http'

export const FAKE_REPLY = '你好，这是假模型的回复。'
export const TOOL_DONE_REPLY = '已完成，结果收到了。'
export const SHELL_MARKER_FILE = 'contract-output.txt'

/** @param {http.ServerResponse} res @param {[string, unknown][]} events @param {number} [delayMs] */
async function sse(res, events, delayMs = 0) {
  res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
  for (const [event, data] of events) {
    if (res.destroyed) return
    res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    if (delayMs) await new Promise((r) => setTimeout(r, delayMs))
  }
  res.end()
}

/** @param {string} id @returns {[string, unknown]} */
const messageStart = (id) => ['message_start', { type: 'message_start', message: { id, type: 'message', role: 'assistant', model: 'fake', content: [], stop_reason: null, usage: { input_tokens: 10, output_tokens: 0 } } }]

/** @param {http.ServerResponse} res @param {string} text @param {number} [delayMs] */
function textReply(res, text, delayMs = 0) {
  const chunks = text.match(/.{1,6}/gsu) ?? [text]
  return sse(res, [
    messageStart('msg_text'),
    ['content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } }],
    ...chunks.map((c) => /** @type {[string, unknown]} */ (['content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: c } }])),
    ['content_block_stop', { type: 'content_block_stop', index: 0 }],
    ['message_delta', { type: 'message_delta', delta: { stop_reason: 'end_turn' }, usage: { output_tokens: 5 } }],
    ['message_stop', { type: 'message_stop' }],
  ], delayMs)
}

/** @param {http.ServerResponse} res @param {string} name @param {unknown} input */
function toolReply(res, name, input) {
  return sse(res, [
    messageStart('msg_tool'),
    ['content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } }],
    ['content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: '我先确认一下。' } }],
    ['content_block_stop', { type: 'content_block_stop', index: 0 }],
    ['content_block_start', { type: 'content_block_start', index: 1, content_block: { type: 'tool_use', id: `toolu_${Date.now()}`, name, input: {} } }],
    ['content_block_delta', { type: 'content_block_delta', index: 1, delta: { type: 'input_json_delta', partial_json: JSON.stringify(input) } }],
    ['content_block_stop', { type: 'content_block_stop', index: 1 }],
    ['message_delta', { type: 'message_delta', delta: { stop_reason: 'tool_use' }, usage: { output_tokens: 20 } }],
    ['message_stop', { type: 'message_stop' }],
  ])
}

/**
 * @returns {Promise<{ url: string, requests: () => number, diagnostics: () => { tools: string[], lastToolResult: string }, close: () => Promise<void> }>}
 */
export async function startFakeLlm() {
  let requests = 0
  /** @type {string[]} */
  let lastTools = []
  let lastToolResult = ''
  const server = http.createServer((req, res) => {
    let body = ''
    req.on('data', (d) => { body += d })
    req.on('end', () => {
      const url = req.url ?? ''
      if (req.method === 'GET' && url.endsWith('/models')) {
        res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ data: [] })); return
      }
      if (!url.endsWith('/messages')) { res.writeHead(404); res.end(); return }
      requests++
      /** @type {any} */
      let parsed
      try { parsed = JSON.parse(body) } catch { res.writeHead(400); res.end(); return }
      /** @type {string[]} */
      const tools = (parsed.tools ?? []).map((/** @type {{ name: string }} */ t) => t.name)
      /** @type {any[]} */
      const messages = parsed.messages ?? []
      const last = messages.at(-1)
      const lastBlocks = Array.isArray(last?.content) ? last.content : [{ type: 'text', text: String(last?.content ?? '') }]
      lastTools = tools
      const toolResults = lastBlocks.filter((/** @type {{ type: string }} */ b) => b.type === 'tool_result')
      if (toolResults.length > 0) {
        lastToolResult = JSON.stringify(toolResults).slice(0, 600)
        void textReply(res, TOOL_DONE_REPLY)
        return
      }
      const text = JSON.stringify(messages.findLast((m) => m.role === 'user')?.content ?? '')
      if (text.includes('ASK')) {
        void toolReply(res, 'ask_user_question', { questions: [{ id: 'q1', question: '用哪种方案？', header: '选择', options: [{ label: '方案A' }, { label: '方案B' }], multi_select: false }] })
      } else if (text.includes('SHELL')) {
        // Linux 上是 bash，Windows 上是 pwsh
        const shell = tools.find((n) => /^(bash|pwsh)$/.test(n)) ?? tools.find((n) => /bash|pwsh|powershell|shell|exec|command/i.test(n)) ?? 'bash'
        void toolReply(res, shell, {
          command: `echo dshremote-contract > ${SHELL_MARKER_FILE}`,
          description: 'contract write',
          sandbox_permissions: 'danger-full-access',
          justification: '验证审批流程',
        })
      } else if (text.includes('SLOW')) {
        void textReply(res, '慢'.repeat(600), 100)
      } else {
        void textReply(res, FAKE_REPLY)
      }
    })
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(undefined)))
  const address = /** @type {import('node:net').AddressInfo} */ (server.address())
  return {
    url: `http://127.0.0.1:${address.port}`,
    requests: () => requests,
    // 检查失败时用来定位原因：DSH 提供了哪些工具、最近一次工具结果是什么
    diagnostics: () => ({ tools: lastTools, lastToolResult }),
    close: () => new Promise((resolve) => { server.closeAllConnections(); server.close(() => resolve()) }),
  }
}
