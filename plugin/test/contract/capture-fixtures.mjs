// @ts-check
/**
 * 从真实的 dsh web 录制单元测试用的数据（test/fixtures/dsh-<版本>/）。
 * 外观层的转换器（会话摘要、工作区、模型、权限模式、历史消息）用这些数据做单元测试，
 * 这样单元测试不需要启动 DSH，又能保证测试数据是 DSH 真实返回的格式。
 *
 * 用法：npm run capture-fixtures   （环境变量同 contract）
 * DSH 升级后重新录制一份新版本的数据，对比转换结果，就能看出格式变化。
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { DshConnection } from '../../src/dsh/connection.js'
import { startDshWeb, PLUGIN_DIR, log } from '../helpers/dsh-runtime.mjs'
import { startFakeLlm, TOOL_DONE_REPLY } from './fake-llm.mjs'

const ACTOR = 'd_capturefixtu'
const wait = (/** @type {number} */ ms) => new Promise((r) => setTimeout(r, ms))
/** @template T @param {() => T | undefined | false} fn @param {string} label @returns {Promise<T>} */
async function until(fn, label, ms = 30_000) {
  const end = Date.now() + ms
  while (Date.now() < end) { const v = fn(); if (v) return v; await wait(100) }
  throw new Error(`等待超时：${label}`)
}
/** @param {import('../../src/dsh/connection.js').RemoteResult} r */
const valueOf = (r) => { if (!r.ok) throw new Error(JSON.stringify(r.error)); return /** @type {any} */ (r.value) }

const llm = await startFakeLlm()
const web = await startDshWeb({ withPlugin: false, env: { DEEPSEEK_BASE_URL: llm.url, DEEPSEEK_API_KEY: 'sk-0123456789abcdef0123456789abcdef' } })
const token = web.launch.searchParams.get('token') ?? ''
const dsh = new DshConnection({
  getPort: () => Number(web.launch.port),
  authenticatedUrl: (base) => { const u = new URL(base); u.searchParams.set('token', token); return u.href },
})
const outDir = join(PLUGIN_DIR, 'test', 'fixtures', `dsh-${web.version}`)
/** @param {string} name @param {unknown} value */
const save = (name, value) => {
  writeFileSync(join(outDir, name), `${JSON.stringify(value, null, 2)}\n`)
  log(`已写入 ${name}`)
}
try {
  mkdirSync(outDir, { recursive: true })
  /** @type {import('../../src/dsh/connection.js').DshAppEvent[]} */
  const events = []
  dsh.onEvent((e) => events.push(e))
  await until(() => dsh.status().state === 'ready', '$events ready')

  save('permission-presets.json', valueOf(await dsh.call('permissionPresets/catalog', {}, { actor: ACTOR })))
  save('model-catalog.json', valueOf(await dsh.call('session/modelCatalog', {}, { actor: ACTOR })))

  const workspace = valueOf(await dsh.call('workspace/create', { request: { path: web.workspace } }, { actor: ACTOR })).workspace
  save('workspace-create.json', workspace)
  const sessionId = valueOf(await dsh.call('session/create', { request: { workspaceId: workspace.workspaceId } }, { actor: ACTOR })).sessionId
  // 另一个没有归入工作区的会话
  valueOf(await dsh.call('session/create', { request: {} }, { actor: ACTOR }))
  /** @type {any[]} */
  const follow = []
  await dsh.openStream('session/follow', { request: { address: { kind: 'session', sessionId }, activate: true, assistantStream: true, maxMessages: 20 } }, { onItem: (v) => follow.push(v) }, { actor: ACTOR })
  await until(() => follow.length > 0, 'follow')
  const doneCount = () => JSON.stringify(follow).split(TOOL_DONE_REPLY.slice(0, 3)).length - 1
  const turnEnds = () => follow.filter((f) => f?.type === 'event' && f.event?.type === 'turn/end').length
  /** @param {string} text */
  const prompt = async (text) => valueOf(await dsh.call('session/prompt', { request: { requestId: randomUUID(), sessionId, mode: 'queue', content: [{ type: 'text', text }] } }, { actor: ACTOR }))
  /** @param {string} event */
  const nextWaterfall = async (event, mark = events.length) => /** @type {Extract<import('../../src/dsh/connection.js').DshAppEvent, { type: 'waterfall' }>} */ (
    await until(() => events.slice(mark).find((e) => e.type === 'waterfall' && e.event === event), event))

  // 1. 普通文本  2. 思考 + 文本  3. 提问  4. 批准的命令  5. 拒绝的命令  6. 被取消的长回复
  let ends = turnEnds()
  await prompt('你好'); await until(() => turnEnds() > ends, 'turn 1')
  ends = turnEnds(); await prompt('THINK 一下'); await until(() => turnEnds() > ends, 'turn 2')

  let mark = events.length; let done = doneCount()
  await prompt('请 ASK 我一个问题')
  const question = await nextWaterfall('user-questions/request', mark)
  save('waterfall-question.json', question)
  valueOf(await dsh.answerEvent(question.eventId, { kind: 'result', value: { answers: [{ id: 'q1', selected: ['方案A'] }] } }, { actor: ACTOR }))
  await until(() => doneCount() > done, 'question done')

  mark = events.length; done = doneCount()
  await prompt('请执行 SHELL 命令')
  const approval = await nextWaterfall('approval/request', mark)
  save('waterfall-approval.json', approval)
  valueOf(await dsh.answerEvent(approval.eventId, { kind: 'result', value: 'allowed-once' }, { actor: ACTOR }))
  await until(() => doneCount() > done, 'approval done')

  mark = events.length; done = doneCount()
  await prompt('请再执行一次 SHELL 命令')
  const rejected = await nextWaterfall('approval/request', mark)
  valueOf(await dsh.answerEvent(rejected.eventId, { kind: 'result', value: 'rejected' }, { actor: ACTOR }))
  await until(() => doneCount() > done, 'reject done')

  ends = turnEnds()
  await prompt('SLOW')
  await wait(1500)
  valueOf(await dsh.call('session/cancel', { request: { sessionId } }, { actor: ACTOR }))
  await until(() => turnEnds() > ends, 'cancelled turn end')
  await wait(500)

  /** @type {unknown[]} */
  const workspaceFrames = []
  await dsh.openStream('workspace/follow', {}, { onItem: (v) => workspaceFrames.push(v) }, { actor: ACTOR })
  await until(() => workspaceFrames.length > 0, 'workspace/follow')
  save('workspace-follow-first.json', workspaceFrames[0])
  save('session-list.json', valueOf(await dsh.call('session/list', { _request: {} }, { actor: ACTOR })))
  save('session-page-full.json', valueOf(await dsh.call('session/page', { request: { address: { kind: 'session', sessionId }, maxMessages: 200 } }, { actor: ACTOR })))
  save('session-page-small.json', valueOf(await dsh.call('session/page', { request: { address: { kind: 'session', sessionId }, maxMessages: 3 } }, { actor: ACTOR })))
  save('session-follow-frames.json', follow)
  save('app-events.json', events)
} finally {
  dsh.close()
  await web.stop()
  await llm.close()
}
