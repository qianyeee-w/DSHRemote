// @ts-check
/**
 * 外观层：把 protocol.md 第 4 节的操作翻译成 DSH 的接口调用，并把结果转换成 protocol.md 第 5 节的结构。
 *
 * 路由（src/server/routes.js）只调用这里，不知道 DSH 的接口名、参数名和数据格式（ADR 0007）。
 * DSH 的数据格式见 docs/research/dsh-session-api.md，测试数据是从真实 DSH 录制的（test/fixtures/）。
 */
import { convertPlan } from './plan.js'
import { convertUsage } from './usage.js'
import { DshUnavailableError } from './connection.js'
import { convertPage, turnBefore, turnDigest } from './transcript.js'
import { canonicalDirectory, createDirectory, DirectoryError, listDirectories } from '../directories.js'

/** 历史消息分页：默认条数和上限 */
export const MESSAGES_DEFAULT_LIMIT = 50
export const MESSAGES_MAX_LIMIT = 200
/** 计算最近一轮的结果时读取的消息数：一轮通常不会超过这么多条 */
const DIGEST_PAGE_SIZE = 40

/** 读取工作区列表时，等待 workspace/follow 首帧的时间 */
const WORKSPACE_TIMEOUT_MS = 10_000

/**
 * 外观层的业务错误：路由把它原样转成 HTTP 错误（protocol.md 第 7 节）。
 * message 是给人看的说明，不包含 DSH 的原始错误信息。
 */
export class FacadeError extends Error {
  /** @param {number} status @param {string} code @param {string} message */
  constructor(status, code, message) {
    super(message)
    this.name = 'FacadeError'
    this.status = status
    this.code = code
  }
}

/** @param {unknown} value @returns {value is Record<string, any>} */
const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value)
/** @param {unknown} value */
const stringOrNull = (value) => (typeof value === 'string' ? value : null)

/**
 * @typedef {object} AttentionSummary
 * @property {string} attentionId
 * @property {'approval' | 'question'} kind
 */

/**
 * @typedef {object} SessionSummary
 * @property {string} sessionId
 * @property {string | null} title
 * @property {string | null} workspaceId
 * @property {string | null} cwd
 * @property {'running' | 'waiting' | 'idle'} status
 * @property {AttentionSummary[]} attention
 * @property {{ provider: string, model: string } | null} model 下一条消息会使用的模型
 * @property {string | null} permissionPreset 当前的权限模式
 * @property {number} updatedAt
 * @property {TurnDigest['lastTurn']} lastTurn 最近一轮已经结束的结果（protocol.md 5.1）
 * @property {string | null} lastReply 那一轮模型最后一段回复的开头
 */

/** @typedef {ReturnType<typeof turnDigest>} TurnDigest */

/**
 * @typedef {object} WorkspaceState
 * @property {{ workspaceId: string, title: string, path: string, sessionIds: string[] }[]} items
 * @property {Set<string>} archived
 */

/**
 * 解析 workspace/follow 的 baseline 帧。
 * @param {unknown} frame
 * @returns {WorkspaceState}
 */
export function parseWorkspaceBaseline(frame) {
  const value = isObject(frame) && frame.type === 'baseline' && isObject(frame.value) ? frame.value : {}
  const items = (Array.isArray(value.items) ? value.items : []).filter(isObject)
    .filter((item) => typeof item.workspaceId === 'string')
    .map((item) => ({
      workspaceId: String(item.workspaceId),
      title: typeof item.title === 'string' ? item.title : '',
      path: typeof item.path === 'string' ? item.path : '',
      sessionIds: (Array.isArray(item.sessionIds) ? item.sessionIds : []).filter((id) => typeof id === 'string'),
    }))
  const archived = new Set((Array.isArray(value.archivedSessionIds) ? value.archivedSessionIds : []).filter((id) => typeof id === 'string'))
  return { items, archived }
}

/**
 * projections.values.modelSelection → 下一条消息会使用的模型（next 优先，其次 lastUsed）。
 * @param {unknown} selection
 */
function modelOf(selection) {
  const s = isObject(selection) ? selection : {}
  for (const candidate of [s.next, s.lastUsed]) {
    if (isObject(candidate) && typeof candidate.provider === 'string' && typeof candidate.model === 'string') {
      return { provider: candidate.provider, model: candidate.model }
    }
  }
  return null
}

