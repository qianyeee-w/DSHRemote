// @ts-check
/**
 * 假的 Anthropic Messages 服务，按剧本回复，用来驱动 DSH 调用工具。
 * 改编自 docs/research/probe-0.2.2-rc.1/fake-llm.mjs。
 *
 * 剧本由最后一条用户消息里的关键词决定：
 *   "ASK"   → 调用 ask_user_question
 *   "SHELL" → 调用 shell 工具（名称从请求的 tools 里自动找）
 *   "SLOW"  → 慢慢输出很长的文本，用来测试取消
 *   "THINK" → 先输出一段思考（thinking），再回复文本
 *   "STREAM"→ 用约 1 秒分块输出一段文本（STREAM_REPLY），用来观察流式输出
 *   "MARKDOWN" → 分块慢慢输出一段包含标题、列表、引用、长代码块、宽表格和链接的 Markdown（MARKDOWN_REPLY），
 *               用来在手机上检查 Markdown 渲染和流式输出时是否闪烁
 *   其他     → 直接回复一段文本（包含 FAKE_REPLY）
 * 收到 tool_result 后回复一段文本结束本轮。
 */
import http from 'node:http'

export const FAKE_REPLY = '你好，这是假模型的回复。'
export const STREAM_REPLY = '这是一段分块慢慢输出的回复，用来验证手机上能实时看到模型正在输出的内容。'
export const TOOL_DONE_REPLY = '已完成，结果收到了。'
export const MARKDOWN_REPLY = `# Markdown 测试

这一段有 **粗体**、*斜体*、\`行内代码\` 和一个链接：[示例网站](https://example.com/dshremote)。

## 列表

1. 第一项
2. 第二项，带一个很长很长的说明，用来看手机上自动换行的效果是否正常
   - 嵌套的子项
   - 另一个子项

> 引用：手机上应该能看清楚这段引用的样式。

## 代码块

\`\`\`kotlin
fun main() {
    val message = "这是一行非常长的代码，用来测试代码块能不能横向滚动，而不是被强行换行或者截断显示"
    repeat(3) { index -> println("$index: $message") }
}
\`\`\`

## 宽表格

| 编号 | 名称 | 状态 | 负责人 | 开始时间 | 结束时间 | 备注 | 链接 |
|---|---|---|---|---|---|---|---|
| 1 | 配对 | 完成 | 甲 | 11-01 | 11-03 | 扫码和手动输入都支持 | https://example.com/1 |
| 2 | 会话列表 | 完成 | 乙 | 11-04 | 11-05 | 按状态排序 | https://example.com/2 |
| 3 | 会话详情 | 进行中 | 丙 | 11-06 | — | 流式输出和 Markdown | https://example.com/3 |

最后一段：以上内容都应该能长按选中和复制。
`
export const SHELL_MARKER_FILE = 'contract-output.txt'
/**
 * DSH 每轮追加在用户消息后面的上下文（DSH 0.2.2-rc.1 实测的开头）。
 * @param {string} text
 */
function isInjectedContext(text) {
  return text.startsWith('Current runtime context.') || text.startsWith('Time sampled while preparing turn')
}

/** FAIL 关键词让假模型返回的错误说明 */
export const FAIL_MESSAGE = 'fake failure for DSHRemote tests'

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

/** @param {http.ServerResponse} res @param {string} thinking @param {string} text */
function thinkingReply(res, thinking, text) {
  return sse(res, [
    messageStart('msg_think'),
    ['content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'thinking', thinking: '', signature: '' } }],
    ['content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking } }],
    ['content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'signature_delta', signature: 'sig' } }],
    ['content_block_stop', { type: 'content_block_stop', index: 0 }],
    ['content_block_start', { type: 'content_block_start', index: 1, content_block: { type: 'text', text: '' } }],
    ['content_block_delta', { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text } }],
    ['content_block_stop', { type: 'content_block_stop', index: 1 }],
    ['message_delta', { type: 'message_delta', delta: { stop_reason: 'end_turn' }, usage: { output_tokens: 8 } }],
    ['message_stop', { type: 'message_stop' }],
  ])
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
 * @returns {Promise<{ url: string, requests: () => number, diagnostics: () => { tools: string[], lastToolResult: string, lastUserText: string }, close: () => Promise<void> }>}
 */
export async function startFakeLlm() {
  let requests = 0
  /** @type {string[]} */
  let lastTools = []
  let lastToolResult = ''
  let lastUserText = ''
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
      lastUserText = text.slice(0, 300)
      // 只看用户自己的最后一段文字：上一轮没有完整回复（被停止、失败）时，DSH 会把两条用户消息合在一起发过来；
      // 每轮还会在后面追加运行时上下文、时间上下文（会话的第一轮两段都有）
      const userBlocks = messages.findLast((m) => m.role === 'user')?.content
      const lastText = Array.isArray(userBlocks)
        ? String(userBlocks.findLast((/** @type {any} */ b) => b?.type === 'text' && !isInjectedContext(String(b.text ?? '')))?.text ?? '')
        : String(userBlocks ?? '')
      if (lastText.includes('FAIL')) {
        // 不可重试的错误（DSH 归为 INVALID_REQUEST）：这一轮以 error 结束（protocol.md 5.4 的 failed）
        res.writeHead(400, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ type: 'error', error: { type: 'invalid_request_error', message: FAIL_MESSAGE } }))
      } else if (text.includes('ASK')) {
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
      } else if (text.includes('MARKDOWN')) {
        void textReply(res, MARKDOWN_REPLY, 30)
      } else if (text.includes('STREAM')) {
        void textReply(res, STREAM_REPLY, 80)
      } else if (text.includes('THINK')) {
        void thinkingReply(res, '用户想让我想一想。', '想好了。')
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
    diagnostics: () => ({ tools: lastTools, lastToolResult, lastUserText }),
    close: () => new Promise((resolve) => { server.closeAllConnections(); server.close(() => resolve()) }),
  }
}
