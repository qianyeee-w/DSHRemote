// @ts-check
/**
 * 实时状态：会话消息的增量推送（用录制的真实 session/follow 帧回放）、任务完成判断、会话列表变化。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { SessionFeed, LiveHub, applyStreamEntry, draftMessage } from '../../src/dsh/live.js'
import { convertRecords } from '../../src/dsh/transcript.js'

/** @param {string} name */
const fixture = (name) => JSON.parse(readFileSync(new URL(`../fixtures/dsh-0.2.2-rc.1/${name}`, import.meta.url), 'utf8'))
/** @type {any[]} */
const followFrames = fixture('session-follow-frames.json')
const tick = () => new Promise((r) => setImmediate(r))
const wait = (/** @type {number} */ ms) => new Promise((r) => setTimeout(r, ms))

/** 模拟 DshConnection.openStream：测试代码通过 push 送入帧。 */
function fakeFollow() {
  /** @type {{ handlers: import('../../src/dsh/connection.js').StreamHandlers, cancelled: boolean, args: unknown }[]} */
  const streams = []
  const dsh = /** @type {import('../../src/dsh/connection.js').DshConnection} */ (/** @type {unknown} */ ({
    onEvent: () => () => {},
    onStatus: () => () => {},
    async openStream(/** @type {string} */ _endpoint, /** @type {unknown} */ args, /** @type {import('../../src/dsh/connection.js').StreamHandlers} */ handlers) {
      const entry = { handlers, cancelled: false, args }
      streams.push(entry)
      return { streamId: String(streams.length), send() {}, cancel() { entry.cancelled = true } }
    },
  }))
  return { dsh, streams, push: (/** @type {unknown} */ frame) => streams.at(-1)?.handlers.onItem(frame) }
}

/**
 * 模拟 App：按收到的 message.upsert / message.removed 维护消息列表。
 * @param {import('../../src/dsh/transcript.js').Message[]} history 打开会话时通过 REST 读到的历史
 */
function fakeApp(history) {
  /** @type {Map<string, any>} */
  const messages = new Map(history.map((m) => [m.id, m]))
  /** @type {Map<string, number>} */
  const revs = new Map()
  /** @type {import('../../src/dsh/live.js').MessageEvent[]} */
  const events = []
  return {
    events,
    messages,
    /** @param {import('../../src/dsh/live.js').MessageEvent} event */
    apply(event) {
      events.push(event)
      if (event.type === 'message.removed') { messages.delete(event.messageId); return }
      if (event.type === 'turn.updated' || event.type === 'plan.updated') return
      const last = revs.get(event.message.id) ?? 0
      assert.ok(event.rev > last, `rev 必须递增：${event.message.id}`)
      revs.set(event.message.id, event.rev)
      if (event.replaces) messages.delete(event.replaces)
      messages.set(event.message.id, event.message)
    },
  }
}

test('回放真实的 session/follow：流式草稿先出现，写入日志后被正式消息替换；最终结果与整段转换一致', async () => {
  const fake = fakeFollow()
  const snapshot = followFrames[0]
  const app = fakeApp(convertRecords(snapshot.records))
  const feed = new SessionFeed(fake.dsh, 'session-x', (e) => app.apply(e), { draftThrottleMs: 0 })
  await tick()
  for (const frame of followFrames) { fake.push(frame); await wait(2) }
  await wait(5)
  const allRecords = [...snapshot.records, ...followFrames.filter((f) => f.type === 'event')]
  const expected = convertRecords(allRecords)
  const final = [...app.messages.values()].sort((a, b) => a.createdAt - b.createdAt)
  assert.deepEqual(final, expected)
  // 确实推送过草稿，而且每个草稿最后都被替换掉了
  const drafts = app.events.filter((e) => e.type === 'message.upsert' && e.message.draft)
  assert.ok(drafts.length >= 3, `草稿数=${drafts.length}`)
  assert.ok(drafts.some((e) => e.type === 'message.upsert' && e.message.parts.some((p) => p.type === 'thinking')))
  const replaced = new Set(app.events.flatMap((e) => (e.type === 'message.upsert' && e.replaces ? [e.replaces] : [])))
  for (const d of drafts) if (d.type === 'message.upsert') assert.ok(replaced.has(d.message.id), d.message.id)
  // snapshot 中已有的历史不重复推送
  for (const m of convertRecords(snapshot.records)) assert.ok(!app.events.some((e) => e.type === 'message.upsert' && e.message.id === m.id))
  // 每个事件都带唯一的 eventId
  assert.equal(new Set(app.events.map((e) => e.eventId)).size, app.events.length)
  feed.close()
  assert.equal(fake.streams[0]?.cancelled, true)
})