/** @type {Record<SessionSummary['status'], number>} */
const STATUS_ORDER = { waiting: 0, running: 1, idle: 2 }

/**
 * 把 session/list 的结果转换成会话摘要（protocol.md 5.1），并按「waiting → running → idle」、
 * 同一状态内 updatedAt 倒序排序。已归档的会话不出现在列表中（和 Desktop 的会话列表一致）。
 * @param {unknown} list session/list 的返回值
 * @param {WorkspaceState} workspaces
 * @param {(sessionId: string) => AttentionSummary[]} attentionOf 会话当前的待处理事项
 * @param {(sessionId: string) => TurnDigest | undefined} [digestOf] 会话最近一轮的结果（插件读过日志末尾时才有）
 * @returns {SessionSummary[]}
 */
export function convertSessionList(list, workspaces, attentionOf, digestOf = () => undefined) {
  /** @type {Map<string, string>} */
  const workspaceOf = new Map()
  for (const workspace of workspaces.items) {
    for (const sessionId of workspace.sessionIds) workspaceOf.set(sessionId, workspace.workspaceId)
  }
  const items = isObject(list) && Array.isArray(list.items) ? list.items : []
  /** @type {SessionSummary[]} */
  const sessions = []
  for (const item of items) {
    if (!isObject(item) || typeof item.sessionId !== 'string') continue
    if (workspaces.archived.has(item.sessionId)) continue
    const values = isObject(item.projections) && isObject(item.projections.values) ? item.projections.values : {}
    const attention = attentionOf(item.sessionId)
    sessions.push({
      sessionId: item.sessionId,
      title: stringOrNull(values.title),
      workspaceId: workspaceOf.get(item.sessionId) ?? null,
      cwd: stringOrNull(item.cwd) ?? stringOrNull(values.workingDirectory),
      status: attention.length > 0 ? 'waiting' : item.running === true ? 'running' : 'idle',
      attention,
      model: modelOf(values.modelSelection),
      permissionPreset: isObject(values.permissions) ? stringOrNull(values.permissions.currentValue) : null,
      updatedAt: typeof item.updatedAt === 'number' ? item.updatedAt : 0,
      lastTurn: digestOf(item.sessionId)?.lastTurn ?? null,
      lastReply: digestOf(item.sessionId)?.lastReply ?? null,
    })
  }
  return sessions.sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status] || b.updatedAt - a.updatedAt)
}

/**
 * session/modelCatalog → 模型列表。
 * @param {unknown} catalog
 */
export function convertModelCatalog(catalog) {
  const value = isObject(catalog) ? catalog : {}
  const fallback = isObject(value.default) && typeof value.default.provider === 'string' && typeof value.default.model === 'string'
    ? { provider: value.default.provider, model: value.default.model }
    : null
  /** @type {{ provider: string, providerName: string, model: string, name: string, description?: string }[]} */
  const models = []
  for (const group of Array.isArray(value.groups) ? value.groups : []) {
    if (!isObject(group) || typeof group.id !== 'string') continue
    for (const model of Array.isArray(group.models) ? group.models : []) {
      if (!isObject(model) || typeof model.id !== 'string') continue
      /** @type {{ provider: string, providerName: string, model: string, name: string, description?: string }} */
      const entry = {
        provider: group.id,
        providerName: typeof group.name === 'string' ? group.name : group.id,
        model: model.id,
        name: typeof model.name === 'string' ? model.name : model.id,
      }
      if (typeof model.description === 'string') entry.description = model.description
      models.push(entry)
    }
  }
  return { default: fallback, models }
}

/**
 * permissionPresets/catalog → 权限模式列表。
 * @param {unknown} catalog
 */
export function convertPermissionPresets(catalog) {
  const value = isObject(catalog) ? catalog : {}
  const presets = (Array.isArray(value.options) ? value.options : [])
    .filter((o) => isObject(o) && typeof o.value === 'string')
    .map((o) => ({ id: String(o.value), name: typeof o.name === 'string' ? o.name : String(o.value) }))
  return { default: stringOrNull(value.defaultPreset), presets }
}

