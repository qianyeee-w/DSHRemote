// @ts-check
/**
 * 待处理事项的跟踪与回答。waterfall 的数据是从真实 dsh 0.2.2-rc.1 录制的。
 * DSH 的多客户端行为（重放、cancel、已处理的事件回答时静默成功）见 src/dsh/attention.js 开头的说明。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { AttentionTracker, toDshAnswer } from '../../src/dsh/attention.js'
import { DshUnavailableError } from '../../src/dsh/connection.js'
import { FacadeError } from '../../src/dsh/facade.js'

/** @param {string} name */
const fixture = (name) => JSON.parse(readFileSync(new URL(`../fixtures/dsh-0.2.2-rc.1/${name}`, import.meta.url), 'utf8'))
const approvalEvent = fixture('waterfall-approval.json')
const questionEvent = fixture('waterfall-question.json')
const DEVICE = 'd_abcdefghijkl'
const wait = (/** @type {number} */ ms) => new Promise((r) => setTimeout(r, ms))

/**
 * 模拟 DshConnection 中跟踪器用到的部分。
 * @param {{ answer?: (eventId: string, outcome: unknown) => Promise<import('../../src/dsh/connection.js').RemoteResult>, page?: () => Promise<import('../../src/dsh/connection.js').RemoteResult> }} [behavior]
 */
function fakeDsh(behavior = {}) {
  /** @type {Set<(e: any) => void>} */
  const eventListeners = new Set()
  /** @type {Set<(s: any) => void>} */
  const statusListeners = new Set()
  /** @type {{ eventId: string, outcome: any, actor: string }[]} */
  const answers = []
  let state = 'ready'
  const dsh = {
    onEvent: (/** @type {(e: any) => void} */ l) => { eventListeners.add(l); return () => eventListeners.delete(l) },
    onStatus: (/** @type {(s: any) => void} */ l) => { statusListeners.add(l); return () => statusListeners.delete(l) },
    status: () => ({ state, lastError: undefined }),
    async answerEvent(/** @type {string} */ eventId, /** @type {unknown} */ outcome, /** @type {{ actor: string }} */ context) {
      answers.push({ eventId, outcome, actor: context.actor })
      return behavior.answer ? behavior.answer(eventId, outcome) : { ok: true, value: undefined }
    },
    async call() {
      return behavior.page ? behavior.page() : { ok: true, value: { records: [] } }
    },
  }
  return {
    dsh: /** @type {import('../../src/dsh/connection.js').DshConnection} */ (/** @type {unknown} */ (dsh)),
    answers,
    emit: (/** @type {unknown} */ event) => { for (const l of eventListeners) l(event) },
    setState: (/** @type {string} */ next) => { state = next; for (const l of statusListeners) l({ state: next }) },
  }
}

/** @param {AttentionTracker} tracker */
function record(tracker) {
  /** @type {import('../../src/dsh/attention.js').AttentionChange[]} */
  const changes = []
  tracker.onChange((c) => changes.push(c))
  return changes
}

test('审批：从会话日志补上要执行的命令，再通知；原因优先显示中文', async () => {
  const toolCall = { type: 'event', event: { type: 'tool/call', seq: 5, time: 1, data: { callId: approvalEvent.request.callId, name: 'bash', arguments: '{"command":"rm -rf build"}' } } }
  const fake = fakeDsh({ page: async () => ({ ok: true, value: { records: [toolCall] } }) })
  const tracker = new AttentionTracker(fake.dsh)
  const changes = record(tracker)
  fake.emit(approvalEvent)
  await wait(20)
  assert.equal(changes.length, 1)
  const change = changes[0]
  assert.equal(change?.type, 'requested')
  if (change?.type !== 'requested') return
  assert.equal(change.attention.attentionId, approvalEvent.eventId)
  assert.equal(change.attention.sessionId, approvalEvent.agentId)
  assert.equal(change.attention.kind, 'approval')
  assert.equal(change.attention.approval?.toolName, 'bash')
  assert.match(String(change.attention.approval?.reason), /^允许本次操作/)
  assert.equal(change.attention.approval?.summary, '运行命令：rm -rf build')
  assert.deepEqual(change.attention.approval?.input, { command: 'rm -rf build' })
  assert.deepEqual(tracker.list(approvalEvent.agentId).map((a) => a.attentionId), [approvalEvent.eventId])
  tracker.close()
})

test('审批：补充信息失败时照样通知，只是没有 summary', async () => {
  const fake = fakeDsh({ page: async () => { throw new DshUnavailableError('x') } })
  const tracker = new AttentionTracker(fake.dsh)
  const changes = record(tracker)
  fake.emit(approvalEvent)
  await wait(20)
  assert.equal(changes.length, 1)
  assert.equal(changes[0]?.type === 'requested' && changes[0].attention.approval?.summary, undefined)
  tracker.close()
})

