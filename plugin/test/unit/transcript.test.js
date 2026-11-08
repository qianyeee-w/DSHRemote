// @ts-check
/**
 * 历史消息转换器。测试数据是从真实 dsh 0.2.2-rc.1 录制的（npm run capture-fixtures），
 * 会话内容：普通对话 → 思考 → 提问 → 批准的命令 → 拒绝的命令 → 被停止的长回复。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { convertRecords, convertPage, toolSummary, TOOL_OUTPUT_LIMIT, TOOL_INPUT_STRING_LIMIT } from '../../src/dsh/transcript.js'

/** @param {string} name */
const fixture = (name) => JSON.parse(readFileSync(new URL(`../fixtures/dsh-0.2.2-rc.1/${name}`, import.meta.url), 'utf8'))
/** @type {{ records: any[], hasMore: boolean }} */
const full = fixture('session-page-full.json')

/** @param {import('../../src/dsh/transcript.js').Message} m */
const shape = (m) => `${m.role}:${m.parts.map((p) => (p.type === 'tool' ? `tool(${p.name},${p.status})` : p.type)).join('+')}`

test('完整会话：只保留用户输入和模型回复，按顺序转换', () => {
  const messages = convertRecords(full.records)
  assert.deepEqual(messages.map(shape), [
    'user:text', 'assistant:text', // 你好
    'user:text', 'assistant:thinking+text', // THINK
    'user:text', 'assistant:text+tool(ask_user_question,done)', 'assistant:text', // ASK
    'user:text', 'assistant:text+tool(bash,done)', 'assistant:text', // 批准的命令
    'user:text', 'assistant:text+tool(bash,rejected)', 'assistant:text', // 拒绝的命令
    'user:text', 'assistant:text', // 被停止的长回复
  ])
  const texts = messages.filter((m) => m.role === 'user').map((m) => m.parts[0]?.type === 'text' ? m.parts[0].text : '')
  assert.deepEqual(texts, ['你好', 'THINK 一下', '请 ASK 我一个问题', '请执行 SHELL 命令', '请再执行一次 SHELL 命令', 'SLOW'])
  // 运行时上下文、时间上下文、系统提示都不出现
  const all = JSON.stringify(messages)
  assert.doesNotMatch(all, /Current runtime context|Time sampled|You are an AI agent/)
  // 每条消息都有 id 和时间
  for (const m of messages) {
    assert.equal(typeof m.id, 'string')
    assert.ok(m.createdAt > 0)
  }
})

test('思考内容转换成 thinking 部件', () => {
  const think = convertRecords(full.records)[3]
  assert.deepEqual(think?.parts, [{ type: 'thinking', text: '用户想让我想一想。' }, { type: 'text', text: '想好了。' }])
})

test('工具部件：摘要、输入、输出和状态', () => {
  const messages = convertRecords(full.records)
  const tools = messages.flatMap((m) => m.parts).filter((p) => p.type === 'tool')
  const [ask, allowed, rejected] = /** @type {import('../../src/dsh/transcript.js').ToolPart[]} */ (tools)
  assert.equal(ask?.summary, '提问：用哪种方案？')
  assert.match(String(ask?.output), /方案A/)
  assert.equal(allowed?.summary, '运行命令：echo dshremote-contract > contract-output.txt')
  assert.equal(allowed?.input.command, 'echo dshremote-contract > contract-output.txt')
  assert.equal(allowed?.output, '(no output)')
  assert.equal(rejected?.status, 'rejected')
  assert.match(String(rejected?.output), /rejected/)
  assert.equal(allowed?.truncated, undefined)
})

test('完整的一页（hasMore=false）没有游标', () => {
  const page = convertPage(full)
  assert.equal(page.hasMore, false)
  assert.equal(page.cursor, null)
  assert.equal(page.messages.length, 15)
})

test('录制的小页：从模型回复开始，游标是第一条记录的 seq', () => {
  const small = fixture('session-page-small.json')
  const page = convertPage(small)
  assert.equal(page.hasMore, true)
  assert.equal(page.cursor, String(small.records[0].event.seq))
  assert.deepEqual(page.messages.map(shape), ['assistant:text', 'user:text', 'assistant:text'])
})

test('分页切在任何位置，新旧两页拼起来都和整段转换的结果一致（工具调用不会被拆散或重复）', () => {
  const expected = convertRecords(full.records)
  for (let cut = 1; cut < full.records.length; cut++) {
    const newer = convertPage({ records: full.records.slice(cut), hasMore: true })
    assert.ok(newer.cursor !== null, `cut=${cut}`)
    const cursor = Number(newer.cursor)
    assert.ok(cursor >= cut, `cut=${cut}：游标不能比 DSH 的切点更早`)
    // 下一次用游标向前翻页：DSH 返回 seq < cursor 的记录（这里取全部，等价于 hasMore=false 的那一页）
    const older = convertPage({ records: full.records.filter((r) => r.event.seq < cursor), hasMore: false })
    assert.deepEqual([...older.messages, ...newer.messages], expected, `cut=${cut}`)
  }
})

test('分页切在工具调用中间：开头的孤儿事件被去掉，游标落在下一条消息上', () => {
  const callIndex = full.records.findIndex((r) => r.event.type === 'tool/call' && JSON.parse(r.event.data.arguments).command)
  const page = convertPage({ records: full.records.slice(callIndex), hasMore: true })
  const firstKept = full.records.slice(callIndex).find((r) => ['turn/start', 'user/message', 'assistant/message'].includes(r.event.type))
  assert.equal(page.cursor, String(firstKept.event.seq))
  // 这一页里不会出现「没有所属回复」的工具结果
  assert.ok(page.messages.every((m) => m.parts.length > 0))
})

