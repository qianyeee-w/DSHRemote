// @ts-check
/**
 * 外观层：DSH 返回值 → protocol.md 第 5 节的结构。测试数据从真实 dsh 0.2.2-rc.1 录制。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  Facade, FacadeError, convertSessionList, convertModelCatalog, convertPermissionPresets, parseWorkspaceBaseline,
  MESSAGES_DEFAULT_LIMIT, MESSAGES_MAX_LIMIT,
} from '../../src/dsh/facade.js'
import { DshUnavailableError } from '../../src/dsh/connection.js'

/** @param {string} name */
const fixture = (name) => JSON.parse(readFileSync(new URL(`../fixtures/dsh-0.2.2-rc.1/${name}`, import.meta.url), 'utf8'))
const ACTOR = 'd_abcdefghijkl'

test('工作区：解析 workspace/follow 的 baseline 帧', () => {
  const state = parseWorkspaceBaseline(fixture('workspace-follow-first.json'))
  assert.equal(state.items.length, 1)
  assert.equal(state.items[0]?.title, 'workspace')
  assert.equal(state.items[0]?.sessionIds.length, 1)
  assert.deepEqual(parseWorkspaceBaseline({ type: 'upsert' }), { items: [], archived: new Set() })
})

test('会话列表：标题、所属工作区、状态；没有归入工作区的会话 workspaceId 为 null', () => {
  const workspaces = parseWorkspaceBaseline(fixture('workspace-follow-first.json'))
  const sessions = convertSessionList(fixture('session-list.json'), workspaces, () => [])
  assert.equal(sessions.length, 2)
  const inWorkspace = sessions.find((s) => s.workspaceId !== null)
  assert.equal(inWorkspace?.title, '你好，这是假模型的回复。')
  assert.equal(inWorkspace?.workspaceId, workspaces.items[0]?.workspaceId)
  assert.equal(inWorkspace?.status, 'idle')
  assert.deepEqual(inWorkspace?.attention, [])
  const blank = sessions.find((s) => s.workspaceId === null)
  assert.equal(blank?.title, null)
  assert.equal(typeof blank?.cwd, 'string')
  // 只输出协议规定的字段，DSH 的 projections 等内部数据不出现
  assert.deepEqual(Object.keys(sessions[0] ?? {}).sort(), ['attention', 'cwd', 'lastReply', 'lastTurn', 'model', 'permissionPreset', 'sessionId', 'status', 'title', 'updatedAt', 'workspaceId'])
})

test('会话列表：waiting → running → idle 排序，同一状态内按 updatedAt 倒序；已归档的会话不出现', () => {
  const item = (/** @type {string} */ id, /** @type {boolean} */ running, /** @type {number} */ updatedAt) => ({ sessionId: id, running, updatedAt, projections: { values: { title: id } } })
  const list = { items: [item('idle-old', false, 1), item('running', true, 2), item('idle-new', false, 3), item('waiting', false, 0), item('archived', true, 9)] }
  const sessions = convertSessionList(list, { items: [], archived: new Set(['archived']) }, (id) => (id === 'waiting' ? [{ attentionId: 'a1', kind: 'approval' }] : []))
  assert.deepEqual(sessions.map((s) => `${s.sessionId}:${s.status}`), ['waiting:waiting', 'running:running', 'idle-new:idle', 'idle-old:idle'])
})

test('模型列表与权限模式', () => {
  const models = convertModelCatalog(fixture('model-catalog.json'))
  assert.deepEqual(models.default, { provider: 'deepseek-official', model: 'deepseek-flash' })
  assert.deepEqual(models.models.map((m) => `${m.provider}/${m.model}`), ['deepseek-official/deepseek-flash', 'deepseek-official/deepseek-v4-pro'])
  assert.equal(models.models[0]?.providerName, 'DeepSeek')
  assert.equal(typeof models.models[1]?.description, 'string')
  const presets = convertPermissionPresets(fixture('permission-presets.json'))
  assert.deepEqual(presets, {
    default: 'workspace-write',
    presets: [{ id: 'read-only', name: 'read-only' }, { id: 'workspace-write', name: 'workspace-write' }, { id: 'danger-full-access', name: 'danger-full-access' }],
  })
  assert.deepEqual(convertModelCatalog(null), { default: null, models: [] })
})