/**
 * DSH 的业务错误码 → 外观层错误。没有列出的错误码一律 502 DSH_ERROR。
 * @type {Record<string, [number, string, string]>}
 */
const DSH_ERRORS = {
  'session/not-found': [404, 'NOT_FOUND', '会话不存在'],
  'workspace/not-found': [404, 'NOT_FOUND', '工作区不存在'],
  'workspace/invalid-path': [422, 'DIRECTORY_UNAVAILABLE', 'DSH 无法使用这个目录作为工作区'],
  'session/model-unavailable': [400, 'BAD_REQUEST', '这个模型当前不可用'],
  'gateway/bad-request': [400, 'BAD_REQUEST', 'DSH 拒绝了这个请求，请检查内容'],
}

/**
 * @param {import('./connection.js').RemoteResult} result
 */
function unwrap(result) {
  if (result.ok) return result.value
  const mapped = DSH_ERRORS[result.error.code]
  if (mapped) throw new FacadeError(mapped[0], mapped[1], mapped[2])
  // DSH 的原始错误信息可能包含路径等细节，不转发给 App
  throw new FacadeError(502, 'DSH_ERROR', `DSH 无法完成这个操作（${result.error.code}）`)
}

/** DSH 会话 id 的格式（只用来拦截明显非法的输入，不代表 DSH 的全部约束） */
const SESSION_ID = /^[A-Za-z0-9_-]{1,128}$/
/** 工作区 id、请求 id 的格式 */
const OPAQUE_ID = /^[A-Za-z0-9_-]{1,128}$/
/** 单条消息的长度上限（字符） */
export const PROMPT_TEXT_LIMIT = 100_000
/** 记住最近发出的消息的 requestId，用来去重：最多多少条、保留多久 */
const PROMPT_DEDUPE_MAX = 1_000
const PROMPT_DEDUPE_TTL_MS = 10 * 60_000

/** @param {string} sessionId */
function assertSessionId(sessionId) {
  if (!SESSION_ID.test(sessionId)) throw new FacadeError(404, 'NOT_FOUND', '会话不存在')
}
/** @param {string} message */
const badRequest = (message) => new FacadeError(400, 'BAD_REQUEST', message)

/** 目录异常在外观层转换，不把系统错误带到路由或日志。 @template T @param {() => Promise<T>} action */
async function directoryOperation(action) {
  try { return await action() } catch (error) {
    if (error instanceof DirectoryError) throw new FacadeError(error.status, error.code, error.message)
    throw error
  }
}

export class Facade {
  #dsh
  #attentionOf

  #attention
  #now
  /** @type {Map<string, { at: number, done: Promise<unknown> }>} sessionId + requestId → 发送结果 */
  #recentPrompts = new Map()
  /** @type {Map<string, TurnDigest>} sessionId → 最近一轮的结果（只在内存里，插件重启后重新读取） */
  #digests = new Map()

  /**
   * @param {import('./connection.js').DshConnection} dsh
   * @param {{ attention?: import('./attention.js').AttentionTracker, now?: () => number }} [options] attention：待处理事项的跟踪器
   */
  constructor(dsh, options = {}) {
    this.#dsh = dsh
    this.#now = options.now ?? Date.now
    this.#attention = options.attention
    const tracker = options.attention
    /** @type {(sessionId: string) => AttentionSummary[]} */
    this.#attentionOf = tracker
      ? (sessionId) => tracker.list(sessionId).map(({ attentionId, kind }) => ({ attentionId, kind }))
      : () => []
  }