test('提问：转换成协议结构；格式不认识的提问不转发', async () => {
  const fake = fakeDsh()
  const tracker = new AttentionTracker(fake.dsh)
  const changes = record(tracker)
  fake.emit(questionEvent)
  fake.emit({ ...questionEvent, eventId: 'weird', request: { something: 'else' } })
  assert.equal(changes.length, 1)
  const attention = tracker.get(questionEvent.eventId)
  assert.deepEqual(attention?.question, {
    questions: [{ id: 'q1', question: '用哪种方案？', header: '选择', options: [{ label: '方案A' }, { label: '方案B' }], multiSelect: false }],
  })
  assert.equal(tracker.get('weird'), undefined)
  tracker.close()
})

test('回答审批：用设备身份发给 DSH，处理完后通知 resolved（by 为这台设备）', async () => {
  const fake = fakeDsh()
  const tracker = new AttentionTracker(fake.dsh, { graceMs: 10 })
  fake.emit(approvalEvent)
  await wait(10)
  const changes = record(tracker)
  assert.deepEqual(await tracker.answer(DEVICE, approvalEvent.eventId, { decision: 'allow' }), { attentionId: approvalEvent.eventId })
  assert.deepEqual(fake.answers, [{ eventId: approvalEvent.eventId, outcome: { kind: 'result', value: 'allowed-once' }, actor: DEVICE }])
  assert.deepEqual(changes, [{ type: 'resolved', attentionId: approvalEvent.eventId, sessionId: approvalEvent.agentId, by: DEVICE }])
  assert.equal(tracker.list().length, 0)
  tracker.close()
})

test('【反向】在别处处理过的事项再回答：409，且不再发给 DSH', async () => {
  const fake = fakeDsh()
  const tracker = new AttentionTracker(fake.dsh, { graceMs: 10 })
  fake.emit(approvalEvent)
  await wait(10)
  const changes = record(tracker)
  fake.emit({ type: 'cancel', eventId: approvalEvent.eventId }) // 例如在 Desktop 上点了批准
  assert.deepEqual(changes, [{ type: 'resolved', attentionId: approvalEvent.eventId, sessionId: approvalEvent.agentId, by: null }])
  await assert.rejects(tracker.answer(DEVICE, approvalEvent.eventId, { decision: 'allow' }), (e) => e instanceof FacadeError && e.status === 409 && e.code === 'ATTENTION_RESOLVED')
  assert.equal(fake.answers.length, 0)
  tracker.close()
})

test('【反向】同时处理：DSH 对晚到的回答静默返回成功，但随后到达的 cancel 说明别人先处理了 → 409', async () => {
  /** @type {ReturnType<typeof fakeDsh>} */
  const fake = fakeDsh({
    answer: async (eventId) => {
      // 模拟别的客户端先处理：我们的回答发出去的同时，cancel 帧到达
      setTimeout(() => fake.emit({ type: 'cancel', eventId }), 5)
      return { ok: true, value: undefined }
    },
  })
  const tracker = new AttentionTracker(fake.dsh, { graceMs: 50 })
  fake.emit(questionEvent)
  const changes = record(tracker)
  await assert.rejects(tracker.answer(DEVICE, questionEvent.eventId, { answers: [{ id: 'q1', selected: ['方案A'] }] }), (e) => e instanceof FacadeError && e.status === 409)
  assert.deepEqual(changes.map((c) => c.type === 'resolved' && c.by), [null])
  tracker.close()
})

test('【反向】同一事项的两个回答同时到达：第二个直接 409', async () => {
  const fake = fakeDsh()
  const tracker = new AttentionTracker(fake.dsh, { graceMs: 30 })
  fake.emit(questionEvent)
  const first = tracker.answer(DEVICE, questionEvent.eventId, { answers: [{ id: 'q1', selected: ['方案A'] }] })
  await assert.rejects(tracker.answer('d_otherdevice1', questionEvent.eventId, { answers: [{ id: 'q1', selected: ['方案B'] }] }), (e) => e instanceof FacadeError && e.status === 409)
  await first
  assert.equal(fake.answers.length, 1)
  tracker.close()
})

test('【反向】不存在的事项：404', async () => {
  const tracker = new AttentionTracker(fakeDsh().dsh)
  await assert.rejects(tracker.answer(DEVICE, 'nope', { decision: 'allow' }), (e) => e instanceof FacadeError && e.status === 404)
  tracker.close()
})