/**
 * 模拟 DshConnection：记录调用，按 endpoint 返回预设结果。
 * @param {Record<string, import('../../src/dsh/connection.js').RemoteResult | (() => never)>} results
 * @param {{ workspaceFrame?: unknown, streamError?: { code: string, message: string } }} [stream]
 */
function fakeConnection(results, stream = {}) {
  /** @type {{ endpoint: string, args: Record<string, unknown>, actor: string }[]} */
  const calls = []
  let cancelled = 0
  const connection = {
    /** @param {string} endpoint @param {Record<string, unknown>} args @param {{ actor: string }} context */
    async call(endpoint, args, context) {
      calls.push({ endpoint, args, actor: context.actor })
      const result = results[endpoint]
      if (typeof result === 'function') return result()
      if (!result) throw new Error(`没有预设 ${endpoint}`)
      return result
    },
    /** @param {string} endpoint @param {unknown} _args @param {import('../../src/dsh/connection.js').StreamHandlers} handlers @param {{ actor: string }} context */
    async openStream(endpoint, _args, handlers, context) {
      calls.push({ endpoint, args: {}, actor: context.actor })
      setImmediate(() => {
        if (stream.streamError) handlers.onError?.(stream.streamError)
        else handlers.onItem(stream.workspaceFrame ?? { type: 'baseline', value: { items: [], archivedSessionIds: [], pinnedSessionIds: [] } })
      })
      return { streamId: 's', send() {}, cancel() { cancelled++ } }
    },
  }
  return { connection: /** @type {import('../../src/dsh/connection.js').DshConnection} */ (/** @type {unknown} */ (connection)), calls, cancelled: () => cancelled }
}

test('Facade：每次调用都带上设备身份；读完工作区首帧后关闭流', async () => {
  const fake = fakeConnection({ 'session/list': { ok: true, value: fixture('session-list.json') } }, { workspaceFrame: fixture('workspace-follow-first.json') })
  const facade = new Facade(fake.connection)
  const { sessions } = await facade.sessions(ACTOR)
  assert.equal(sessions.length, 2)
  assert.deepEqual(fake.calls.map((c) => c.endpoint).sort(), ['session/list', 'workspace/follow'])
  assert.ok(fake.calls.every((c) => c.actor === ACTOR))
  assert.deepEqual(fake.calls.find((c) => c.endpoint === 'session/list')?.args, { _request: {} })
  assert.equal(fake.cancelled(), 1)
  const { workspaces } = await facade.workspaces(ACTOR)
  assert.deepEqual(Object.keys(workspaces[0] ?? {}).sort(), ['path', 'title', 'workspaceId'])
})

test('Facade：历史消息的参数映射（limit → maxMessages，cursor → beforeSeq）', async () => {
  const fake = fakeConnection({ 'session/page': { ok: true, value: fixture('session-page-small.json') } })
  const facade = new Facade(fake.connection)
  const page = await facade.messages(ACTOR, 'session-1', { before: '120', limit: 3 })
  assert.equal(page.hasMore, true)
  assert.deepEqual(fake.calls[0]?.args, { request: { address: { kind: 'session', sessionId: 'session-1' }, maxMessages: 3, beforeSeq: 120 } })
  await facade.messages(ACTOR, 'session-1')
  assert.deepEqual(fake.calls[1]?.args, { request: { address: { kind: 'session', sessionId: 'session-1' }, maxMessages: MESSAGES_DEFAULT_LIMIT } })
})

/** @param {Promise<unknown>} promise @param {number} status @param {string} code */
async function rejectsWith(promise, status, code) {
  await assert.rejects(promise, (error) => error instanceof FacadeError && error.status === status && error.code === code)
}