  /**
   * 读取 workspace/follow 的首帧（baseline）后立即关闭这条流。
   * @param {string} actor
   * @returns {Promise<WorkspaceState>}
   */
  async #workspaceState(actor) {
    return new Promise((resolve, reject) => {
      /** @type {import('./connection.js').StreamHandle | undefined} */
      let handle
      let settled = false
      /** @param {() => void} fn */
      const finish = (fn) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        handle?.cancel()
        fn()
      }
      const timer = setTimeout(() => finish(() => reject(new DshUnavailableError('读取工作区超时'))), WORKSPACE_TIMEOUT_MS)
      this.#dsh.openStream('workspace/follow', {}, {
        onItem: (value) => finish(() => resolve(parseWorkspaceBaseline(value))),
        onEnd: () => finish(() => reject(new DshUnavailableError('工作区流提前结束'))),
        onError: (error) => finish(() => reject(error.code === 'carrier-lost'
          ? new DshUnavailableError('与 DSH 的连接断开')
          : new FacadeError(502, 'DSH_ERROR', `DSH 无法完成这个操作（${error.code}）`))),
      }, { actor }).then((h) => { handle = h; if (settled) h.cancel() }, (error) => finish(() => reject(error)))
    })
  }

  /** @param {string} actor */
  async workspaces(actor) {
    const state = await this.#workspaceState(actor)
    return { workspaces: state.items.map(({ workspaceId, title, path }) => ({ workspaceId, title, path })) }
  }

  /** @param {string} _actor @param {unknown} [path] */
  async directories(_actor, path) {
    return directoryOperation(() => listDirectories(path))
  }

  /** @param {string} _actor @param {unknown} body */
  async createDirectory(_actor, body) {
    const b = isObject(body) ? body : {}
    return directoryOperation(() => createDirectory(b.path, b.name))
  }

  /** 登记实际目录；重复调用由官方接口按路径解析到已有工作区。 @param {string} actor @param {unknown} body */
  async registerWorkspace(actor, body) {
    const b = isObject(body) ? body : {}
    const path = await directoryOperation(() => canonicalDirectory(b.path))
    const value = unwrap(await this.#dsh.call('workspace/create', { request: { path } }, { actor }))
    const workspace = isObject(value) && isObject(value.workspace) ? value.workspace : undefined
    if (!isObject(value) || !workspace || typeof workspace.workspaceId !== 'string' || typeof workspace.title !== 'string'
      || typeof workspace.path !== 'string' || typeof value.created !== 'boolean') {
      throw new FacadeError(502, 'DSH_ERROR', 'DSH 没有返回有效的工作区')
    }
    return { workspace: { workspaceId: workspace.workspaceId, title: workspace.title, path: workspace.path }, created: value.created }
  }

  /** @param {string} actor */
  async sessions(actor) {
    const [list, workspaces] = await Promise.all([
      this.#dsh.call('session/list', { _request: {} }, { actor }),
      this.#workspaceState(actor),
    ])
    return { sessions: convertSessionList(unwrap(list), workspaces, this.#attentionOf, (id) => this.#digests.get(id)) }
  }

  /**
   * 读取会话日志的末尾，更新这个会话最近一轮的结果（protocol.md 5.1 的 lastTurn、lastReply）。
   * 任务结束时调用一次；插件启动后对最近更新的会话补算。
   * @param {string} actor
   * @param {string} sessionId
   */
  async refreshDigest(actor, sessionId) {
    assertSessionId(sessionId)
    const page = unwrap(await this.#dsh.call('session/page', { request: { address: { kind: 'session', sessionId }, maxMessages: DIGEST_PAGE_SIZE } }, { actor }))
    const records = isObject(page) && Array.isArray(page.records) ? page.records : []
    const digest = turnDigest(records)
    this.#digests.set(sessionId, digest)
    return digest
  }

  /** 已经读过的最近一轮的结果。 @param {string} sessionId */
  digest(sessionId) {
    return this.#digests.get(sessionId)
  }

  /** 是否已经读过这个会话的日志末尾。 @param {string} sessionId */
  hasDigest(sessionId) {
    return this.#digests.has(sessionId)
  }

  /**
   * 用量与上下文（protocol.md 第 4 节「用量」）。DSH 说投影暂时不可用时两项都为 null。
   * @param {string} actor @param {string} sessionId
   */
  async usage(actor, sessionId) {
    assertSessionId(sessionId)
    const result = await this.#dsh.call('session/projections', { request: { sessionId } }, { actor })
    if (!result.ok && result.error.code === 'session/projections-unavailable') return convertUsage(null)
    return convertUsage(unwrap(result))
  }

  /**
   * 计划（protocol.md 第 4 节「计划」）：目标和这一轮的任务清单。DSH 说投影暂时不可用时两项都为 null。
   * @param {string} actor @param {string} sessionId
   */
  async plan(actor, sessionId) {
    assertSessionId(sessionId)
    const result = await this.#dsh.call('session/projections', { request: { sessionId } }, { actor })
    if (!result.ok && result.error.code === 'session/projections-unavailable') return convertPlan(null)
    return convertPlan(unwrap(result))
  }

  /** @param {string} actor */
  async models(actor) {
    return convertModelCatalog(unwrap(await this.#dsh.call('session/modelCatalog', {}, { actor })))
  }

  /** @param {string} actor */
  async permissionPresets(actor) {
    return convertPermissionPresets(unwrap(await this.#dsh.call('permissionPresets/catalog', {}, { actor })))
  }

  /**
   * 历史消息分页（protocol.md 第 4 节）。
   * @param {string} actor
   * @param {string} sessionId
   * @param {{ before?: string | undefined, limit?: number | undefined }} [options] before 是上一页返回的 cursor
   */
  async messages(actor, sessionId, options = {}) {
    assertSessionId(sessionId)
    const limit = options.limit ?? MESSAGES_DEFAULT_LIMIT
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > MESSAGES_MAX_LIMIT) {
      throw new FacadeError(400, 'BAD_REQUEST', `limit 必须是 1 到 ${MESSAGES_MAX_LIMIT} 之间的整数`)
    }
    /** @type {Record<string, unknown>} */
    const request = { address: { kind: 'session', sessionId }, maxMessages: limit }
    if (options.before !== undefined) {
      if (!/^(0|[1-9][0-9]{0,15})$/.test(options.before)) throw new FacadeError(400, 'BAD_REQUEST', 'cursor 格式不对')
      request.beforeSeq = Number(options.before)
    }
    const page = unwrap(await this.#dsh.call('session/page', { request }, { actor }))
    const converted = convertPage(isObject(page) ? page : {})
    if (converted.cursor === null || converted.messages.every((m) => m.turn !== undefined)) return converted
    // 这一页开头的消息推断不出轮次（同一轮前面的事件都在更早的一页）：往前多读一小段，找到最近一个带 turn 的事件
    const before = unwrap(await this.#dsh.call('session/page', {
      request: { address: { kind: 'session', sessionId }, maxMessages: 1, beforeSeq: Number(converted.cursor) },
    }, { actor }))
    const previous = isObject(before) && Array.isArray(before.records) ? before.records : []
    return convertPage(isObject(page) ? page : {}, turnBefore(previous))
  }

  // ---------------------------------------------------------------- 写操作（T8）

  /**
   * 新建会话。
   * @param {string} actor @param {unknown} body `{ workspaceId? }`
   */
  async createSession(actor, body) {
    const b = isObject(body) ? body : {}
    /** @type {Record<string, unknown>} */
    const request = {}
    if (b.workspaceId !== undefined) {
      if (typeof b.workspaceId !== 'string' || !OPAQUE_ID.test(b.workspaceId)) throw badRequest('workspaceId 格式不对')
      request.workspaceId = b.workspaceId
    }
    const value = unwrap(await this.#dsh.call('session/create', { request }, { actor }))
    if (!isObject(value) || typeof value.sessionId !== 'string') throw new FacadeError(502, 'DSH_ERROR', 'DSH 没有返回会话 id')
    return { sessionId: value.sessionId }
  }

  /**
   * 发送消息。requestId 由 App 生成，插件按它去重：App 因为网络问题重试时，同一条消息只发出一次。
   * DSH 0.2.2-rc.1 实测并不按 requestId 去重（docs/research/dsh-session-api.md 5.7），所以由插件来做。
   * 去重记录只在内存中，保留 10 分钟；DSH 返回错误时删掉记录，允许重试。
   * @param {string} actor @param {string} sessionId @param {unknown} body `{ requestId, text, mode }`
   */
  async prompt(actor, sessionId, body) {
    assertSessionId(sessionId)
    const b = isObject(body) ? body : {}
    if (typeof b.requestId !== 'string' || !OPAQUE_ID.test(b.requestId)) throw badRequest('requestId 格式不对')
    if (typeof b.text !== 'string' || b.text.trim() === '') throw badRequest('text 不能为空')
    if (b.text.length > PROMPT_TEXT_LIMIT) throw badRequest(`text 不能超过 ${PROMPT_TEXT_LIMIT} 个字符`)
    const mode = b.mode ?? 'queue'
    if (mode !== 'queue' && mode !== 'steer') throw badRequest('mode 必须是 queue 或 steer')
    const key = `${sessionId}\n${b.requestId}`
    const now = this.#now()
    for (const [k, entry] of this.#recentPrompts) {
      if (now - entry.at <= PROMPT_DEDUPE_TTL_MS && this.#recentPrompts.size <= PROMPT_DEDUPE_MAX) break
      this.#recentPrompts.delete(k) // Map 按插入顺序，最早的在前
    }
    const existing = this.#recentPrompts.get(key)
    if (existing) {
      await existing.done
      return { accepted: true }
    }
    const done = this.#dsh.call('session/prompt', {
      request: { requestId: b.requestId, sessionId, mode, content: [{ type: 'text', text: b.text }] },
    }, { actor }).then(unwrap)
    const entry = { at: now, done }
    this.#recentPrompts.set(key, entry)
    try {
      await done
    } catch (error) {
      if (this.#recentPrompts.get(key) === entry) this.#recentPrompts.delete(key)
      throw error
    }
    return { accepted: true }
  }

  /**
   * 停止当前任务。会话空闲时也返回成功。
   * @param {string} actor @param {string} sessionId
   */
  async cancel(actor, sessionId) {
    assertSessionId(sessionId)
    unwrap(await this.#dsh.call('session/cancel', { request: { sessionId } }, { actor }))
    return { accepted: true }
  }

  /**
   * 切换模型。
   * @param {string} actor @param {string} sessionId @param {unknown} body `{ provider, model }`
   */
  async selectModel(actor, sessionId, body) {
    assertSessionId(sessionId)
    const b = isObject(body) ? body : {}
    if (typeof b.provider !== 'string' || !OPAQUE_ID.test(b.provider)) throw badRequest('provider 格式不对')
    if (typeof b.model !== 'string' || !/^[A-Za-z0-9_.:/-]{1,128}$/.test(b.model)) throw badRequest('model 格式不对')
    const value = unwrap(await this.#dsh.call('session/selectModel', { request: { sessionId, provider: b.provider, model: b.model } }, { actor }))
    const selected = isObject(value) ? value.selected : undefined
    return { model: modelOf({ next: selected }) }
  }

  /**
   * 切换权限模式。DSH 没有单独的接口，Web 界面是执行 `/permission <preset>` 命令（ui-permission-presets）。
   * 这里只拼出这一条命令，preset 必须在 DSH 给出的列表中，**不提供执行任意命令的能力**（安全红线第 8 条）。
   * @param {string} actor @param {string} sessionId @param {unknown} body `{ preset }`
   */
  async setPermissionPreset(actor, sessionId, body) {
    assertSessionId(sessionId)
    const b = isObject(body) ? body : {}
    if (typeof b.preset !== 'string' || !/^[a-z][a-z0-9-]{0,63}$/.test(b.preset)) throw badRequest('preset 格式不对')
    const { presets } = await this.permissionPresets(actor)
    if (!presets.some((p) => p.id === b.preset)) throw badRequest('没有这个权限模式')
    const value = unwrap(await this.#dsh.call('commands/execute', {
      agentId: sessionId, line: `/permission ${b.preset}`, submittedAttachments: [],
    }, { actor }))
    const result = isObject(value) && isObject(value.result) ? value.result : undefined
    if (!result) throw new FacadeError(502, 'DSH_ERROR', 'DSH 没有提供切换权限模式的命令')
    if (result.kind !== 'success') throw new FacadeError(502, 'DSH_ERROR', 'DSH 没有切换权限模式')
    return { permissionPreset: b.preset }
  }

  /**
   * 处理审批或回答提问（protocol.md 5.3）。
   * @param {string} actor @param {string} attentionId @param {unknown} body
   */
  async answerAttention(actor, attentionId, body) {
    if (!this.#attention) throw new DshUnavailableError('待处理事项跟踪器没有启动')
    if (!OPAQUE_ID.test(attentionId)) throw new FacadeError(404, 'NOT_FOUND', '待处理事项不存在')
    return this.#attention.answer(actor, attentionId, body)
  }

  /** 当前所有待处理事项（T9 的 hello 使用）。 */
  attention() {
    return this.#attention?.list() ?? []
  }
}
