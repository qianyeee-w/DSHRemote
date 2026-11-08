// @ts-check
/**
 * 一次性配对码（protocol.md 2.2）。
 *
 * - 8 位，字符集去掉容易混淆的 0 / O / 1 / I / L，共 31 个字符（约 8.5×10¹¹ 种组合）。
 * - 由用户在本机配对页手动生成，5 分钟内有效，用一次就作废。
 * - 同一时间只有一个有效的配对码，生成新码时旧码立刻失效。
 * - 连续输错 5 次：当前配对码作废，配对接口锁定 1 分钟。
 * - 只保存在内存里，不落盘：DSH 重启后不存在有效的配对码。
 * - 配对码不得写入日志或出现在任何响应中（除了本机配对页）。
 */
import { randomInt, timingSafeEqual } from 'node:crypto'

export const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
export const CODE_LENGTH = 8
export const CODE_TTL_MS = 5 * 60 * 1000
export const MAX_FAILED_ATTEMPTS = 5
export const LOCK_MS = 60 * 1000

/**
 * 统一输入格式：去掉空格和连字符，转成大写。
 * @param {string} input
 */
export function normalizeCode(input) {
  return input.replace(/[\s-]/g, '').toUpperCase()
}

/**
 * @typedef {{ ok: true } | { ok: false, reason: 'none' | 'invalid' | 'expired' | 'locked', retryAfterMs?: number }} VerifyResult
 */

export class PairingCodes {
  /** @type {{ code: string, expiresAt: number } | undefined} */
  #current
  #failedAttempts = 0
  #lockedUntil = 0
  #now

  /** @param {{ now?: () => number }} [options] 测试时可注入时钟 */
  constructor(options = {}) {
    this.#now = options.now ?? Date.now
  }

  /**
   * 生成新的配对码。旧码立刻失效，并解除锁定（这是用户在本机主动发起的操作）。
   * @returns {{ code: string, expiresAt: number }}
   */
  generate() {
    let code = ''
    for (let i = 0; i < CODE_LENGTH; i++) code += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)]
    this.#current = { code, expiresAt: this.#now() + CODE_TTL_MS }
    this.#failedAttempts = 0
    this.#lockedUntil = 0
    return { ...this.#current }
  }

  /** 作废当前配对码（例如用户在配对页上点了取消）。 */
  clear() {
    this.#current = undefined
  }

  /**
   * 校验并消耗配对码。成功后配对码立刻作废。
   * @param {string} input
   * @returns {VerifyResult}
   */
  verify(input) {
    const now = this.#now()
    if (now < this.#lockedUntil) return { ok: false, reason: 'locked', retryAfterMs: this.#lockedUntil - now }
    const current = this.#current
    if (!current) return { ok: false, reason: 'none' }
    if (now >= current.expiresAt) {
      this.#current = undefined
      return { ok: false, reason: 'expired' }
    }
    const candidate = Buffer.from(normalizeCode(input), 'utf8')
    const expected = Buffer.from(current.code, 'utf8')
    let matches = false
    if (candidate.length === expected.length) {
      matches = timingSafeEqual(candidate, expected)
    } else {
      // 长度不同时也做一次等长比较，避免通过响应时间推测长度
      timingSafeEqual(expected, expected)
    }
    if (matches) {
      this.#current = undefined
      this.#failedAttempts = 0
      return { ok: true }
    }
    this.#failedAttempts++
    if (this.#failedAttempts >= MAX_FAILED_ATTEMPTS) {
      this.#current = undefined
      this.#failedAttempts = 0
      this.#lockedUntil = now + LOCK_MS
      return { ok: false, reason: 'locked', retryAfterMs: LOCK_MS }
    }
    return { ok: false, reason: 'invalid' }
  }

  /**
   * 供本机配对页显示的状态（包含配对码本身，只能用于本机页面）。
   * @returns {{ code: string | undefined, expiresAt: number | undefined, lockedUntil: number | undefined }}
   */
  status() {
    const now = this.#now()
    const active = this.#current && now < this.#current.expiresAt ? this.#current : undefined
    return {
      code: active?.code,
      expiresAt: active?.expiresAt,
      lockedUntil: now < this.#lockedUntil ? this.#lockedUntil : undefined,
    }
  }
}
