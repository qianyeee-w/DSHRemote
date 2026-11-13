// @ts-check
/**
 * 会话的计划（protocol.md 第 4 节「计划」，落地方案 B5）。
 * 从 DSH 的 `session/projections` 里只提取 `goal` 和 `todos` 的几项（调研文档 9.7），
 * 不转发目标的 id、修订号、轮数等其他字段。拿不到时为 null。
 */

/** 目标描述的上限（字符） */
export const GOAL_OBJECTIVE_LIMIT = 2_000
/** 受阻说明的上限（字符） */
export const GOAL_MESSAGE_LIMIT = 300
/** 每一步的上限（字符） */
export const STEP_TEXT_LIMIT = 500
/** 最多几步 */
export const STEPS_LIMIT = 100

/**
 * @typedef {object} PlanGoal
 * @property {string} objective
 * @property {'active' | 'paused' | 'blocked' | 'complete' | 'other'} status
 * @property {{ code: string, message: string }} [blocked]
 */

/**
 * @typedef {object} PlanStep
 * @property {string} text
 * @property {'pending' | 'in_progress' | 'completed'} status
 */

/**
 * @typedef {object} Plan
 * @property {PlanGoal | null} goal
 * @property {PlanStep[] | null} steps
 */

/** @param {unknown} value @returns {value is Record<string, any>} */
const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value)

/** @param {string} text @param {number} limit */
const clip = (text, limit) => (text.length > limit ? `${text.slice(0, limit - 1)}…` : text)

const GOAL_STATUSES = new Set(['active', 'paused', 'blocked', 'complete'])
const STEP_STATUSES = new Set(['pending', 'in_progress', 'completed'])

/**
 * @param {unknown} value `session/projections` 的返回值（`{ kind, values }` 或 null）
 * @returns {Plan}
 */
export function convertPlan(value) {
  const values = isObject(value) && isObject(value.values) ? value.values : {}
  return { goal: goalOf(values.goal), steps: stepsOf(values.todos) }
}

/**
 * DSH 的 goal 投影：`{ goal: { objective, phase, blockedReason? }, roundsStarted, … }`，没有目标时为 null。
 * @param {unknown} projection
 * @returns {PlanGoal | null}
 */
function goalOf(projection) {
  const goal = isObject(projection) && isObject(projection.goal) ? projection.goal : undefined
  if (!goal || typeof goal.objective !== 'string' || goal.objective.trim() === '') return null
  const status = typeof goal.phase === 'string' && GOAL_STATUSES.has(goal.phase) ? /** @type {PlanGoal['status']} */ (goal.phase) : 'other'
  /** @type {PlanGoal} */
  const result = { objective: clip(goal.objective.trim(), GOAL_OBJECTIVE_LIMIT), status }
  const reason = goal.blockedReason
  if (status === 'blocked' && isObject(reason) && typeof reason.code === 'string' && /^[a-z0-9-]{1,64}$/.test(reason.code)) {
    const message = typeof reason.message === 'string' ? clip(reason.message.replace(/\s+/g, ' ').trim(), GOAL_MESSAGE_LIMIT) : ''
    result.blocked = { code: reason.code, message }
  }
  return result
}

/**
 * DSH 的 todos 投影：`[{ content, status }]`，这一轮还没写时为 null。
 * @param {unknown} todos
 * @returns {PlanStep[] | null}
 */
function stepsOf(todos) {
  if (!Array.isArray(todos)) return null
  /** @type {PlanStep[]} */
  const steps = []
  for (const item of todos) {
    if (steps.length >= STEPS_LIMIT) break
    if (!isObject(item) || typeof item.content !== 'string' || item.content.trim() === '') continue
    const status = typeof item.status === 'string' && STEP_STATUSES.has(item.status) ? /** @type {PlanStep['status']} */ (item.status) : 'pending'
    steps.push({ text: clip(item.content.trim(), STEP_TEXT_LIMIT), status })
  }
  return steps
}
