// @ts-check
/**
 * 会话的用量与上下文（protocol.md 第 4 节「用量」，落地方案 B4）。
 * 从 DSH 的 `session/projections` 里只提取 `tokenUsage` 和 `contextPressure` 的几项，
 * 不转发其他投影（ADR 0007、调研文档 9.1）。拿不到的值省略或为 null，不用 0 代替。
 */

/**
 * @typedef {object} SessionUsage
 * @property {{ tokens?: number, window?: number } | null} context 下一次请求大约要带的 token 数（估算）和上下文上限
 * @property {{ uncachedInputTokens: number, outputTokens: number, cacheReadTokens: number, cacheWriteTokens: number } | null} total 整个会话的累计用量
 */

/** @param {unknown} value @returns {value is Record<string, any>} */
const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value)

/** @param {unknown} value @returns {value is number} */
const isCount = (value) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0

/**
 * @param {unknown} value `session/projections` 的返回值（`{ kind, values }` 或 null）
 * @returns {SessionUsage}
 */
export function convertUsage(value) {
  const values = isObject(value) && isObject(value.values) ? value.values : {}
  return { context: contextOf(values.contextPressure), total: totalOf(values.tokenUsage) }
}

/** @param {unknown} pressure */
function contextOf(pressure) {
  if (!isObject(pressure)) return null
  /** @type {{ tokens?: number, window?: number }} */
  const context = {}
  // 优先用估算的下一次请求（压缩之后会马上变小）；还没有时用最近一次请求的实际值
  const tokens = isCount(pressure.projectedTokens) ? pressure.projectedTokens : isCount(pressure.pressureTokens) ? pressure.pressureTokens : undefined
  if (tokens !== undefined) context.tokens = tokens
  if (isCount(pressure.contextWindow) && pressure.contextWindow > 0) context.window = pressure.contextWindow
  return context.tokens === undefined && context.window === undefined ? null : context
}

/** @param {unknown} usage */
function totalOf(usage) {
  if (!isObject(usage)) return null
  const { uncachedInputTokens, outputTokens, cacheReadTokens, cacheWriteTokens } = usage
  if (!isCount(uncachedInputTokens) || !isCount(outputTokens) || !isCount(cacheReadTokens) || !isCount(cacheWriteTokens)) return null
  return { uncachedInputTokens, outputTokens, cacheReadTokens, cacheWriteTokens }
}