test('轮次：一轮开始和结束时推送 turn.updated，同一轮后一次替换前一次；snapshot 里已有的轮次不推送（protocol.md 6.1）', async () => {
  const fake = fakeFollow()
  /** @type {import('../../src/dsh/live.js').MessageEvent[]} */
  const events = []
  const feed = new SessionFeed(fake.dsh, 's', (e) => events.push(e), { draftThrottleMs: 0 })
  await tick()
  fake.push({ type: 'snapshot', records: [
    { type: 'event', event: { type: 'turn/start', seq: 1, time: 10, data: { turn: 1 } } },
    { type: 'event', event: { type: 'turn/end', seq: 2, time: 20, data: { turn: 1, reason: { kind: 'completed' } } } },
  ], assistantStream: { revision: 0 } })
  fake.push({ type: 'event', event: { type: 'turn/start', seq: 3, time: 30, data: { turn: 2 } } })
  fake.push({ type: 'event', event: { type: 'turn/end', seq: 4, time: 90, data: { turn: 2, reason: { kind: 'error', error: { message: 'upstream 400' } } } } })
  await wait(2)
  const turns = events.flatMap((e) => (e.type === 'turn.updated' ? [e.turn] : []))
  assert.deepEqual(turns, [
    { turn: 2, startedAt: 30 },
    { turn: 2, startedAt: 30, endedAt: 90, result: 'failed', error: 'upstream 400' },
  ])
  feed.close()
})

test('草稿：输出被放弃（abandoned）时通知 App 删除', async () => {
  const fake = fakeFollow()
  /** @type {import('../../src/dsh/live.js').MessageEvent[]} */
  const events = []
  const feed = new SessionFeed(fake.dsh, 's', (e) => events.push(e), { draftThrottleMs: 0 })
  await tick()
  fake.push({ type: 'snapshot', records: [], assistantStream: { revision: 0 } })
  fake.push({ type: 'assistant-stream', frame: { type: 'start', attemptId: 'a1', turn: 1, step: 1 } })
  fake.push({ type: 'assistant-stream', frame: { type: 'chunk', attemptId: 'a1', chunk: { type: 'text-delta', index: 0, text: '半句' } } })
  await wait(5)
  fake.push({ type: 'assistant-stream', frame: { type: 'end', attemptId: 'a1', outcome: { kind: 'abandoned' } } })
  assert.deepEqual(events.map((e) => e.type), ['message.upsert', 'message.removed'])
  assert.equal(events[1]?.type === 'message.removed' && events[1].messageId, 'draft:a1')
  feed.close()
})

test('订阅时模型正在输出：snapshot 中的 activeAttempt 立即作为草稿推送', async () => {
  const fake = fakeFollow()
  /** @type {import('../../src/dsh/live.js').MessageEvent[]} */
  const events = []
  const feed = new SessionFeed(fake.dsh, 's', (e) => events.push(e))
  await tick()
  fake.push({
    type: 'snapshot', records: [],
    assistantStream: { revision: 3, activeAttempt: { attemptId: 'a9', turn: 2, step: 1, stream: [
      { type: 'chunk', chunk: { type: 'block-start', index: 0, blockType: 'text' } },
      { type: 'text-chunks', index: 0, texts: ['已经', '输出的'] },
    ] } },
  })
  assert.equal(events.length, 1)
  const first = events[0]
  assert.ok(first?.type === 'message.upsert')
  assert.deepEqual(first.message.parts, [{ type: 'text', text: '已经输出的' }])
  feed.close()
})

test('DSH 断开后自动重新订阅，重连后只推送有变化的消息', async () => {
  const fake = fakeFollow()
  /** @type {import('../../src/dsh/live.js').MessageEvent[]} */
  const events = []
  const feed = new SessionFeed(fake.dsh, 's', (e) => events.push(e))
  await tick()
  const snapshot = followFrames[0]
  const records = [...snapshot.records, ...followFrames.filter((f) => f.type === 'event')]
  const turnStarts = records.flatMap((r, i) => (r.event.type === 'turn/start' ? [i] : []))
  const cut = /** @type {number} */ (turnStarts.at(-1))
  fake.push({ type: 'snapshot', records: records.slice(0, cut + 3) })
  fake.streams[0]?.handlers.onError?.({ code: 'carrier-lost', message: '' })
  await wait(700)
  assert.equal(fake.streams.length, 2, '应当重新打开 session/follow')
  fake.push({ type: 'snapshot', records })
  const ids = events.flatMap((e) => (e.type === 'message.upsert' ? [e.message.id] : []))
  const lastTurn = convertRecords(records.slice(cut)).map((m) => m.id)
  // 只推送断线期间新增的（最后一轮里的）消息
  assert.ok(ids.length > 0 && ids.every((id) => lastTurn.includes(id)), JSON.stringify(ids))
  feed.close()
})

