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
  assert.deepEqual(Object.keys(sessions[0] ?? {}).sort(), ['attention', 'cwd', 'sessionId', 'status', 'title', 'updatedAt', 'workspaceId'])
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

test('Facade：工作区流出错时，断线视为 DSH 不可用，其他错误视为 DSH 错误', async () => {
  const lost = new Facade(fakeConnection({}, { streamError: { code: 'carrier-lost', message: '' } }).connection)
  await assert.rejects(lost.workspaces(ACTOR), DshUnavailableError)
  const failed = new Facade(fakeConnection({}, { streamError: { code: 'gateway/internal', message: '' } }).connection)
  await rejectsWith(failed.workspaces(ACTOR), 502, 'DSH_ERROR')
})
