// @ts-check
/**
 * 历史消息转换器。测试数据是从真实 dsh 0.2.2-rc.1 录制的（npm run capture-fixtures），
 * 会话内容：普通对话 → 思考 → 提问 → 批准的命令 → 拒绝的命令 → 被停止的长回复。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { convertRecords, convertPage, extractTurns, turnUsage, clipCommandOutput, COMMAND_HEAD_LIMIT, COMMAND_TAIL_LIMIT, turnBefore, turnDigest, turnResult, toolSummary, TOOL_OUTPUT_LIMIT, TOOL_INPUT_STRING_LIMIT, TURN_ERROR_LIMIT, LAST_REPLY_LIMIT } from '../../src/dsh/transcript.js'

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

/** @param {string} id @param {string} args @param {string} [name] */
const assistantWithTool = (id, args, name = 'bash') => ({ type: 'event', event: { type: 'assistant/message', seq: 1, time: 1, data: { message: { id: 'm1', content: [{ type: 'tool-call', id, name, arguments: args }] } } } })
/** @param {string} id @param {string} text */
const toolResult = (id, text) => ({ type: 'event', event: { type: 'tool/result', seq: 2, time: 2, data: { message: { toolCallId: id, content: [{ type: 'text', text }], isError: false } } } })

test('过长的工具输出按上限截断，保留开头并标记 truncated（命令以外的工具）', () => {
  const [message] = convertRecords([assistantWithTool('c1', '{"file_path":"a.txt"}', 'read'), toolResult('c1', 'x'.repeat(TOOL_OUTPUT_LIMIT + 100))])
  const part = /** @type {import('../../src/dsh/transcript.js').ToolPart} */ (message?.parts[0])
  assert.equal(part.output?.length, TOOL_OUTPUT_LIMIT + 1)
  assert.ok(part.output?.endsWith('…'))
  assert.equal(part.truncated, true)
  assert.equal(part.omitted, undefined)
})

test('命令输出（落地方案 B6）：超过上限时保留开头和末尾，切在换行处，标明去掉的位置、字符数和行数', () => {
  const lines = Array.from({ length: 2_000 }, (_, i) => `line ${String(i).padStart(4, '0')}`)
  const text = `${lines.join('\n')}\n`
  const [message] = convertRecords([assistantWithTool('c1', '{"command":"npm test"}'), toolResult('c1', text)])
  const part = /** @type {import('../../src/dsh/transcript.js').ToolPart} */ (message?.parts[0])
  const output = part.output ?? ''
  const omitted = part.omitted
  assert.ok(omitted)
  assert.equal(part.truncated, true)
  assert.ok(output.length <= TOOL_OUTPUT_LIMIT, `总长 ${output.length}`)
  assert.ok(!output.endsWith('…'), '末尾不加省略号')
  // 开头和末尾都是完整的行；最后一行就是原文的最后一行
  const head = output.slice(0, omitted.offset)
  const tail = output.slice(omitted.offset)
  assert.ok(head.endsWith('\n') && head.startsWith('line 0000\n'))
  assert.ok(tail.startsWith('line ') && tail.endsWith('line 1999\n'))
  assert.ok(head.length <= COMMAND_HEAD_LIMIT && tail.length <= COMMAND_TAIL_LIMIT)
  // 去掉的部分加回来就是原文
  assert.equal(head.length + omitted.chars + tail.length, text.length)
  assert.equal(text.slice(0, omitted.offset) + text.slice(omitted.offset + omitted.chars), output)
  const kept = output.split('\n').length - 1
  assert.equal(kept + omitted.lines, 2_000)
  // 不长时原样保留，不带 omitted
  const [short] = convertRecords([assistantWithTool('c2', '{"command":"ls"}'), toolResult('c2', 'a\nb\n')])
  const shortPart = /** @type {import('../../src/dsh/transcript.js').ToolPart} */ (short?.parts[0])
  assert.equal(shortPart.output, 'a\nb\n')
  assert.equal(shortPart.truncated, undefined)
  assert.equal(shortPart.omitted, undefined)
})