test('草稿转换：思考、文本、正在输出参数的工具调用', () => {
  const draft = { attemptId: 'a', turn: 1, step: 1, createdAt: 5, blocks: new Map() }
  applyStreamEntry(draft, { type: 'block-start', index: 0, blockType: 'reasoning' })
  applyStreamEntry(draft, { type: 'reasoning-delta', index: 0, text: '想' })
  applyStreamEntry(draft, { type: 'text-delta', index: 1, text: '答' })
  applyStreamEntry(draft, { type: 'tool-call-delta', index: 2, id: 'c1', name: 'bash', argumentsDelta: '{"command":"l' })
  assert.deepEqual(draftMessage(draft).parts, [
    { type: 'thinking', text: '想' },
    { type: 'text', text: '答' },
    { type: 'tool', callId: 'c1', name: 'bash', summary: 'bash', status: 'running', input: {} },
  ])
  applyStreamEntry(draft, { type: 'tool-call-delta', index: 2, argumentsDelta: 's"}' })
  const tool = draftMessage(draft).parts[2]
  assert.equal(tool?.type === 'tool' && tool.summary, '运行命令：ls')
})

// ---------------------------------------------------------------- LiveHub

/**
 * @param {{ sessions?: () => import('../../src/dsh/facade.js').SessionSummary[], digests?: Record<string, any> }} [options]
 */
function fakeHubDeps(options = {}) {
  /** @type {Set<(e: any) => void>} */
  const dshListeners = new Set()
  /** @type {Set<(e: any) => void>} */
  const attentionListeners = new Set()
  /** @type {import('../../src/dsh/attention.js').Attention[]} */
  let pending = []
  const dsh = /** @type {any} */ ({
    onEvent: (/** @type {any} */ l) => { dshListeners.add(l); return () => dshListeners.delete(l) },
    onStatus: () => () => {},
  })
  /** @type {Map<string, any>} */
  const digests = new Map()
  /** @type {string[]} */
  const digestReads = []
  const facade = /** @type {any} */ ({
    sessions: async () => ({ sessions: options.sessions?.() ?? [] }),
    refreshDigest: async (/** @type {string} */ _actor, /** @type {string} */ id) => {
      digestReads.push(id)
      const digest = options.digests?.[id] ?? { lastTurn: null, lastReply: null }
      digests.set(id, digest)
      return digest
    },
    digest: (/** @type {string} */ id) => digests.get(id),
    hasDigest: (/** @type {string} */ id) => digests.has(id),
  })
  const attention = /** @type {any} */ ({
    onChange: (/** @type {any} */ l) => { attentionListeners.add(l); return () => attentionListeners.delete(l) },
    list: (/** @type {string} */ sessionId) => pending.filter((a) => sessionId === undefined || a.sessionId === sessionId),
  })
  return {
    dsh, facade, attention, digestReads,
    emitDsh: (/** @type {string} */ event, /** @type {unknown[]} */ args) => { for (const l of dshListeners) l({ type: 'emit', event, args }) },
    emitAttention: (/** @type {any} */ change) => { for (const l of attentionListeners) l(change) },
    setPending: (/** @type {any[]} */ list) => { pending = list },
  }
}

/** @param {string} id @param {Partial<import('../../src/dsh/facade.js').SessionSummary>} [extra] @returns {import('../../src/dsh/facade.js').SessionSummary} */
const summary = (id, extra = {}) => ({ sessionId: id, title: id, workspaceId: null, cwd: null, status: 'idle', attention: [], model: null, permissionPreset: null, updatedAt: 1, lastTurn: null, lastReply: null, ...extra })

