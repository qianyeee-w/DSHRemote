// 假的 Anthropic Messages 服务：按剧本回复，用来驱动 DSH 调用工具。
// 剧本由用户消息里的关键词决定：
//   "ASK"   → 调用 ask_user_question
//   "SHELL" → 调用 shell 工具（名称从请求的 tools 里自动找）
//   其他     → 直接回复文本
// 收到 tool_result 后回复一段文本结束本轮。
import http from 'node:http'
import fs from 'node:fs'

const PORT = Number(process.env.FAKE_LLM_PORT ?? 9911)
const LOG = process.env.FAKE_LLM_LOG ?? './fake-llm.log'
const log = (...a) => fs.appendFileSync(LOG, a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ') + '\n')

function sse(res, events) {
  res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
  for (const [event, data] of events) res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
  res.end()
}

const msgStart = (id) => ['message_start', { type: 'message_start', message: { id, type: 'message', role: 'assistant', model: 'fake', content: [], stop_reason: null, usage: { input_tokens: 10, output_tokens: 0 } } }]

function textReply(res, text) {
  const chunks = text.match(/.{1,6}/gsu) ?? [text]
  sse(res, [
    msgStart('msg_text'),
    ['content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } }],
    ...chunks.map(c => ['content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: c } }]),
    ['content_block_stop', { type: 'content_block_stop', index: 0 }],
    ['message_delta', { type: 'message_delta', delta: { stop_reason: 'end_turn' }, usage: { output_tokens: 5 } }],
    ['message_stop', { type: 'message_stop' }],
  ])
}

function toolReply(res, name, input) {
  const json = JSON.stringify(input)
  sse(res, [
    msgStart('msg_tool'),
    ['content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } }],
    ['content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: '我先确认一下。' } }],
    ['content_block_stop', { type: 'content_block_stop', index: 0 }],
    ['content_block_start', { type: 'content_block_start', index: 1, content_block: { type: 'tool_use', id: `toolu_${Date.now()}`, name, input: {} } }],
    ['content_block_delta', { type: 'content_block_delta', index: 1, delta: { type: 'input_json_delta', partial_json: json } }],
    ['content_block_stop', { type: 'content_block_stop', index: 1 }],
    ['message_delta', { type: 'message_delta', delta: { stop_reason: 'tool_use' }, usage: { output_tokens: 20 } }],
    ['message_stop', { type: 'message_stop' }],
  ])
}

http.createServer((req, res) => {
  let body = ''
  req.on('data', d => { body += d })
  req.on('end', () => {
    log('REQ', req.method, req.url)
    if (req.method === 'GET' && req.url.endsWith('/models')) {
      res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ data: [] })); return
    }
    if (!req.url.endsWith('/messages')) { res.writeHead(404); res.end(); return }
    let parsed
    try { parsed = JSON.parse(body) } catch { res.writeHead(400); res.end(); return }
    const tools = (parsed.tools ?? []).map(t => t.name)
    log('TOOLS', tools)
    const messages = parsed.messages ?? []
    const last = messages.at(-1)
    const lastBlocks = Array.isArray(last?.content) ? last.content : [{ type: 'text', text: String(last?.content ?? '') }]
    if (lastBlocks.some(b => b.type === 'tool_result')) {
      log('TOOL_RESULT', lastBlocks.filter(b => b.type === 'tool_result'))
      textReply(res, '已完成，结果收到了。')
      return
    }
    const firstUser = messages.findLast(m => m.role === 'user')
    const text = JSON.stringify(firstUser?.content ?? '')
    if (text.includes('ASK')) {
      toolReply(res, 'ask_user_question', { questions: [{ id: 'q1', question: '用哪种方案？', header: '选择', options: [{ label: '方案A' }, { label: '方案B' }], multi_select: false }] })
    } else if (text.includes('SHELL')) {
      const shell = tools.find(n => /bash|shell|exec|command/i.test(n)) ?? 'bash'
      toolReply(res, shell, { command: 'echo dshremote-probe > probe-output.txt && cat probe-output.txt', description: 'probe write', sandbox_permissions: 'danger-full-access', justification: '验证审批流程' })
    } else {
      textReply(res, '你好，这是假模型的回复，用于验证流式输出。')
    }
  })
}).listen(PORT, '127.0.0.1', () => log('fake llm listening', PORT))