test('一段日志里一个边界都没有时不裁剪', () => {
  const orphans = full.records.filter((r) => ['tool/call', 'tool/result', 'approval/asked'].includes(r.event.type))
  const page = convertPage({ records: orphans, hasMore: true })
  assert.equal(page.cursor, String(orphans[0].event.seq))
  assert.deepEqual(page.messages, [])
})

test('工具调用没有结果、本轮已结束（例如被停止）时，状态为 error；还没结束时为 running', () => {
  const callIndex = full.records.findIndex((r) => r.event.type === 'assistant/message' && r.event.data.message.content.some((/** @type {any} */ c) => c.type === 'tool-call' && c.name === 'bash'))
  const turnEnd = { type: 'event', event: { type: 'turn/end', seq: 9999, time: 1, data: { turn: 4, reason: { kind: 'aborted' } } } }
  const running = convertRecords(full.records.slice(callIndex, callIndex + 1))
  assert.equal(shape(/** @type {any} */ (running[0])), 'assistant:text+tool(bash,running)')
  const aborted = convertRecords([...full.records.slice(callIndex, callIndex + 1), turnEnd])
  assert.equal(shape(/** @type {any} */ (aborted[0])), 'assistant:text+tool(bash,error)')
})

/** @param {string} id @param {string} args */
const assistantWithTool = (id, args) => ({ type: 'event', event: { type: 'assistant/message', seq: 1, time: 1, data: { message: { id: 'm1', content: [{ type: 'tool-call', id, name: 'bash', arguments: args }] } } } })
/** @param {string} id @param {string} text */
const toolResult = (id, text) => ({ type: 'event', event: { type: 'tool/result', seq: 2, time: 2, data: { message: { toolCallId: id, content: [{ type: 'text', text }], isError: false } } } })

test('过长的工具输出按上限截断，并标记 truncated', () => {
  const [message] = convertRecords([assistantWithTool('c1', '{"command":"ls"}'), toolResult('c1', 'x'.repeat(TOOL_OUTPUT_LIMIT + 100))])
  const part = /** @type {import('../../src/dsh/transcript.js').ToolPart} */ (message?.parts[0])
  assert.equal(part.output?.length, TOOL_OUTPUT_LIMIT + 1)
  assert.equal(part.truncated, true)
})

test('过长的工具输入字段按上限截断；无法解析的参数变成空对象', () => {
  const long = JSON.stringify({ command: 'y'.repeat(TOOL_INPUT_STRING_LIMIT + 10) })
  const [a] = convertRecords([assistantWithTool('c1', long)])
  const partA = /** @type {import('../../src/dsh/transcript.js').ToolPart} */ (a?.parts[0])
  assert.equal(String(partA.input.command).length, TOOL_INPUT_STRING_LIMIT + 1)
  assert.equal(partA.truncated, true)
  const [b] = convertRecords([assistantWithTool('c2', '{not json')])
  const partB = /** @type {import('../../src/dsh/transcript.js').ToolPart} */ (b?.parts[0])
  assert.deepEqual(partB.input, {})
  assert.equal(partB.summary, 'bash')
})

test('用户消息中的图片和文件用占位文字表示；DSH 注入的消息和非 append 的消息不显示', () => {
  const user = (/** @type {any} */ source, /** @type {any[]} */ content, /** @type {string} */ surfaceOp = 'append') => ({ type: 'event', event: { type: 'user/message', seq: 1, time: 1, surfaceOp, data: { id: `u${Math.random()}`, source, content } } })
  const messages = convertRecords([
    user({ kind: 'user' }, [{ type: 'text', text: '看图' }, { type: 'image', attachment: {} }, { type: 'file', attachment: { name: 'a.txt' } }]),
    user({ kind: 'runtime-context' }, [{ type: 'text', text: '内部' }]),
    user({ kind: 'user' }, [{ type: 'text', text: '被替换' }], 'replace'),
  ])
  assert.equal(messages.length, 1)
  assert.deepEqual(messages[0]?.parts, [{ type: 'text', text: '看图' }, { type: 'text', text: '[图片]' }, { type: 'text', text: '[文件：a.txt]' }])
})

test('不认识的事件和格式不对的记录被忽略，不抛异常', () => {
  assert.deepEqual(convertRecords([null, 1, 'x', { type: 'event' }, { type: 'event', event: { type: 'future/event', data: {} } }]), [])
})

test('工具摘要', () => {
  assert.equal(toolSummary('pwsh', { command: 'Get-ChildItem\nmore' }), '运行命令：Get-ChildItem')
  assert.equal(toolSummary('read', { file_path: 'src/a.js' }), '读取：src/a.js')
  assert.equal(toolSummary('edit', { file_path: 'src/a.js' }), '编辑：src/a.js')
  assert.equal(toolSummary('grep', { pattern: 'TODO' }), '搜索：TODO')
  assert.equal(toolSummary('web_fetch', { url: 'https://example.com' }), '打开网页：https://example.com')
  assert.equal(toolSummary('some_future_tool', {}), 'some_future_tool')
  assert.equal(toolSummary('bash', { command: 'z'.repeat(500) }).length, 121)
})