test('任务完成：停止运行并持续一段时间后才发 session.idle；期间又开始运行就不发', async () => {
  const deps = fakeHubDeps({ sessions: () => [summary('s1', { title: '修复登录' })] })
  const hub = new LiveHub(deps.dsh, deps.facade, deps.attention, { idleMs: 40, debounceMs: 1 })
  /** @type {import('../../src/dsh/live.js').BroadcastEvent[]} */
  const events = []
  hub.onEvent((e) => events.push(e))
  await hub.snapshot('d_abcdefghijkl')
  deps.emitDsh('api-session/status', ['s1', true])
  deps.emitDsh('api-session/status', ['s1', false])
  await wait(20)
  deps.emitDsh('api-session/status', ['s1', true]) // 8 秒内又开始运行
  await wait(60)
  assert.equal(events.filter((e) => e.type === 'session.idle').length, 0)
  deps.emitDsh('api-session/status', ['s1', false])
  await wait(60)
  const idle = events.filter((e) => e.type === 'session.idle')
  assert.equal(idle.length, 1)
  assert.equal(idle[0]?.type === 'session.idle' && idle[0].title, '修复登录')
  hub.close()
})

test('任务完成：停下来是因为在等审批或提问时，不发 session.idle', async () => {
  const deps = fakeHubDeps()
  const hub = new LiveHub(deps.dsh, deps.facade, deps.attention, { idleMs: 20, debounceMs: 1 })
  /** @type {import('../../src/dsh/live.js').BroadcastEvent[]} */
  const events = []
  hub.onEvent((e) => events.push(e))
  deps.setPending([{ attentionId: 'a1', sessionId: 's1', kind: 'approval', createdAt: 0 }])
  deps.emitDsh('api-session/status', ['s1', false])
  await wait(50)
  assert.equal(events.filter((e) => e.type === 'session.idle').length, 0)
  hub.close()
})

test('会话列表变化：只推送变化的会话；消失的会话推送 session.removed', async () => {
  let list = [summary('s1'), summary('s2')]
  const deps = fakeHubDeps({ sessions: () => list })
  const hub = new LiveHub(deps.dsh, deps.facade, deps.attention, { debounceMs: 1 })
  /** @type {import('../../src/dsh/live.js').BroadcastEvent[]} */
  const events = []
  hub.onEvent((e) => events.push(e))
  await hub.snapshot('d_abcdefghijkl')
  list = [summary('s1', { status: 'running' }), summary('s3')]
  deps.emitDsh('api-session/activity', ['s1', 2])
  await wait(30)
  assert.deepEqual(events.map((e) => (e.type === 'session.updated' ? `updated:${e.session.sessionId}` : e.type === 'session.removed' ? `removed:${e.sessionId}` : e.type)).sort(),
    ['removed:s2', 'updated:s1', 'updated:s3'])
  hub.close()
})

test('refreshSoon：没有 DSH 事件时也重新读取会话列表并推送变化（切换模型后）', async () => {
  let list = [summary('s1')]
  const deps = fakeHubDeps({ sessions: () => list })
  const hub = new LiveHub(deps.dsh, deps.facade, deps.attention, { debounceMs: 1 })
  /** @type {import('../../src/dsh/live.js').BroadcastEvent[]} */
  const events = []
  hub.onEvent((e) => events.push(e))
  await hub.snapshot('d_abcdefghijkl')
  list = [summary('s1', { model: { provider: 'p', model: 'm2' } })]
  hub.refreshSoon()
  await wait(30)
  assert.deepEqual(events.map((e) => (e.type === 'session.updated' ? e.session.model : e.type)), [{ provider: 'p', model: 'm2' }])
  hub.close()
})

test('待处理事项的变化原样广播', async () => {
  const deps = fakeHubDeps()
  const hub = new LiveHub(deps.dsh, deps.facade, deps.attention, { debounceMs: 1 })
  /** @type {import('../../src/dsh/live.js').BroadcastEvent[]} */
  const events = []
  hub.onEvent((e) => events.push(e))
  const attention = { attentionId: 'a1', sessionId: 's1', kind: 'approval', createdAt: 0 }
  deps.emitAttention({ type: 'requested', attention })
  deps.emitAttention({ type: 'resolved', attentionId: 'a1', sessionId: 's1', by: 'd_abcdefghijkl' })
  assert.deepEqual(events.map(({ eventId, ...rest }) => (assert.equal(typeof eventId, 'string'), rest)), [
    { type: 'attention.requested', attention },
    { type: 'attention.resolved', attentionId: 'a1', sessionId: 's1', by: 'd_abcdefghijkl' },
  ])
  hub.close()
})

test('多个手机订阅同一个会话时共用一条 session/follow，最后一个取消时关闭', async () => {
  const fake = fakeFollow()
  const deps = fakeHubDeps()
  const hub = new LiveHub(fake.dsh, deps.facade, deps.attention)
  const offA = hub.subscribe('s1', () => {})
  const offB = hub.subscribe('s1', () => {})
  await tick()
  assert.equal(fake.streams.length, 1)
  offA()
  assert.equal(fake.streams[0]?.cancelled, false)
  offB()
  assert.equal(fake.streams[0]?.cancelled, true)
  hub.close()
})