test('【反向】Facade：非法的会话 id、cursor、limit 不会发给 DSH', async () => {
  const fake = fakeConnection({})
  const facade = new Facade(fake.connection)
  await rejectsWith(facade.messages(ACTOR, '../x'), 404, 'NOT_FOUND')
  await rejectsWith(facade.messages(ACTOR, 's', { before: '-1' }), 400, 'BAD_REQUEST')
  await rejectsWith(facade.messages(ACTOR, 's', { before: '1e3' }), 400, 'BAD_REQUEST')
  await rejectsWith(facade.messages(ACTOR, 's', { limit: 0 }), 400, 'BAD_REQUEST')
  await rejectsWith(facade.messages(ACTOR, 's', { limit: MESSAGES_MAX_LIMIT + 1 }), 400, 'BAD_REQUEST')
  assert.equal(fake.calls.length, 0)
})

test('Facade：DSH 的错误映射成外观层错误，原始错误信息不外传', async () => {
  const facade = new Facade(fakeConnection({
    'session/page': { ok: false, error: { code: 'session/not-found', message: 'session "x" not found at /home/me/secret' } },
    'session/modelCatalog': { ok: false, error: { code: 'gateway/internal', message: '/home/me/secret' } },
    'permissionPresets/catalog': () => { throw new DshUnavailableError('无法连接 DSH') },
  }).connection)
  await rejectsWith(facade.messages(ACTOR, 'session-x'), 404, 'NOT_FOUND')
  await assert.rejects(facade.models(ACTOR), (error) => error instanceof FacadeError && error.status === 502 && !error.message.includes('/home'))
  await assert.rejects(facade.permissionPresets(ACTOR), DshUnavailableError)
})

test('Facade：用量只读 session/projections；投影暂时不可用时两项为 null，会话不存在时 404', async () => {
  const ok = fakeConnection({
    'session/projections': { ok: true, value: { kind: 'sequenced', asOfSeq: 3, values: { tokenUsage: { uncachedInputTokens: 1, outputTokens: 2, cacheReadTokens: 3, cacheWriteTokens: 4 }, contextPressure: { projectedTokens: 9, contextWindow: 100 } } } },
  })
  assert.deepEqual(await new Facade(ok.connection).usage(ACTOR, 'session-a'), {
    context: { tokens: 9, window: 100 },
    total: { uncachedInputTokens: 1, outputTokens: 2, cacheReadTokens: 3, cacheWriteTokens: 4 },
  })
  assert.deepEqual(ok.calls.map((c) => [c.endpoint, c.args, c.actor]), [['session/projections', { request: { sessionId: 'session-a' } }, ACTOR]])
  const unavailable = fakeConnection({ 'session/projections': { ok: false, error: { code: 'session/projections-unavailable', message: '' } } })
  assert.deepEqual(await new Facade(unavailable.connection).usage(ACTOR, 'session-a'), { context: null, total: null })
  const missing = fakeConnection({ 'session/projections': { ok: false, error: { code: 'session/not-found', message: '/home/me/secret' } } })
  await rejectsWith(new Facade(missing.connection).usage(ACTOR, 'session-x'), 404, 'NOT_FOUND')
  await rejectsWith(new Facade(missing.connection).usage(ACTOR, '../x'), 404, 'NOT_FOUND')
})

test('Facade：工作区流出错时，断线视为 DSH 不可用，其他错误视为 DSH 错误', async () => {
  const lost = new Facade(fakeConnection({}, { streamError: { code: 'carrier-lost', message: '' } }).connection)
  await assert.rejects(lost.workspaces(ACTOR), DshUnavailableError)
  const failed = new Facade(fakeConnection({}, { streamError: { code: 'gateway/internal', message: '' } }).connection)
  await rejectsWith(failed.workspaces(ACTOR), 502, 'DSH_ERROR')
})

// ---------------------------------------------------------------- 写操作

test('会话摘要：带上当前模型和权限模式', () => {
  const workspaces = parseWorkspaceBaseline(fixture('workspace-follow-first.json'))
  const sessions = convertSessionList(fixture('session-list.json'), workspaces, () => [])
  const main = sessions.find((s) => s.title !== null)
  assert.deepEqual(main?.model, { provider: 'deepseek-official', model: 'deepseek-flash' })
  assert.equal(main?.permissionPreset, 'workspace-write')
})