test('DSH 拒绝回答或连接断开时报错，事项保持待处理，可以重试', async () => {
  let mode = 'error'
  const fake = fakeDsh({
    answer: async () => {
      if (mode === 'error') return { ok: false, error: { code: 'gateway/internal', message: '/secret/path' } }
      if (mode === 'down') throw new DshUnavailableError('事件流还没有就绪')
      return { ok: true, value: undefined }
    },
  })
  const tracker = new AttentionTracker(fake.dsh, { graceMs: 5 })
  fake.emit(questionEvent)
  const answer = { answers: [{ id: 'q1', selected: ['方案A'] }] }
  await assert.rejects(tracker.answer(DEVICE, questionEvent.eventId, answer), (e) => e instanceof FacadeError && e.status === 502 && !e.message.includes('/secret'))
  mode = 'down'
  await assert.rejects(tracker.answer(DEVICE, questionEvent.eventId, answer), DshUnavailableError)
  assert.equal(tracker.list().length, 1)
  mode = 'ok'
  await tracker.answer(DEVICE, questionEvent.eventId, answer)
  assert.equal(tracker.list().length, 0)
  tracker.close()
})

test('重连：DSH 重放的事项保留，断线期间处理掉的事项在重放窗口结束后标记为已处理', async () => {
  const fake = fakeDsh()
  const tracker = new AttentionTracker(fake.dsh, { resyncMs: 30 })
  fake.emit(approvalEvent)
  fake.emit(questionEvent)
  await wait(10)
  const changes = record(tracker)
  fake.setState('connecting')
  fake.setState('ready')
  fake.emit(questionEvent) // 重放：提问还在
  assert.equal(tracker.list().length, 2)
  await wait(60)
  assert.deepEqual(tracker.list().map((a) => a.attentionId), [questionEvent.eventId])
  assert.deepEqual(changes, [{ type: 'resolved', attentionId: approvalEvent.eventId, sessionId: approvalEvent.agentId, by: null }])
  tracker.close()
})

/** @param {Partial<import('../../src/dsh/attention.js').Attention>} extra @returns {import('../../src/dsh/attention.js').Attention} */
const questionAttention = (extra = {}) => ({
  attentionId: 'a', sessionId: 's', kind: 'question', createdAt: 0,
  question: { questions: [
    { id: 'q1', question: '?', header: '', options: [{ label: 'A' }, { label: 'B' }], multiSelect: false },
    { id: 'q2', question: '?', header: '', options: [{ label: 'X' }, { label: 'Y' }], multiSelect: true },
  ] },
  ...extra,
})

test('回答的格式转换', () => {
  const approval = /** @type {import('../../src/dsh/attention.js').Attention} */ ({ attentionId: 'a', sessionId: 's', kind: 'approval', createdAt: 0 })
  assert.equal(toDshAnswer(approval, { decision: 'allow' }), 'allowed-once')
  assert.equal(toDshAnswer(approval, { decision: 'reject' }), 'rejected')
  assert.deepEqual(toDshAnswer(questionAttention(), { answers: [{ id: 'q2', selected: ['X', 'Y'] }, { id: 'q1', selected: [], custom: '都不要' }] }), {
    answers: [{ id: 'q2', selected: ['X', 'Y'] }, { id: 'q1', selected: [], custom: '都不要' }],
  })
})

test('【反向】格式不对的回答一律 400', () => {
  const approval = /** @type {import('../../src/dsh/attention.js').Attention} */ ({ attentionId: 'a', sessionId: 's', kind: 'approval', createdAt: 0 })
  const bad = [
    [approval, { decision: 'always' }], // DSH 没有「以后都允许」
    [approval, null],
    [questionAttention(), { answers: [] }],
    [questionAttention(), { answers: [{ id: 'q9', selected: ['A'] }] }],
    [questionAttention(), { answers: [{ id: 'q1', selected: ['A', 'B'] }] }], // 单选题选了两项
    [questionAttention(), { answers: [{ id: 'q1', selected: ['C'] }] }], // 不存在的选项
    [questionAttention(), { answers: [{ id: 'q1', selected: ['A'] }, { id: 'q1', selected: ['B'] }] }],
    [questionAttention(), { answers: [{ id: 'q1', selected: [] }] }], // 什么都没选
    [questionAttention(), { answers: [{ id: 'q1', selected: [], custom: 'x'.repeat(4001) }] }],
    [questionAttention(), { answers: [{ id: 'q1', selected: [1] }] }],
  ]
  for (const [attention, answer] of bad) {
    assert.throws(() => toDshAnswer(/** @type {any} */ (attention), answer), (e) => e instanceof FacadeError && e.status === 400, JSON.stringify(answer))
  }
})

test('【反向】格式不对的回答不会发给 DSH，事项保持待处理', async () => {
  const fake = fakeDsh()
  const tracker = new AttentionTracker(fake.dsh)
  fake.emit(questionEvent)
  await assert.rejects(tracker.answer(DEVICE, questionEvent.eventId, { answers: [{ id: 'q1', selected: ['方案C'] }] }), (e) => e instanceof FacadeError && e.status === 400)
  assert.equal(fake.answers.length, 0)
  assert.equal(tracker.list().length, 1)
  tracker.close()
})