test('命令输出：一整行没有换行时按字符切，不切开代理对', () => {
  const emoji = '😀'
  const text = emoji.repeat(TOOL_OUTPUT_LIMIT)
  const clipped = clipCommandOutput(text)
  assert.ok(clipped.omitted)
  const { offset, chars } = clipped.omitted
  assert.equal(offset % 2, 0, '开头不切开代理对')
  assert.equal((offset + chars) % 2, 0, '末尾不切开代理对')
  assert.equal(text.slice(0, offset) + text.slice(offset + chars), clipped.text)
  assert.equal(clipped.omitted.lines, 0)
  assert.deepEqual(clipCommandOutput('短'), { text: '短' })
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

// ---------------------------------------------------------------- 轮次（protocol.md 5.4）

test('轮次：每条消息带上所属的轮次，录制的 6 轮里最后一轮被用户停止', () => {
  const messages = convertRecords(full.records)
  // 用户消息在 turn/start 之后，属于这一轮；模型回复用事件自带的 turn
  assert.deepEqual(messages.filter((m) => m.role === 'user').map((m) => m.turn), [1, 2, 3, 4, 5, 6])
  for (const m of messages) assert.ok(typeof m.turn === 'number', `${m.id} 没有轮次`)
  const turns = extractTurns(full.records)
  assert.deepEqual(turns.map((t) => t.turn), [1, 2, 3, 4, 5, 6])
  assert.deepEqual(turns.map((t) => t.result), ['completed', 'completed', 'completed', 'completed', 'completed', 'stopped'])
  assert.equal(turns[5]?.stoppedBy, 'user')
  for (const t of turns) assert.ok(t.startedAt !== undefined && t.endedAt !== undefined && t.endedAt >= t.startedAt)
  // 只输出协议规定的字段
  for (const t of turns) for (const key of Object.keys(t)) assert.ok(['turn', 'startedAt', 'endedAt', 'result', 'error', 'stoppedBy', 'usage'].includes(key), key)
})

test('本轮用量（落地方案 B4）：按 DSH 的算法从完整的一轮算出，多步的一轮是各次请求的合计；被中途停止的一轮没有', () => {
  const turns = extractTurns(full.records)
  // 假模型每次请求报告输入 10，输出 5 / 8 / 20+5
  assert.deepEqual(turns[0]?.usage, { totalTokens: 15, uncachedInputTokens: 10, outputTokens: 5 })
  assert.deepEqual(turns[1]?.usage, { totalTokens: 18, uncachedInputTokens: 10, outputTokens: 8 })
  assert.deepEqual(turns[2]?.usage, { totalTokens: 45, uncachedInputTokens: 20, outputTokens: 25 })
  // 停止时这一步没有结束，事件不完整：不给小计
  assert.equal(turns[5]?.usage, undefined)
  // 只转发协议里的几项（routes、reasoningTokens 等不转发）
  for (const t of turns) for (const key of Object.keys(t.usage ?? {})) {
    assert.ok(['totalTokens', 'uncachedInputTokens', 'outputTokens', 'cacheReadTokens', 'cacheWriteTokens'].includes(key), key)
  }
})

test('本轮用量：缺了开头的一轮、格式对不上的事件，都不给用量，也不会抛出', () => {
  const start = full.records.findIndex((r) => r.event.type === 'step/start')
  const tail = extractTurns(full.records.slice(start + 1))
  assert.equal(tail[0]?.usage, undefined)
  assert.equal(turnUsage([{ type: 'turn/start' }, { type: 'turn/end' }]), undefined)
  assert.equal(turnUsage([{ type: 'turn/start', data: { turn: 1 } }, { type: 'step/start', data: { turn: 1, step: 1 } }, { type: 'turn/end', data: { turn: 1 } }]), undefined)
})

test('轮次：分页切在任何位置，各页的轮次合起来和整段一致；一页开头的消息由第一个轮次事件推断所属的轮次', () => {
  const expected = extractTurns(full.records)
  for (let cut = 1; cut < full.records.length; cut++) {
    const newer = convertPage({ records: full.records.slice(cut), hasMore: true })
    const older = convertPage({ records: full.records.filter((r) => r.event.seq < Number(newer.cursor)), hasMore: false })
    /** @type {Map<number, any>} */
    const merged = new Map()
    for (const t of [...older.turns, ...newer.turns]) merged.set(t.turn, { ...merged.get(t.turn), ...t })
    const withoutUsage = (/** @type {any[]} */ list) => list.map(({ usage: _, ...rest }) => rest)
    assert.deepEqual(withoutUsage([...merged.values()].sort((a, b) => a.turn - b.turn)), withoutUsage(expected), `cut=${cut}`)
    // 用量只在某一页包含整轮时给出，而且和整段算出的一样；一轮被切开时不给不完整的小计
    for (const t of [...older.turns, ...newer.turns]) {
      if (t.usage !== undefined) assert.deepEqual(t.usage, expected.find((e) => e.turn === t.turn)?.usage, `cut=${cut} turn=${t.turn}`)
    }
    for (const page of [older, newer]) {
      for (const t of page.turns) if (t.startedAt === undefined || t.endedAt === undefined) assert.equal(t.usage, undefined, `cut=${cut} turn=${t.turn}`)
    }
  }
})

test('轮次：还在运行的一轮没有 endedAt 和 result', () => {
  const running = full.records.filter((r) => r.event.seq < 89)
  const last = extractTurns(running).at(-1)
  assert.equal(last?.turn, 6)
  assert.equal(last?.endedAt, undefined)
  assert.equal(last?.result, undefined)
})

test('轮次的结束原因 → result（DSH 0.2.2 的 TurnEndReasonMap，不认识的是 other）', () => {
  assert.deepEqual(turnResult({ kind: 'completed' }), { result: 'completed' })
  assert.deepEqual(turnResult({ kind: 'max-tokens' }), { result: 'completed' })
  assert.deepEqual(turnResult({ kind: 'forked' }), { result: 'completed' })
  assert.deepEqual(turnResult({ kind: 'aborted', reason: { kind: 'user' } }), { result: 'stopped', stoppedBy: 'user' })
  assert.deepEqual(turnResult({ kind: 'aborted', reason: { kind: 'parent' } }), { result: 'stopped', stoppedBy: 'other' })
  assert.deepEqual(turnResult({ kind: 'interrupted' }), { result: 'interrupted' })
  assert.deepEqual(turnResult({ kind: 'blocked' }), { result: 'other' })
  assert.deepEqual(turnResult({ kind: 'something-new' }), { result: 'other' })
  assert.deepEqual(turnResult(undefined), { result: 'other' })
  assert.deepEqual(turnResult({ kind: 'error', error: { message: '  rate limit\n exceeded ', code: 'RATE_LIMIT' } }), { result: 'failed', error: 'rate limit exceeded' })
  assert.deepEqual(turnResult({ kind: 'error', error: { code: 'TOOL_ACTIVATION_FAILED' } }), { result: 'failed', error: 'TOOL_ACTIVATION_FAILED' })
  assert.deepEqual(turnResult({ kind: 'error' }), { result: 'failed' })
  const long = turnResult({ kind: 'error', error: { message: 'x'.repeat(500) } })
  assert.equal(long.error?.length, TURN_ERROR_LIMIT + 1) // 截断后以「…」结尾
  assert.ok(long.error?.endsWith('…'))
})

test('会话摘要的最近一轮：最后一个已经结束的轮次和那一轮最后一段回复的开头', () => {
  const digest = turnDigest(full.records)
  assert.equal(digest.lastTurn?.result, 'stopped')
  assert.equal(typeof digest.lastTurn?.endedAt, 'number')
  assert.equal(typeof digest.lastReply, 'string')
  assert.doesNotMatch(digest.lastReply ?? '', /\n/)
  assert.ok((digest.lastReply ?? '').length <= LAST_REPLY_LIMIT + 1)
  // 最后一轮还在运行时，取上一轮已经结束的
  const running = turnDigest(full.records.filter((r) => r.event.seq < 89))
  assert.equal(running.lastTurn?.result, 'completed')
  // 没有任何已结束的轮次
  assert.deepEqual(turnDigest([]), { lastTurn: null, lastReply: null })
})

test('会话摘要的最近一轮：失败时带上原因', () => {
  const records = [
    { type: 'event', event: { type: 'turn/start', seq: 1, time: 10, data: { turn: 1 } } },
    { type: 'event', event: { type: 'user/message', seq: 2, time: 11, data: { id: 'u1', source: { kind: 'user' }, content: [{ type: 'text', text: 'FAIL' }] } } },
    { type: 'event', event: { type: 'turn/end', seq: 3, time: 12, data: { turn: 1, reason: { kind: 'error', error: { message: 'upstream 400' } } } } },
  ]
  assert.deepEqual(turnDigest(records), { lastTurn: { result: 'failed', endedAt: 12, error: 'upstream 400' }, lastReply: null })
})

test('一页以用户消息开头、里面没有任何带 turn 的事件时，用更早日志里最近的轮次补上（facade 会多读一小段）', () => {
  const userIndex = full.records.findIndex((r) => r.event.type === 'user/message' && r.event.data.source?.kind === 'user' && r.event.seq > 20)
  // 只留下这条用户消息：这一页推断不出轮次
  const lonely = { records: [full.records[userIndex]], hasMore: true }
  assert.equal(convertPage(lonely).messages[0]?.turn, undefined)
  const earlier = full.records.slice(0, userIndex)
  assert.equal(turnBefore(earlier), 2)
  assert.equal(convertPage(lonely, turnBefore(earlier)).messages[0]?.turn, 2)
  assert.equal(turnBefore([]), undefined)
})