test('写操作：参数映射到 DSH 的接口', async () => {
  const fake = fakeConnection({
    'session/create': { ok: true, value: { sessionId: 'session-new' } },
    'session/prompt': { ok: true, value: { accepted: true } },
    'session/cancel': { ok: true, value: { accepted: true } },
    'session/selectModel': { ok: true, value: { selected: { provider: 'p', model: 'm' } } },
    'permissionPresets/catalog': { ok: true, value: fixture('permission-presets.json') },
    'commands/execute': { ok: true, value: { commandId: 'c', result: { kind: 'success', text: 'preset read-only' } } },
  })
  const facade = new Facade(fake.connection)
  assert.deepEqual(await facade.createSession(ACTOR, {}), { sessionId: 'session-new' })
  assert.deepEqual(await facade.createSession(ACTOR, { workspaceId: 'w1' }), { sessionId: 'session-new' })
  assert.deepEqual(await facade.prompt(ACTOR, 's1', { requestId: 'r1', text: '你好', mode: 'steer' }), { accepted: true })
  assert.deepEqual(await facade.prompt(ACTOR, 's1', { requestId: 'r2', text: '默认排队' }), { accepted: true })
  assert.deepEqual(await facade.cancel(ACTOR, 's1'), { accepted: true })
  assert.deepEqual(await facade.selectModel(ACTOR, 's1', { provider: 'p', model: 'm' }), { model: { provider: 'p', model: 'm' } })
  assert.deepEqual(await facade.setPermissionPreset(ACTOR, 's1', { preset: 'read-only' }), { permissionPreset: 'read-only' })
  assert.deepEqual(fake.calls.map((c) => [c.endpoint, c.args]), [
    ['session/create', { request: {} }],
    ['session/create', { request: { workspaceId: 'w1' } }],
    ['session/prompt', { request: { requestId: 'r1', sessionId: 's1', mode: 'steer', content: [{ type: 'text', text: '你好' }] } }],
    ['session/prompt', { request: { requestId: 'r2', sessionId: 's1', mode: 'queue', content: [{ type: 'text', text: '默认排队' }] } }],
    ['session/cancel', { request: { sessionId: 's1' } }],
    ['session/selectModel', { request: { sessionId: 's1', provider: 'p', model: 'm' } }],
    ['permissionPresets/catalog', {}],
    ['commands/execute', { agentId: 's1', line: '/permission read-only', submittedAttachments: [] }],
  ])
  assert.ok(fake.calls.every((c) => c.actor === ACTOR))
})

test('【反向】写操作：格式不对的参数不会发给 DSH', async () => {
  const fake = fakeConnection({})
  const facade = new Facade(fake.connection)
  const cases = [
    () => facade.createSession(ACTOR, { workspaceId: '../x' }),
    () => facade.prompt(ACTOR, 's1', { text: 'hi' }), // 缺少 requestId
    () => facade.prompt(ACTOR, 's1', { requestId: 'r', text: '   ' }),
    () => facade.prompt(ACTOR, 's1', { requestId: 'r', text: 'x'.repeat(100_001) }),
    () => facade.prompt(ACTOR, 's1', { requestId: 'r', text: 'hi', mode: 'now' }),
    () => facade.prompt(ACTOR, 's1', { requestId: 'r w', text: 'hi' }),
    () => facade.selectModel(ACTOR, 's1', { provider: 'p' }),
    () => facade.selectModel(ACTOR, 's1', { provider: 'p', model: 'm m' }),
    () => facade.setPermissionPreset(ACTOR, 's1', {}),
  ]
  for (const run of cases) await assert.rejects(run(), (e) => e instanceof FacadeError && e.status === 400)
  await assert.rejects(facade.prompt(ACTOR, 'bad/id', { requestId: 'r', text: 'hi' }), (e) => e instanceof FacadeError && e.status === 404)
  assert.equal(fake.calls.length, 0)
})

