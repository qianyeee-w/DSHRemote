// @ts-check
/**
 * 已配对设备的登记与认证（protocol.md 2.4、第 3 节）。
 *
 * - 设备 token：32 字节随机数（base64url），只在配对响应中出现一次。
 * - 电脑端只保存 token 的 SHA-256（安全红线第 6 条）。
 * - 认证时先算哈希，再用恒定时间比较，并且总是比较完所有设备，避免通过响应时间推测。
 * - 设备列表保存在 devices.json。文件损坏或格式不对时直接报错，不会悄悄清空。
 */
import { randomBytes, createHash, timingSafeEqual } from 'node:crypto'

export const DEVICES_FILE = 'devices.json'
export const MAX_DEVICES = 20
/** lastSeenAt 最多每 10 分钟写一次盘，避免每个请求都写文件。 */
const LAST_SEEN_PERSIST_INTERVAL_MS = 10 * 60 * 1000
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/
const DEVICE_ID_PATTERN = /^d_[A-Za-z0-9_-]{12}$/
const HASH_PATTERN = /^[0-9a-f]{64}$/

export class DeviceLimitError extends Error {
  constructor() {
    super(`已配对的设备数量达到上限（${MAX_DEVICES} 台），请先在配对页撤销不用的设备`)
    this.name = 'DeviceLimitError'
  }
}

/**
 * @typedef {object} DeviceRecord
 * @property {string} deviceId
 * @property {string} name
 * @property {string} platform
 * @property {string} appVersion
 * @property {string} tokenHash SHA-256（hex）。不得出现在任何响应中
 * @property {number} createdAt
 * @property {number} lastSeenAt
 */

/** @typedef {Omit<DeviceRecord, 'tokenHash'>} DeviceInfo */

/** @param {string} token */
const hashToken = (token) => createHash('sha256').update(token, 'utf8').digest('hex')

/** @param {DeviceRecord} record @returns {DeviceInfo} */
function publicInfo({ tokenHash: _tokenHash, ...info }) {
  return info
}

/**
 * 校验 devices.json 的内容。格式不对时抛出，让插件以失败状态停下，而不是当作「没有设备」继续运行。
 * @param {unknown} data
 * @returns {DeviceRecord[]}
 */
function parseDevicesFile(data) {
  const fail = (/** @type {string} */ why) => { throw new Error(`devices.json 格式不正确：${why}。请检查或删除该文件后重启 DSH（删除后所有手机需要重新配对）`) }
  if (typeof data !== 'object' || data === null || Array.isArray(data)) fail('顶层不是对象')
  const file = /** @type {{ version?: unknown, devices?: unknown }} */ (data)
  if (file.version !== 1) fail(`不支持的版本 ${JSON.stringify(file.version)}`)
  if (!Array.isArray(file.devices)) fail('devices 不是数组')
  const records = /** @type {unknown[]} */ (file.devices)
  return records.map((raw, index) => {
    const r = /** @type {Record<string, unknown>} */ (raw ?? {})
    const ok = typeof r.deviceId === 'string' && DEVICE_ID_PATTERN.test(r.deviceId)
      && typeof r.name === 'string' && typeof r.platform === 'string' && typeof r.appVersion === 'string'
      && typeof r.tokenHash === 'string' && HASH_PATTERN.test(r.tokenHash)
      && typeof r.createdAt === 'number' && typeof r.lastSeenAt === 'number'
    if (!ok) fail(`第 ${index + 1} 条记录无效`)
    return /** @type {DeviceRecord} */ ({
      deviceId: r.deviceId, name: r.name, platform: r.platform, appVersion: r.appVersion,
      tokenHash: r.tokenHash, createdAt: r.createdAt, lastSeenAt: r.lastSeenAt,
    })
  })
}

export class DeviceRegistry {
  /** @type {Set<(deviceId: string) => void>} */
  #revokeListeners = new Set()
  /** @type {DeviceRecord[]} */
  #devices
  #storage
  #now
  /** @type {Map<string, number>} */
  #lastPersistedSeen = new Map()
  /** 写盘串行化，避免并发写入时后写的内容被先写的覆盖 */
  #writeChain = Promise.resolve()

  /**
   * @param {import('../storage.js').Storage} storage
   * @param {DeviceRecord[]} devices
   * @param {() => number} now
   */
  constructor(storage, devices, now) {
    this.#storage = storage
    this.#devices = devices
    this.#now = now
    for (const d of devices) this.#lastPersistedSeen.set(d.deviceId, d.lastSeenAt)
  }

