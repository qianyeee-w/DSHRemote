// @ts-check
/**
 * 外观层：把 protocol.md 第 4 节的操作翻译成 DSH 的接口调用，并把结果转换成 protocol.md 第 5 节的结构。
 *
 * 路由（src/server/routes.js）只调用这里，不知道 DSH 的接口名、参数名和数据格式（ADR 0007）。
 * DSH 的数据格式见 docs/research/dsh-session-api.md，测试数据是从真实 DSH 录制的（test/fixtures/）。
 */
import { DshUnavailableError } from './connection.js'
import { convertPage } from './transcript.js'

/** 历史消息分页：默认条数和上限 */
export const MESSAGES_DEFAULT_LIMIT = 50
export const MESSAGES_MAX_LIMIT = 200
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
 * @property {number} updatedAt
 */

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

/** @type {Record<SessionSummary['status'], number>} */
const STATUS_ORDER = { waiting: 0, running: 1, idle: 2 }

/**
 * 把 session/list 的结果转换成会话摘要（protocol.md 5.1），并按「waiting → running → idle」、
 * 同一状态内 updatedAt 倒序排序。已归档的会话不出现在列表中（和 Desktop 的会话列表一致）。
 * @param {unknown} list session/list 的返回值
 * @param {WorkspaceState} workspaces
 * @param {(sessionId: string) => AttentionSummary[]} attentionOf 会话当前的待处理事项
 * @returns {SessionSummary[]}
 */
export function convertSessionList(list, workspaces, attentionOf) {
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
      updatedAt: typeof item.updatedAt === 'number' ? item.updatedAt : 0,
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
 * @param {import('./connection.js').RemoteResult} result
 * @param {{ notFound?: string }} [map] DSH 的哪个错误码对应 404
 */
function unwrap(result, map = {}) {
  if (result.ok) return result.value
  if (map.notFound && result.error.code === map.notFound) throw new FacadeError(404, 'NOT_FOUND', '会话不存在')
  // DSH 的原始错误信息可能包含路径等细节，不转发给 App
  throw new FacadeError(502, 'DSH_ERROR', `DSH 无法完成这个操作（${result.error.code}）`)
}

/** DSH 会话 id 的格式（只用来拦截明显非法的输入，不代表 DSH 的全部约束） */
const SESSION_ID = /^[A-Za-z0-9_-]{1,128}$/

export class Facade {
  #dsh
  #attentionOf

  /**
   * @param {import('./connection.js').DshConnection} dsh
   * @param {{ attentionOf?: (sessionId: string) => AttentionSummary[] }} [options] 待处理事项的来源（T8 接入）
   */
  constructor(dsh, options = {}) {
    this.#dsh = dsh
    this.#attentionOf = options.attentionOf ?? (() => [])
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

  /** @param {string} actor */
  async sessions(actor) {
    const [list, workspaces] = await Promise.all([
      this.#dsh.call('session/list', { _request: {} }, { actor }),
      this.#workspaceState(actor),
    ])
    return { sessions: convertSessionList(unwrap(list), workspaces, this.#attentionOf) }
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
    if (!SESSION_ID.test(sessionId)) throw new FacadeError(404, 'NOT_FOUND', '会话不存在')
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
    const page = unwrap(await this.#dsh.call('session/page', { request }, { actor }), { notFound: 'session/not-found' })
    return convertPage(isObject(page) ? page : {})
  }
}