test('任务结束：读一次日志末尾，session.idle 带上这一轮的结果（protocol.md 6.1）', async () => {
  const deps = fakeHubDeps({
    sessions: () => [summary('s1')],
    digests: { s1: { lastTurn: { result: 'failed', endedAt: 5, error: 'rate limit' }, lastReply: null } },
  })
  const hub = new LiveHub(deps.dsh, deps.facade, deps.attention, { idleMs: 30, debounceMs: 1 })
  /** @type {import('../../src/dsh/live.js').BroadcastEvent[]} */
  const events = []
  hub.onEvent((e) => events.push(e))
  await hub.snapshot('d_abcdefghijkl')
  await new Promise((r) => setTimeout(r, 20))
  deps.digestReads.length = 0
  deps.emitDsh('api-session/status', ['s1', true])
  deps.emitDsh('api-session/status', ['s1', false])
  await new Promise((r) => setTimeout(r, 80))
  assert.deepEqual(deps.digestReads, ['s1'])
  const idle = events.find((e) => e.type === 'session.idle')
  assert.ok(idle && idle.type === 'session.idle')
  assert.equal(idle.result, 'failed')
  hub.close()
})

test('插件启动后只补算最近更新的 20 个会话（用户确认），读完推送变化', async () => {
  const sessions = Array.from({ length: 25 }, (_, i) => summary(`s${i}`, { updatedAt: i }))
  const deps = fakeHubDeps({ sessions: () => sessions })
  const hub = new LiveHub(deps.dsh, deps.facade, deps.attention, { debounceMs: 1 })
  hub.onEvent(() => {})
  await hub.snapshot('d_abcdefghijkl')
  await new Promise((r) => setTimeout(r, 30))
  assert.equal(deps.digestReads.length, 20)
  assert.deepEqual(new Set(deps.digestReads), new Set(sessions.slice(5).map((s) => s.sessionId)))
  // 第二个连接的 hello 不再重复补算
  await hub.snapshot('d_abcdefghijkl')
  await new Promise((r) => setTimeout(r, 10))
  assert.equal(deps.digestReads.length, 20)
  hub.close()
})

test('计划（落地方案 B5）：看到 todo/write、goal/change、turn/start 时重新读投影，变了才推送 plan.updated；订阅时不推送', async () => {
  const fake = fakeFollow()
  /** @type {unknown} */
  let values = { todos: null, goal: null }
  let reads = 0
  Object.assign(fake.dsh, {
    call: async (/** @type {string} */ endpoint, /** @type {any} */ args) => {
      assert.equal(endpoint, 'session/projections')
      assert.deepEqual(args, { request: { sessionId: 's' } })
      reads++
      return { ok: true, value: { kind: 'sequenced', asOfSeq: 1, values } }
    },
  })
  /** @type {import('../../src/dsh/live.js').MessageEvent[]} */
  const events = []
  const feed = new SessionFeed(fake.dsh, 's', (e) => events.push(e), { planDebounceMs: 5 })
  await tick()
  fake.push({ type: 'snapshot', records: [] })
  await wait(30)
  assert.equal(reads, 1)
  assert.equal(events.filter((e) => e.type === 'plan.updated').length, 0, '订阅时 App 自己读，不推送')

  const event = (/** @type {string} */ type, /** @type {number} */ seq) => ({ type: 'event', event: { type, seq, time: seq, data: { turn: 1 } } })
  values = { todos: [{ content: '跑测试', status: 'in_progress' }], goal: null }
  fake.push(event('todo/write', 10))
  // 同一时刻的几条只读一次
  fake.push(event('todo/write', 11))
  await wait(30)
  assert.equal(reads, 2)
  assert.deepEqual(events.filter((e) => e.type === 'plan.updated').map((e) => /** @type {any} */ (e).plan), [
    { goal: null, steps: [{ text: '跑测试', status: 'in_progress' }] },
  ])
  // 没变：不推送
  fake.push(event('goal/change', 12))
  await wait(30)
  assert.equal(events.filter((e) => e.type === 'plan.updated').length, 1)
  // 新一轮开始，DSH 清空了任务清单
  values = { todos: null, goal: null }
  fake.push(event('turn/start', 13))
  await wait(30)
  const last = /** @type {any} */ (events.filter((e) => e.type === 'plan.updated').at(-1))
  assert.deepEqual(last.plan, { goal: null, steps: null })
  assert.equal(last.sessionId, 's')
  feed.close()
})