  /**
   * @param {import('../storage.js').Storage} storage
   * @param {{ now?: () => number }} [options]
   */
  static async open(storage, options = {}) {
    const data = await storage.readJson(DEVICES_FILE)
    const devices = data === undefined ? [] : parseDevicesFile(data)
    return new DeviceRegistry(storage, devices, options.now ?? Date.now)
  }

  #persist() {
    const snapshot = { version: 1, devices: this.#devices.map((d) => ({ ...d })) }
    const write = this.#writeChain.then(() => this.#storage.writeJson(DEVICES_FILE, snapshot))
    this.#writeChain = write.catch(() => {})
    return write
  }

  /**
   * 登记新设备，返回只出现这一次的 token。
   * @param {{ name: string, platform: string, appVersion: string }} info
   * @returns {Promise<{ device: DeviceInfo, token: string }>}
   */
  async register(info) {
    if (this.#devices.length >= MAX_DEVICES) throw new DeviceLimitError()
    const token = randomBytes(32).toString('base64url')
    const now = this.#now()
    /** @type {DeviceRecord} */
    const record = {
      deviceId: `d_${randomBytes(9).toString('base64url')}`,
      name: info.name,
      platform: info.platform,
      appVersion: info.appVersion,
      tokenHash: hashToken(token),
      createdAt: now,
      lastSeenAt: now,
    }
    this.#devices = [...this.#devices, record]
    this.#lastPersistedSeen.set(record.deviceId, now)
    try {
      await this.#persist()
    } catch (error) {
      // 没有成功落盘的设备不能生效，否则 DSH 重启后这台手机会莫名失效
      this.#devices = this.#devices.filter((d) => d.deviceId !== record.deviceId)
      throw error
    }
    return { device: publicInfo(record), token }
  }

  /**
   * 用 token 认证设备。失败时返回 undefined。
   * @param {string | undefined} token
   * @returns {DeviceInfo | undefined}
   */
  authenticate(token) {
    if (typeof token !== 'string' || !TOKEN_PATTERN.test(token)) return undefined
    const candidate = Buffer.from(hashToken(token), 'hex')
    /** @type {DeviceRecord | undefined} */
    let found
    for (const device of this.#devices) {
      // 比较完所有设备再返回，耗时与是否命中无关
      if (timingSafeEqual(candidate, Buffer.from(device.tokenHash, 'hex'))) found = device
    }
    if (!found) return undefined
    this.#touch(found)
    return publicInfo(found)
  }

  /** @param {DeviceRecord} device */
  #touch(device) {
    const now = this.#now()
    device.lastSeenAt = now
    const last = this.#lastPersistedSeen.get(device.deviceId) ?? 0
    if (now - last >= LAST_SEEN_PERSIST_INTERVAL_MS) {
      this.#lastPersistedSeen.set(device.deviceId, now)
      this.#persist().catch(() => { /* lastSeenAt 只是参考信息，写盘失败不影响认证 */ })
    }
  }

  /**
   * 撤销设备。撤销后它的 token 立刻失效。
   * @param {string} deviceId
   * @returns {Promise<boolean>} 设备是否存在
   */
  async revoke(deviceId) {
    const revoked = await this.#revoke(deviceId)
    if (revoked) {
      for (const listener of this.#revokeListeners) {
        try { listener(deviceId) } catch { /* 监听者的错误不影响撤销 */ }
      }
    }
    return revoked
  }

  /**
   * 设备被撤销时通知（例如立即断开它的实时连接）。
   * @param {(deviceId: string) => void} listener
   * @returns {() => void} 取消监听
   */
  onRevoke(listener) {
    this.#revokeListeners.add(listener)
    return () => this.#revokeListeners.delete(listener)
  }

  /** @param {string} deviceId */
  async #revoke(deviceId) {
    const before = this.#devices
    if (!before.some((d) => d.deviceId === deviceId)) return false
    this.#devices = before.filter((d) => d.deviceId !== deviceId)
    this.#lastPersistedSeen.delete(deviceId)
    try {
      await this.#persist()
    } catch (error) {
      // 落盘失败时恢复，避免出现「看起来撤销了、重启后又复活」的情况
      this.#devices = before
      throw error
    }
    return true
  }

  /** @returns {DeviceInfo[]} 不含 token 哈希 */
  list() {
    return this.#devices.map(publicInfo)
  }

  /** 等待所有写盘完成（测试和卸载时使用）。 */
  async flush() {
    await this.#writeChain
  }
}
