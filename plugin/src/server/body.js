// @ts-check
/**
 * 读取并解析 JSON 请求体：限制大小、要求 Content-Type 为 application/json。
 */

export class BodyError extends Error {
  /**
   * @param {number} status
   * @param {string} code
   * @param {string} message
   */
  constructor(status, code, message) {
    super(message)
    this.name = 'BodyError'
    this.status = status
    this.code = code
  }
}

/** 默认的请求体上限。各接口可以传入更小的值。 */
export const DEFAULT_BODY_LIMIT = 16 * 1024

/**
 * @param {import('node:http').IncomingMessage} req
 * @param {{ limit?: number }} [options]
 * @returns {Promise<unknown>}
 */
export async function readJsonBody(req, options = {}) {
  const limit = options.limit ?? DEFAULT_BODY_LIMIT
  const mediaType = String(req.headers['content-type'] ?? '').split(';', 1)[0]?.trim().toLowerCase()
  if (mediaType !== 'application/json') {
    req.resume()
    throw new BodyError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Content-Type 必须是 application/json')
  }
  const declared = Number(req.headers['content-length'])
  if (Number.isFinite(declared) && declared > limit) {
    req.resume()
    throw new BodyError(413, 'PAYLOAD_TOO_LARGE', '请求体过大')
  }

  /** @type {Buffer[]} */
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += /** @type {Buffer} */ (chunk).length
    if (size > limit) {
      req.resume()
      throw new BodyError(413, 'PAYLOAD_TOO_LARGE', '请求体过大')
    }
    chunks.push(/** @type {Buffer} */ (chunk))
  }
  const text = Buffer.concat(chunks).toString('utf8')
  try {
    return JSON.parse(text)
  } catch {
    throw new BodyError(400, 'BAD_REQUEST', '请求体不是合法的 JSON')
  }
}

/**
 * 取出一个必填的字符串字段，并做长度和字符检查（去掉首尾空白，拒绝控制字符）。
 * @param {unknown} body
 * @param {string} key
 * @param {{ max: number, pattern?: RegExp }} rule
 * @returns {string}
 */
export function requireString(body, key, rule) {
  const value = typeof body === 'object' && body !== null && !Array.isArray(body)
    ? /** @type {Record<string, unknown>} */ (body)[key]
    : undefined
  if (typeof value !== 'string') throw new BodyError(400, 'BAD_REQUEST', `缺少字段 ${key}`)
  const trimmed = value.trim()
  // eslint-disable-next-line no-control-regex
  if (trimmed.length === 0 || trimmed.length > rule.max || /[\u0000-\u001f\u007f]/.test(trimmed)) {
    throw new BodyError(400, 'BAD_REQUEST', `字段 ${key} 的格式不正确`)
  }
  if (rule.pattern && !rule.pattern.test(trimmed)) throw new BodyError(400, 'BAD_REQUEST', `字段 ${key} 的格式不正确`)
  return trimmed
}
