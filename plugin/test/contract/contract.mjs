// @ts-check
/**
 * DSH 接口契约测试：用假模型服务驱动真实的 `dsh web`，通过插件的适配层（src/dsh/connection.js）
 * 逐项验证插件依赖的 DSH 接口。DSH 升级后，这里失败的项目就是适配层需要修改的地方
 * （docs/roadmap.md 第 1 条）。
 *
 * 用法：
 *   npm run contract                       # 基线版本
 *   DSH_VERSION=0.2.3 npm run contract     # 其他版本
 *   DSH_BIN / DSH_INSTALL_DIR 同 load-check
 *
 * 输出：stdout 最后一行是 JSON 结果 { check: 'contract', dshVersion, platform, ok, results: [{ name, ok, detail }] }。
 * 退出码 0 表示全部通过。
 */
import { existsSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { DshConnection, PLUGIN_ACTOR } from '../../src/dsh/connection.js'
import { Facade, FacadeError } from '../../src/dsh/facade.js'
import { AttentionTracker } from '../../src/dsh/attention.js'
import { startDshWeb, DSH_VERSION, log } from '../helpers/dsh-runtime.mjs'
import { FAIL_MESSAGE, startFakeLlm, FAKE_REPLY, TOOL_DONE_REPLY, SHELL_MARKER_FILE, TODO_ITEMS, GOAL_OBJECTIVE, LONG_OUTPUT_LINES } from './fake-llm.mjs'

/** 测试里模拟的设备身份：适配层要求每次调用都说明替谁发起 */
const ACTOR = 'd_contracttest'
const STEP_TIMEOUT_MS = 30_000

/** @type {{ name: string, ok: boolean, detail?: string }[]} */
const results = []
/** @param {string} name @param {boolean} ok @param {string} [detail] */
function record(name, ok, detail) {
  results.push(detail === undefined ? { name, ok } : { name, ok, detail })
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  ${detail}` : ''}`)
}
/** @param {unknown} value @param {number} [n] */
const short = (value, n = 200) => { const s = JSON.stringify(value) ?? 'undefined'; return s.length > n ? `${s.slice(0, n)}…` : s }
const wait = (/** @type {number} */ ms) => new Promise((r) => setTimeout(r, ms))
/**
 * @template T
 * @param {() => T | undefined | false} fn @param {string} label @param {number} [ms]
 * @returns {Promise<T>}
 */
async function until(fn, label, ms = STEP_TIMEOUT_MS) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const value = fn()
    if (value) return value
    await wait(100)
  }
  throw new Error(`等待超时：${label}`)
}

/** @type {(() => unknown) | undefined} */
let diagnostics

/**
 * @template T
 * @param {() => Promise<T | undefined | false>} fn @param {string} label @param {number} [ms]
 * @returns {Promise<T>}
 */
async function untilAsync(fn, label, ms = STEP_TIMEOUT_MS) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const value = await fn()
    if (value) return value
    await wait(200)
  }
  throw new Error(`等待超时：${label}`)
}

/**
 * 跑一个检查项；抛出异常记为失败，后续项目继续执行。
 * @param {string} name @param {() => Promise<string | undefined | void>} fn
 */
async function step(name, fn) {
  try {
    const detail = await fn()
    record(name, true, detail ?? undefined)
    return true
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    record(name, false, diagnostics ? `${message}；${short(diagnostics(), 900)}` : message)
    return false
  }
}

/** @param {import('../../src/dsh/connection.js').RemoteResult} result */
function valueOf(result) {
  if (!result.ok) throw new Error(`DSH 返回错误：${short(result.error)}`)
  return /** @type {any} */ (result.value)
}

/** @param {DshConnection} dsh @param {string} sessionId @param {string} text */
async function prompt(dsh, sessionId, text) {
  return valueOf(await dsh.call('session/prompt', {
    request: { requestId: randomUUID(), sessionId, mode: 'queue', content: [{ type: 'text', text }] },
  }, { actor: ACTOR }))
}

