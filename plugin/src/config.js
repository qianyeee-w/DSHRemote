// @ts-check
/**
 * 插件配置校验。
 *
 * 原则：非法值直接报错（fail closed），不静默回退到默认值。
 * 报错会让 DSH 把插件标记为加载失败，用户能在 DSH 的插件列表里看到。
 */
import { isIP } from 'node:net'

/**
 * @typedef {object} PluginConfig
 * @property {boolean} enabled 是否启用
 * @property {number} lanPort 局域网 HTTPS 端口
 * @property {string} lanBind 局域网服务的监听地址（IP 字面量）
 * @property {number} adminPort 本机配对页端口（只监听 127.0.0.1）
 * @property {boolean} openAdminOnFirstRun 第一次启动时是否自动打开配对页
 */

/** @type {Readonly<PluginConfig>} */
export const DEFAULT_CONFIG = Object.freeze({
  enabled: true,
  lanPort: 3190,
  lanBind: '0.0.0.0',
  adminPort: 3191,
  openAdminOnFirstRun: true,
})

const KNOWN_KEYS = new Set(Object.keys(DEFAULT_CONFIG))

export class ConfigError extends Error {
  /** @param {string} message */
  constructor(message) {
    super(`dshremote 配置错误：${message}`)
    this.name = 'ConfigError'
  }
}

/**
 * @param {unknown} value
 * @param {string} key
 * @returns {boolean}
 */
function boolean(value, key) {
  if (typeof value !== 'boolean') throw new ConfigError(`${key} 必须是 true 或 false，收到 ${JSON.stringify(value)}`)
  return value
}

/**
 * @param {unknown} value
 * @param {string} key
 * @returns {number}
 */
function port(value, key) {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 1 || value > 65535) {
    throw new ConfigError(`${key} 必须是 1 到 65535 之间的整数，收到 ${JSON.stringify(value)}`)
  }
  return value
}

/**
 * 校验并补全配置。
 * @param {unknown} raw cordis.patch.yml 中的 config；undefined / null 表示全部用默认值
 * @returns {Readonly<PluginConfig>}
 */
export function parseConfig(raw) {
  if (raw === undefined || raw === null) return DEFAULT_CONFIG
  if (typeof raw !== 'object' || Array.isArray(raw)) throw new ConfigError('config 必须是一个对象')
  const source = /** @type {Record<string, unknown>} */ (raw)
  for (const key of Object.keys(source)) {
    // 拼错的键直接报错，避免用户以为改了配置、实际没生效
    if (!KNOWN_KEYS.has(key)) throw new ConfigError(`未知的配置项 ${JSON.stringify(key)}，可用的配置项：${[...KNOWN_KEYS].join(', ')}`)
  }
  const merged = { ...DEFAULT_CONFIG, ...source }

  const lanBind = merged.lanBind
  if (typeof lanBind !== 'string' || isIP(lanBind) === 0) {
    throw new ConfigError(`lanBind 必须是 IP 地址（例如 0.0.0.0 或 192.168.1.10），不能是主机名，收到 ${JSON.stringify(lanBind)}`)
  }

  /** @type {PluginConfig} */
  const config = {
    enabled: boolean(merged.enabled, 'enabled'),
    lanPort: port(merged.lanPort, 'lanPort'),
    lanBind,
    adminPort: port(merged.adminPort, 'adminPort'),
    openAdminOnFirstRun: boolean(merged.openAdminOnFirstRun, 'openAdminOnFirstRun'),
  }
  if (config.lanPort === config.adminPort) throw new ConfigError('lanPort 和 adminPort 不能相同')
  return Object.freeze(config)
}