test('【反向】权限模式：只能切换到 DSH 列出的模式，不能借机执行其他命令（安全红线 8）', async () => {
  const fake = fakeConnection({
    'permissionPresets/catalog': { ok: true, value: fixture('permission-presets.json') },
    'commands/execute': { ok: true, value: { commandId: 'c', result: { kind: 'success', text: '' } } },
  })
  const facade = new Facade(fake.connection)
  for (const preset of ['read-only; /plugin add x', 'read-only\n/clear', '../x', 'not-in-catalog', 'READ-ONLY']) {
    await assert.rejects(facade.setPermissionPreset(ACTOR, 's1', { preset }), (e) => e instanceof FacadeError && e.status === 400, preset)
  }
  assert.equal(fake.calls.filter((c) => c.endpoint === 'commands/execute').length, 0)
})

test('写操作：DSH 的错误码映射', async () => {
  const facade = new Facade(fakeConnection({
    'session/prompt': { ok: false, error: { code: 'session/not-found', message: 'x' } },
    'session/create': { ok: false, error: { code: 'workspace/not-found', message: 'x' } },
    'session/selectModel': { ok: false, error: { code: 'session/model-unavailable', message: 'x' } },
    'session/cancel': { ok: false, error: { code: 'something/new', message: '/path/leak' } },
    'permissionPresets/catalog': { ok: true, value: fixture('permission-presets.json') },
    'commands/execute': { ok: true, value: { commandId: 'c', result: { kind: 'error', text: 'unknown preset' } } },
  }).connection)
  /** @param {Promise<unknown>} p @param {number} status */
  const expectStatus = (p, status) => assert.rejects(p, (e) => e instanceof FacadeError && e.status === status && !e.message.includes('/path'))
  await expectStatus(facade.prompt(ACTOR, 's1', { requestId: 'r', text: 'hi' }), 404)
  await expectStatus(facade.createSession(ACTOR, { workspaceId: 'w' }), 404)
  await expectStatus(facade.selectModel(ACTOR, 's1', { provider: 'p', model: 'm' }), 400)
  await expectStatus(facade.cancel(ACTOR, 's1'), 502)
  await expectStatus(facade.setPermissionPreset(ACTOR, 's1', { preset: 'read-only' }), 502)
})

test('发消息按 requestId 去重：重试和并发的重复提交只发出一次；失败后可以重试；过期后不再去重', async () => {
  let now = 0
  let fail = true
  /** @type {(() => void) | undefined} */
  let release
  const calls = []
  const connection = /** @type {any} */ ({
    async call(/** @type {string} */ endpoint) {
      calls.push(endpoint)
      if (fail) return { ok: false, error: { code: 'something/new', message: '' } }
      await new Promise((r) => { release = () => r(undefined) })
      return { ok: true, value: { accepted: true } }
    },
  })
  const facade = new Facade(connection, { now: () => now })
  const body = { requestId: 'r1', text: 'hi' }
  await assert.rejects(facade.prompt(ACTOR, 's1', body), FacadeError)
  fail = false
  const first = facade.prompt(ACTOR, 's1', body)
  const second = facade.prompt(ACTOR, 's1', body) // 并发的重复提交
  await new Promise((r) => setImmediate(r))
  release?.()
  assert.deepEqual(await Promise.all([first, second]), [{ accepted: true }, { accepted: true }])
  assert.deepEqual(await facade.prompt(ACTOR, 's1', body), { accepted: true }) // 之后的重试
  assert.equal(calls.length, 2) // 失败的一次 + 成功的一次
  // 不同会话的同一个 requestId 不算重复
  const other = facade.prompt(ACTOR, 's2', body)
  await new Promise((r) => setImmediate(r)); release?.(); await other
  assert.equal(calls.length, 3)
  now = 11 * 60_000
  const late = facade.prompt(ACTOR, 's1', body)
  await new Promise((r) => setImmediate(r)); release?.(); await late
  assert.equal(calls.length, 4)
})