async function main() {
  const llm = await startFakeLlm()
  diagnostics = llm.diagnostics
  /** @type {import('../helpers/dsh-runtime.mjs').DshWeb | undefined} */
  let web
  /** @type {DshConnection | undefined} */
  let dsh
  let version = DSH_VERSION
  try {
    web = await startDshWeb({
      withPlugin: false,
      env: { DEEPSEEK_BASE_URL: llm.url, DEEPSEEK_API_KEY: 'sk-0123456789abcdef0123456789abcdef' },
    })
    version = web.version
    const launch = web.launch
    const token = launch.searchParams.get('token') ?? ''
    dsh = new DshConnection({
      getPort: () => Number(launch.port),
      // 和 ctx.connection.authenticatedUrl 的行为一致：在给定地址上加上启动 token
      authenticatedUrl: (base) => { const u = new URL(base); u.searchParams.set('token', token); return u.href },
    })
    const conn = dsh

    // ---- 一元调用
    await step('session/list', async () => {
      const value = valueOf(await conn.call('session/list', { _request: {} }, { actor: ACTOR }))
      return short(value, 120)
    })
    await step('session/list 缺少参数时返回业务错误（不是连接错误）', async () => {
      const result = await conn.call('session/list', {}, { actor: ACTOR })
      if (result.ok) throw new Error('缺少 _request 时应当返回错误')
      return result.error.code
    })

    // ---- $events
    /** @type {import('../../src/dsh/connection.js').DshAppEvent[]} */
    const events = []
    conn.onEvent((event) => events.push(event))
    await step('$events 就绪', async () => {
      await until(() => conn.status().state === 'ready', '$events ready')
    })

    // ---- 新建会话 + 跟随
    /** @type {string | undefined} */
    let sessionId
    await step('session/create', async () => {
      sessionId = valueOf(await conn.call('session/create', { request: {} }, { actor: ACTOR }))?.sessionId
      if (typeof sessionId !== 'string') throw new Error('没有返回 sessionId')
    })
    if (!sessionId) throw new Error('无法新建会话，后续检查跳过')
    const sid = sessionId

    /** @type {unknown[]} */
    const followFrames = []
    /** @type {{ code: string, message: string } | undefined} */
    let followError
    await step('session/follow 首帧', async () => {
      await conn.openStream('session/follow', {
        request: { address: { kind: 'session', sessionId: sid }, activate: true, assistantStream: true, maxMessages: 20 },
      }, { onItem: (v) => followFrames.push(v), onError: (e) => { followError = e } }, { actor: ACTOR })
      await until(() => followFrames.length > 0 || followError, 'follow 首帧')
      if (followError) throw new Error(`follow 出错：${followError.code}`)
      return short(followFrames[0], 120)
    })

    /** @type {unknown[]} */
    const stateFrames = []
    await step('session/observeState 首帧', async () => {
      await conn.openStream('session/observeState', { request: { sessionId: sid } }, { onItem: (v) => stateFrames.push(v) }, { actor: ACTOR })
      await until(() => stateFrames.length > 0, 'observeState 首帧')
      return short(stateFrames[0], 120)
    })

    /** follow 流中「工具结果之后的回复」出现的次数（假模型收到 tool_result 后的固定回复，分块输出，只取前几个字） */
    const doneCount = () => JSON.stringify(followFrames).split(TOOL_DONE_REPLY.slice(0, 3)).length - 1

    // ---- 普通对话：流式输出
    await step('session/prompt + 流式输出', async () => {
      const mark = followFrames.length
      await prompt(conn, sid, '你好')
      await until(() => JSON.stringify(followFrames.slice(mark)).includes(FAKE_REPLY.slice(0, 4)), 'follow 流中出现模型回复')
      return `新增帧数=${followFrames.length - mark}`
    })

    // ---- 提问
    await step('提问（user-questions/request）并回答', async () => {
      const mark = events.length
      const done0 = doneCount()
      await prompt(conn, sid, '请 ASK 我一个问题')
      const question = await until(() => events.slice(mark).find((e) => e.type === 'waterfall' && e.event === 'user-questions/request'), '提问 waterfall')
      if (question.type !== 'waterfall') throw new Error('unreachable')
      valueOf(await conn.answerEvent(question.eventId, { kind: 'result', value: { answers: [{ id: 'q1', selected: ['方案A'] }] } }, { actor: ACTOR }))
      await until(() => doneCount() > done0, '回答后模型收到 tool_result 并回复')
    })

    // ---- 审批
    await step('审批（approval/request）并批准，命令被执行', async () => {
      await wait(1000)
      const mark = events.length
      await prompt(conn, sid, '请执行 SHELL 命令')
      const approval = await until(() => events.slice(mark).find((e) => e.type === 'waterfall' && e.event === 'approval/request'), '审批 waterfall')
      if (approval.type !== 'waterfall') throw new Error('unreachable')
      valueOf(await conn.answerEvent(approval.eventId, { kind: 'result', value: 'allowed-once' }, { actor: ACTOR }))
      const marker = join(web?.workspace ?? '', SHELL_MARKER_FILE)
      await until(() => existsSync(marker), '命令输出文件')
      return `request=${short(approval.request, 120)}`
    })

    await step('审批被拒绝时命令不执行', async () => {
      await wait(1000)
      const mark = events.length
      const marker = join(web?.workspace ?? '', SHELL_MARKER_FILE)
      rmSync(marker, { force: true })
      const done0 = doneCount()
      await prompt(conn, sid, '请再执行一次 SHELL 命令')
      const approval = await until(() => events.slice(mark).find((e) => e.type === 'waterfall' && e.event === 'approval/request'), '审批 waterfall')
      if (approval.type !== 'waterfall') throw new Error('unreachable')
      valueOf(await conn.answerEvent(approval.eventId, { kind: 'result', value: 'rejected' }, { actor: ACTOR }))
      // 等模型收到被拒绝的 tool_result 并结束本轮
      await until(() => doneCount() > done0, '拒绝后模型收到 tool_result 并回复')
      if (existsSync(marker)) throw new Error('审批被拒绝，命令却执行了')
    })

    // ---- 历史分页
    await step('session/page', async () => {
      await wait(1000)
      const value = valueOf(await conn.call('session/page', { request: { address: { kind: 'session', sessionId: sid }, maxMessages: 2 } }, { actor: ACTOR }))
      return short(value, 120)
    })

    // ---- 取消
    await step('session/cancel 停止正在输出的回复', async () => {
      /** @param {unknown} f */
      const running = (f) => /** @type {any} */ (f)?.running === true
      await until(() => !running(stateFrames.at(-1)), '上一轮结束')
      await prompt(conn, sid, 'SLOW')
      await until(() => running(stateFrames.at(-1)), '开始输出')
      valueOf(await conn.call('session/cancel', { request: { sessionId: sid } }, { actor: ACTOR }))
      // 假模型要 10 秒才能输出完，5 秒内停下来说明取消生效
      await until(() => !running(stateFrames.at(-1)), '取消后停止运行', 5000)
    })

    // ---- 外观层（只读）：通过 Facade 读取，检查转换结果
    const facade = new Facade(conn)
    await step('外观层：创建目录、登记工作区、重复登记和错误恢复', async () => {
      if (!web) throw new Error('测试运行时不存在')
      const created = await facade.createDirectory(ACTOR, { path: web.workspace, name: '手机 项目' })
      const first = await facade.registerWorkspace(ACTOR, { path: created.path })
      const again = await facade.registerWorkspace(ACTOR, { path: created.path })
      if (!first.created || again.created || first.workspace.workspaceId !== again.workspace.workspaceId) throw new Error('重复登记没有复用工作区')
      if (!(await facade.workspaces(ACTOR)).workspaces.some((w) => w.workspaceId === first.workspace.workspaceId)) throw new Error('工作区列表未更新')
      const session = await facade.createSession(ACTOR, { workspaceId: first.workspace.workspaceId })
      const summary = (await facade.sessions(ACTOR)).sessions.find((s) => s.sessionId === session.sessionId)
      if (summary?.workspaceId !== first.workspace.workspaceId || summary.cwd !== first.workspace.path) throw new Error('会话没有采用项目目录')
      try { await facade.registerWorkspace(ACTOR, { path: join(created.path, 'absent') }); throw new Error('应拒绝不存在的目录') }
      catch (error) { if (!(error instanceof FacadeError) || error.code !== 'DIRECTORY_UNAVAILABLE') throw error }
    })
    await step('外观层：工作区与会话列表', async () => {
      const workspace = valueOf(await conn.call('workspace/create', { request: { path: web?.workspace } }, { actor: ACTOR })).workspace
      const inWorkspace = valueOf(await conn.call('session/create', { request: { workspaceId: workspace.workspaceId } }, { actor: ACTOR })).sessionId
      const { workspaces } = await facade.workspaces(ACTOR)
      if (!workspaces.some((w) => w.workspaceId === workspace.workspaceId)) throw new Error(`工作区列表中没有新建的工作区：${short(workspaces)}`)
      const { sessions } = await facade.sessions(ACTOR)
      const main = sessions.find((x) => x.sessionId === sid)
      const member = sessions.find((x) => x.sessionId === inWorkspace)
      if (!main || main.status !== 'idle' || typeof main.title !== 'string') throw new Error(`会话摘要不对：${short(main)}`)
      if (member?.workspaceId !== workspace.workspaceId) throw new Error(`会话没有归入工作区：${short(member)}`)
      return `sessions=${sessions.length}`
    })
    await step('外观层：模型列表与权限模式', async () => {
      const models = await facade.models(ACTOR)
      const presets = await facade.permissionPresets(ACTOR)
      if (models.models.length === 0 || !models.default) throw new Error(`模型列表为空：${short(models)}`)
      if (!presets.presets.some((x) => x.id === 'workspace-write')) throw new Error(`权限模式不对：${short(presets)}`)
      return `models=${models.models.length} presets=${presets.presets.map((x) => x.id).join(',')}`
    })
    await step('外观层：历史消息，小页翻到底与一次取完的结果一致', async () => {
      const all = await facade.messages(ACTOR, sid, { limit: 200 })
      if (all.hasMore) throw new Error('200 条以内应当一次取完')
      const kinds = all.messages.flatMap((m) => m.parts.map((x) => (x.type === 'tool' ? `tool:${x.status}` : x.type)))
      for (const expected of ['text', 'tool:done', 'tool:rejected']) {
        if (!kinds.includes(expected)) throw new Error(`历史中缺少 ${expected}：${kinds.join(',')}`)
      }
      /** @type {import('../../src/dsh/transcript.js').Message[]} */
      let paged = []
      /** @type {string | undefined} */
      let before
      for (let i = 0; i < 100; i++) {
        const page = await facade.messages(ACTOR, sid, { limit: 2, before })
        paged = [...page.messages, ...paged]
        if (!page.hasMore || page.cursor === null) break
        before = page.cursor
      }
      if (JSON.stringify(paged) !== JSON.stringify(all.messages)) {
        const i = paged.findIndex((m, k) => JSON.stringify(m) !== JSON.stringify(all.messages[k]))
        throw new Error(`分页结果不一致：${paged.length} vs ${all.messages.length}；第 ${i} 条：${short({ ...paged[i], parts: undefined })} vs ${short({ ...all.messages[i], parts: undefined })}`)
      }
      return `messages=${all.messages.length}`
    })
    await step('外观层：轮次（protocol.md 5.4）——每条消息都有所属的轮次，最后一轮是用户停止的', async () => {
      const all = await facade.messages(ACTOR, sid, { limit: 200 })
      const missing = all.messages.filter((m) => typeof m.turn !== 'number')
      if (missing.length > 0) throw new Error(`没有轮次的消息：${missing.map((m) => m.id).join(',')}`)
      const turns = all.turns
      if (turns.length === 0) throw new Error('没有轮次')
      const open = turns.filter((t) => t.result === undefined)
      if (open.length > 0) throw new Error(`还有没结束的轮次：${short(open)}`)
      const last = turns.at(-1)
      if (last?.result !== 'stopped' || last.stoppedBy !== 'user') throw new Error(`最后一轮应当是用户停止：${short(last)}`)
      const digest = await facade.refreshDigest(ACTOR, sid)
      if (digest.lastTurn?.result !== 'stopped') throw new Error(`摘要的最近一轮不对：${short(digest)}`)
      return `turns=${turns.map((t) => t.result).join(',')}`
    })
    await step('外观层：本轮用量（protocol.md 5.4，落地方案 B4）——正常结束的轮次按 DSH 的算法得出用量，被停止的一轮没有', async () => {
      const turns = (await facade.messages(ACTOR, sid, { limit: 200 })).turns
      const completed = turns.filter((t) => t.result === 'completed')
      if (completed.length === 0) throw new Error('没有正常结束的轮次')
      // 假模型每次请求报告输入 10、输出 > 0，不报告缓存
      for (const t of completed) {
        const u = t.usage
        if (!u) throw new Error(`第 ${t.turn} 轮没有用量：${short(t)}`)
        if (u.uncachedInputTokens < 10 || u.uncachedInputTokens % 10 !== 0 || u.outputTokens <= 0 || u.totalTokens !== u.uncachedInputTokens + u.outputTokens) {
          throw new Error(`第 ${t.turn} 轮的用量不对：${short(u)}`)
        }
        if (u.cacheReadTokens !== undefined || u.cacheWriteTokens !== undefined) throw new Error(`假模型没有报告缓存，不能给出缓存：${short(u)}`)
      }
      const stopped = turns.at(-1)
      if (stopped?.usage !== undefined) throw new Error(`被停止的一轮不应当有用量：${short(stopped)}`)
      return completed.map((t) => `${t.turn}:${t.usage?.totalTokens}`).join(',')
    })
    await step('外观层：用量与上下文（GET /v1/sessions/{id}/usage）来自 session/projections', async () => {
      const usage = await facade.usage(ACTOR, sid)
      const total = usage.total
      if (!total) throw new Error(`没有累计用量：${short(usage)}`)
      const turns = (await facade.messages(ACTOR, sid, { limit: 200 })).turns
      const summed = turns.reduce((n, t) => n + (t.usage?.uncachedInputTokens ?? 0), 0)
      // 累计用量包括被停止那一轮已经结算的请求，至少不少于各轮之和
      if (total.uncachedInputTokens < summed) throw new Error(`累计输入 ${total.uncachedInputTokens} 少于各轮之和 ${summed}`)
      if (total.cacheReadTokens !== 0 || total.cacheWriteTokens !== 0) throw new Error(`假模型没有缓存：${short(total)}`)
      const context = usage.context
      if (!context || typeof context.tokens !== 'number' || context.tokens <= 0 || typeof context.window !== 'number' || context.window <= context.tokens) {
        throw new Error(`上下文不对：${short(context)}`)
      }
      // 新会话还没有任何请求：上下文占用拿不到，累计为 0（DSH 的真实值），不能编出数字
      const fresh = valueOf(await conn.call('session/create', { request: {} }, { actor: ACTOR }))?.sessionId
      const empty = await facade.usage(ACTOR, fresh)
      if (empty.context?.tokens !== undefined) throw new Error(`新会话不应当有上下文占用：${short(empty)}`)
      return `${short(usage, 160)}；新会话 ${short(empty, 120)}`
    })
    await step('外观层：另开对话（protocol.md「另开对话」，落地方案 C1）——带着这一轮之前的内容，标题加编号，按 requestId 去重', async () => {
      const source = valueOf(await conn.call('session/create', { request: {} }, { actor: ACTOR }))?.sessionId
      for (const text of ['第一个问题', '第二个问题', '第三个问题']) {
        await prompt(conn, source, text)
        const count = ['第一个问题', '第二个问题', '第三个问题'].indexOf(text) + 1
        await untilAsync(async () => (await facade.messages(ACTOR, source, { limit: 50 })).turns.filter((t) => t.result !== undefined).length >= count, `第 ${count} 轮结束`)
      }
      const sourceTitle = (await facade.sessions(ACTOR)).sessions.find((x) => x.sessionId === source)?.title
      const requestId = randomUUID()
      const forked = await facade.fork(ACTOR, source, { requestId, beforeTurn: 3 })
      if (!forked.workspaceAttached) throw new Error('新会话应当归到原来的项目')
      const again = await facade.fork(ACTOR, source, { requestId, beforeTurn: 3 })
      if (again.sessionId !== forked.sessionId) throw new Error('同一个 requestId 分出了两个会话')
      const child = await facade.messages(ACTOR, forked.sessionId, { limit: 50 })
      const users = child.messages.filter((m) => m.role === 'user').map((m) => m.parts.map((p) => (p.type === 'text' ? p.text : '')).join(''))
      if (JSON.stringify(users) !== JSON.stringify(['第一个问题', '第二个问题'])) throw new Error(`新会话的内容不对：${short(users)}`)
      if (child.turns.some((t) => t.turn > 2)) throw new Error(`新会话不应当有第 3 轮：${short(child.turns)}`)
      const listed = await untilAsync(async () => (await facade.sessions(ACTOR)).sessions.find((x) => x.sessionId === forked.sessionId && x.title !== null), '新会话出现在列表里')
      if (sourceTitle && listed.title !== `${sourceTitle} (1)`) throw new Error(`标题不对：${listed.title}（原来是 ${sourceTitle}）`)
      if (listed.status === 'running') throw new Error('新会话不应当自己开始运行')
      // 从第一条消息另开：空会话
      const empty = await facade.fork(ACTOR, source, { requestId: randomUUID(), beforeTurn: 1 })
      const emptyPage = await facade.messages(ACTOR, empty.sessionId, { limit: 50 })
      if (emptyPage.messages.length !== 0) throw new Error(`从第一轮之前另开应当是空会话：${short(emptyPage.messages)}`)
      return `子会话 ${listed.title}；空会话 ${empty.sessionId.slice(-6)}`
    })
    await step('外观层：很长的命令输出保留开头和末尾（protocol.md 5.2，落地方案 B6）', async () => {
      const longSession = valueOf(await conn.call('session/create', { request: {} }, { actor: ACTOR }))?.sessionId
      await prompt(conn, longSession, '请 LONGOUT 一下')
      const part = await untilAsync(async () => {
        const page = await facade.messages(ACTOR, longSession, { limit: 50 })
        for (const m of page.messages) for (const p of m.parts) if (p.type === 'tool' && p.status !== 'running' && p.output !== undefined) return p
        return undefined
      }, '命令结束', 60_000)
      if (part.status !== 'done') throw new Error(`命令没有成功：${short(part, 300)}`)
      const output = (part.output ?? '').replace(/\r\n/g, '\n')
      const omitted = part.omitted
      if (!omitted || part.truncated !== true) throw new Error(`应当去掉中间：${short({ ...part, output: output.slice(0, 80) }, 300)}`)
      // DSH 的 bash、pwsh 自己只保留输出的末尾，所以开头不一定是 line 1（调研文档 9.3c）
      if (part.outputSaved !== true) throw new Error('20000 行的输出 DSH 一定会截断，应当标记 outputSaved')
      if (output.includes('[output truncated; full output:')) throw new Error(`bash/pwsh 的截断提示（带电脑上的路径）没有去掉：${short(output.slice(-160))}`)
      // 输出很大时 DSH 自己会截断（保留开头和末尾，最后附一段带电脑路径的提示）：插件去掉提示，末尾仍是真正的最后一行
      if (output.includes('Full formatted result stored at')) throw new Error(`DSH 的截断提示（带电脑上的路径）没有去掉：${short(output.slice(-160))}`)
      if (!output.trimEnd().endsWith(`line ${LONG_OUTPUT_LINES}`)) throw new Error(`末尾不对：${short(output.slice(-120))}`)
      if (output.length > 8_000) throw new Error(`超过上限：${output.length}`)
      return `offset=${omitted.offset} chars=${omitted.chars} lines=${omitted.lines} 保留=${output.length} outputSaved=${part.outputSaved === true}`
    })
    await step('外观层：计划（protocol.md「计划」，落地方案 B5）——模型写的任务清单和目标；新一轮开始时任务清单被清空', async () => {
      const planned = valueOf(await conn.call('session/create', { request: {} }, { actor: ACTOR }))?.sessionId
      const empty = await facade.plan(ACTOR, planned)
      if (empty.goal !== null || empty.steps !== null) throw new Error(`新会话不应当有计划：${short(empty)}`)
      await prompt(conn, planned, '请 TODO 一下')
      const withSteps = await untilAsync(async () => {
        const plan = await facade.plan(ACTOR, planned)
        return plan.steps?.length === TODO_ITEMS.length ? plan : undefined
      }, '任务清单出现')
      const expected = TODO_ITEMS.map((t) => ({ text: t.content, status: t.status }))
      if (JSON.stringify(withSteps.steps) !== JSON.stringify(expected)) throw new Error(`任务清单不对：${short(withSteps)}`)
      await untilAsync(async () => (await facade.messages(ACTOR, planned, { limit: 50 })).turns.at(-1)?.result !== undefined, '这一轮结束')
      // 轮数上限 1：DSH 自动续跑一轮后，目标受阻，原因是 round-limit
      await prompt(conn, planned, '请 GOAL 一下')
      const blocked = await untilAsync(async () => {
        const plan = await facade.plan(ACTOR, planned)
        return plan.goal?.status === 'blocked' ? plan : undefined
      }, '目标受阻', 60_000)
      if (blocked.goal?.objective !== GOAL_OBJECTIVE) throw new Error(`目标描述不对：${short(blocked.goal)}`)
      if (blocked.goal?.blocked?.code !== 'round-limit') throw new Error(`受阻原因不对：${short(blocked.goal)}`)
      if (blocked.steps !== null) throw new Error(`新一轮开始后任务清单应当被清空：${short(blocked.steps)}`)
      return short(blocked, 200)
    })
    await step('外观层：假模型返回错误（FAIL），这一轮的结果是 failed，带上错误说明', async () => {
      await prompt(conn, sid, '请 FAIL 一次')
      try {
        await untilAsync(async () => {
          const page = await facade.messages(ACTOR, sid, { limit: 20 })
          return page.turns.at(-1)?.result === 'failed'
        }, '这一轮以失败结束', 30_000)
      } catch (error) {
        const raw = valueOf(await conn.call('session/page', { request: { address: { kind: 'session', sessionId: sid }, maxMessages: 4 } }, { actor: ACTOR }))
        const events = (/** @type {any} */ (raw)?.records ?? []).map((/** @type {any} */ r) => `${r.event.type}${r.event.type === 'turn/end' ? JSON.stringify(r.event.data.reason) : ''}${r.event.type === 'assistant/message' ? JSON.stringify(r.event.data.message?.content).slice(0, 160) : ''}${r.event.type === 'user/message' ? JSON.stringify(r.event.data.content).slice(0, 80) : ''}`)
        throw new Error(`${error instanceof Error ? error.message : error}；最近的事件：${events.slice(-12).join(' ')}`, { cause: error })
      }
      const last = (await facade.messages(ACTOR, sid, { limit: 20 })).turns.at(-1)
      if (!last?.error?.includes(FAIL_MESSAGE)) throw new Error(`错误说明里没有假模型的错误：${short(last)}`)
      const digest = await facade.refreshDigest(ACTOR, sid)
      if (digest.lastTurn?.result !== 'failed') throw new Error(`摘要的最近一轮不对：${short(digest)}`)
      return short(last, 160)
    })
    await step('【反向】外观层：不存在的会话返回 404', async () => {
      try {
        await facade.messages(ACTOR, 'session-00000000-0000-0000-0000-000000000000')
      } catch (error) {
        if (error instanceof FacadeError && error.status === 404) return
        throw error
      }
      throw new Error('应当返回 404')
    })

    // ---- 外观层（写）：新建会话、发消息、审批和提问、切换模型和权限模式
    const tracker = new AttentionTracker(conn)
    const writer = new Facade(conn, { attention: tracker })
    /** @type {string | undefined} */
    let wsid
    /** @param {string} id */
    const userTexts = async (id) => (await writer.messages(ACTOR, id, { limit: 200 })).messages
      .filter((m) => m.role === 'user').map((m) => m.parts.map((x) => (x.type === 'text' ? x.text : '')).join(''))
    /** @param {string} id */
    const toolDone = async (id) => (await writer.messages(ACTOR, id, { limit: 200 })).messages.filter((m) => JSON.stringify(m).includes(TOOL_DONE_REPLY)).length
    await step('外观层：新建会话，重复提交同一个 requestId 只发出一条消息', async () => {
      wsid = (await writer.createSession(ACTOR, {})).sessionId
      const requestId = randomUUID()
      await writer.prompt(ACTOR, wsid, { requestId, text: '你好' })
      await writer.prompt(ACTOR, wsid, { requestId, text: '你好' })
      await wait(1500)
      const texts = await userTexts(wsid)
      if (texts.filter((t) => t === '你好').length !== 1) throw new Error(`用户消息不对：${short(texts)}`)
    })
    if (!wsid) throw new Error('无法新建会话，后续检查跳过')
    const ws = wsid

    await step('外观层：提问出现在会话列表中（waiting），回答后消失；重复回答 409', async () => {
      const before = await toolDone(ws)
      await writer.prompt(ACTOR, ws, { requestId: randomUUID(), text: '请 ASK 我一个问题' })
      const question = await until(() => tracker.list(ws).find((a) => a.kind === 'question'), '提问出现')
      const { sessions } = await writer.sessions(ACTOR)
      const summary = sessions.find((x) => x.sessionId === ws)
      if (summary?.status !== 'waiting' || summary.attention[0]?.attentionId !== question.attentionId) throw new Error(`会话摘要不对：${short(summary)}`)
      await writer.answerAttention(ACTOR, question.attentionId, { answers: [{ id: 'q1', selected: ['方案B'] }] })
      try {
        await writer.answerAttention(ACTOR, question.attentionId, { answers: [{ id: 'q1', selected: ['方案A'] }] })
        throw new Error('重复回答应当返回 409')
      } catch (error) {
        if (!(error instanceof FacadeError && error.status === 409)) throw error
      }
      await untilAsync(async () => (await toolDone(ws)) > before, '回答后模型继续')
      const history = JSON.stringify((await writer.messages(ACTOR, ws, { limit: 200 })).messages)
      if (!history.includes('方案B')) throw new Error('模型收到的回答不对')
    })

    await step('外观层：审批带上要执行的命令；另一个客户端（模拟 Desktop）先批准后，手机再处理返回 409', async () => {
      const desktop = new DshConnection({ getPort: () => Number(launch.port), authenticatedUrl: (base) => { const u = new URL(base); u.searchParams.set('token', token); return u.href } })
      try {
        /** @type {import('../../src/dsh/connection.js').DshAppEvent[]} */
        const desktopEvents = []
        desktop.onEvent((e) => desktopEvents.push(e))
        await until(() => desktop.status().state === 'ready', 'desktop ready')
        const before = await toolDone(ws)
        await writer.prompt(ACTOR, ws, { requestId: randomUUID(), text: '请执行 SHELL 命令' })
        const approval = await until(() => tracker.list(ws).find((a) => a.kind === 'approval' && a.approval?.summary), '审批出现')
        if (!approval.approval?.summary?.startsWith('运行命令：echo dshremote-contract')) throw new Error(`审批摘要不对：${short(approval.approval)}`)
        const seen = await until(() => desktopEvents.find((e) => e.type === 'waterfall' && e.eventId === approval.attentionId), 'desktop 收到同一个审批')
        if (seen.type !== 'waterfall') throw new Error('unreachable')
        valueOf(await desktop.answerEvent(seen.eventId, { kind: 'result', value: 'allowed-once' }, { actor: PLUGIN_ACTOR }))
        await until(() => !tracker.get(approval.attentionId), '手机端收到 cancel')
        try {
          await writer.answerAttention(ACTOR, approval.attentionId, { decision: 'reject' })
          throw new Error('应当返回 409')
        } catch (error) {
          if (!(error instanceof FacadeError && error.status === 409)) throw error
        }
        await untilAsync(async () => (await toolDone(ws)) > before, '命令执行完')
      } finally { desktop.close() }
    })

    await step('外观层：两边几乎同时处理，只有一边成功', async () => {
      const desktop = new DshConnection({ getPort: () => Number(launch.port), authenticatedUrl: (base) => { const u = new URL(base); u.searchParams.set('token', token); return u.href } })
      try {
        /** @type {import('../../src/dsh/connection.js').DshAppEvent[]} */
        const desktopEvents = []
        desktop.onEvent((e) => desktopEvents.push(e))
        await until(() => desktop.status().state === 'ready', 'desktop ready')
        const before = await toolDone(ws)
        await writer.prompt(ACTOR, ws, { requestId: randomUUID(), text: '请再执行一次 SHELL 命令' })
        const approval = await until(() => tracker.list(ws).find((a) => a.kind === 'approval'), '审批出现')
        await until(() => desktopEvents.find((e) => e.type === 'waterfall' && e.eventId === approval.attentionId), 'desktop 收到审批')
        // Desktop 先发出回答，手机紧跟着回答：手机的回答通常在 DSH 已经处理完、但 cancel 帧还没到达插件时发出，
        // DSH 对它静默返回成功，插件要靠随后到达的 cancel 帧判断出 409
        const pcAnswer = desktop.answerEvent(approval.attentionId, { kind: 'result', value: 'allowed-once' }, { actor: PLUGIN_ACTOR })
        const [phone, pc] = await Promise.allSettled([
          writer.answerAttention(ACTOR, approval.attentionId, { decision: 'reject' }),
          pcAnswer,
        ])
        if (pc.status !== 'fulfilled' || !pc.value.ok) throw new Error('desktop 的回答调用失败')
        await untilAsync(async () => (await toolDone(ws)) > before, '这一轮结束')
        const history = (await writer.messages(ACTOR, ws, { limit: 200 })).messages
        const last = history.flatMap((m) => m.parts).filter((x) => x.type === 'tool').at(-1)
        const outcome = last?.type === 'tool' ? last.status : 'none'
        // 手机返回成功 ⇔ 拒绝生效；手机返回 409 ⇔ Desktop 的批准生效
        const phoneWon = phone.status === 'fulfilled'
        if (phoneWon && outcome !== 'rejected') throw new Error(`手机返回成功，但工具状态是 ${outcome}`)
        if (!phoneWon && !(phone.reason instanceof FacadeError && phone.reason.status === 409)) throw phone.reason
        if (!phoneWon && outcome !== 'done') throw new Error(`手机返回 409，但工具状态是 ${outcome}`)
        return phoneWon ? '手机先处理' : 'Desktop 先处理'
      } finally { desktop.close() }
    })

    await step('外观层：切换模型和权限模式，会话列表随之更新', async () => {
      await writer.selectModel(ACTOR, ws, { provider: 'deepseek-official', model: 'deepseek-v4-pro' })
      await writer.setPermissionPreset(ACTOR, ws, { preset: 'read-only' })
      const summary = await untilAsync(async () => {
        const s = (await writer.sessions(ACTOR)).sessions.find((x) => x.sessionId === ws)
        return s?.permissionPreset === 'read-only' && s.model?.model === 'deepseek-v4-pro' ? s : undefined
      }, '会话摘要更新')
      return `model=${summary.model?.model} preset=${summary.permissionPreset}`
    })

    await step('【反向】外观层：不存在的模型 400，不存在的会话 404', async () => {
      const expect = async (/** @type {Promise<unknown>} */ p, /** @type {number} */ status) => {
        try { await p } catch (error) { if (error instanceof FacadeError && error.status === status) return; throw error }
        throw new Error(`应当返回 ${status}`)
      }
      const ghost = 'session-00000000-0000-0000-0000-000000000000'
      await expect(writer.selectModel(ACTOR, ws, { provider: 'deepseek-official', model: 'no-such-model' }), 400)
      await expect(writer.prompt(ACTOR, ghost, { requestId: randomUUID(), text: 'hi' }), 404)
      await expect(writer.cancel(ACTOR, ghost), 404)
      await expect(writer.setPermissionPreset(ACTOR, ghost, { preset: 'read-only' }), 404)
    })
    tracker.close()

    // ---- cookie 失效后自动重新登录：换一个 DshConnection 模拟插件重启（启动 token 应可重复使用）
    await step('启动 token 可以重复登录', async () => {
      const second = new DshConnection({ getPort: () => Number(launch.port), authenticatedUrl: (base) => { const u = new URL(base); u.searchParams.set('token', token); return u.href } })
      try {
        valueOf(await second.call('session/list', { _request: {} }, { actor: PLUGIN_ACTOR }))
      } finally { second.close() }
    })
  } catch (error) {
    record('运行契约测试', false, error instanceof Error ? error.message : String(error))
  } finally {
    dsh?.close()
    await web?.stop()
    await llm.close()
  }
  const ok = results.length > 0 && results.every((r) => r.ok)
  console.log(JSON.stringify({ check: 'contract', dshVersion: version, platform: process.platform, ok, results }))
  process.exitCode = ok ? 0 : 1
}

await main()
