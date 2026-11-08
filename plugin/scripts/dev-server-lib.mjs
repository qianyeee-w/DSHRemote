// @ts-check
/**
 * dev-server 用到的纯函数：参数解析、从配对页 HTML 中读取信息、终端二维码。
 * 和 CLI（dev-server.mjs）分开，方便单元测试（test/unit/dev-server.test.js）。
 */
import { homedir } from 'node:os'
import { join, resolve } from 'node:path'
import { isIPv4 } from 'node:net'
import qrcode from '../src/vendor/qrcode.mjs'

/** App 开发用的局域网端口默认值。不用插件的默认值 3190 / 3191：开发机上它们可能已经被用户自己的 DSH 占用 */
export const DEFAULT_LAN_PORT = 3290

/** 数据目录：DSHREMOTE_DEV_HOME > 平台的缓存目录。不放在仓库里。 */
export function defaultHome(env = process.env, platform = process.platform) {
  if (env.DSHREMOTE_DEV_HOME) return resolve(env.DSHREMOTE_DEV_HOME)
  const base = platform === 'win32'
    ? (env.LOCALAPPDATA ?? join(homedir(), 'AppData', 'Local'))
    : (env.XDG_CACHE_HOME ?? join(homedir(), '.cache'))
  return join(base, 'dshremote-dev')
}

/**
 * @param {string | undefined} value
 * @param {string} name
 * @returns {number | undefined}
 */
export function parsePort(value, name) {
  if (value === undefined) return undefined
  if (!/^\d{1,5}$/.test(value) || Number(value) < 1 || Number(value) > 65535) throw new Error(`${name} 必须是 1–65535 之间的整数：${value}`)
  return Number(value)
}

/**
 * 启动参数 → 端口和监听地址。
 * @param {{ port?: string | undefined, 'admin-port'?: string | undefined, bind?: string | undefined }} values
 */
export function startOptions(values) {
  const lanPort = parsePort(values.port, '--port') ?? DEFAULT_LAN_PORT
  const adminPort = parsePort(values['admin-port'], '--admin-port') ?? lanPort + 1
  if (adminPort > 65535) throw new Error('--port 为 65535 时必须另外指定 --admin-port')
  if (adminPort === lanPort) throw new Error('--port 和 --admin-port 不能相同')
  const bind = values.bind ?? '0.0.0.0'
  if (!isIPv4(bind)) throw new Error(`--bind 必须是 IPv4 地址：${bind}`)
  return { lanPort, adminPort, bind }
}

/** @param {string} text */
const unescapeHtml = (text) => text.replace(/&(amp|lt|gt|quot|#39);/g, (_, e) => ({ amp: '&', lt: '<', gt: '>', quot: '"', '#39': "'" })[/** @type {'amp'} */ (e)])

/** 配对页上的 CSRF token。 @param {string} html */
export function parseCsrf(html) {
  return html.match(/name="csrf" value="([^"]+)"/)?.[1]
}

/** 配对页上显示的配对码（去掉中间的空格），没有时为 undefined。 @param {string} html */
export function parsePairingCode(html) {
  return html.match(/class="mono big">([A-Z0-9 ]+)</)?.[1]?.replace(/ /g, '')
}

/** 插件到 DSH 的连接状态。 @param {string} html */
export function parseDshState(html) {
  return html.match(/data-state="([a-z]+)"/)?.[1]
}

/**
 * 配对页上的已配对设备列表。
 * @param {string} html
 * @returns {{ name: string, client: string, createdAt: string, lastSeenAt: string, deviceId: string }[]}
 */
export function parseDevices(html) {
  const row = /<tr>\s*<td>([^<]*)<\/td>\s*<td>([^<]*)<\/td>\s*<td>([^<]*)<\/td>\s*<td>([^<]*)<\/td>\s*<td><form method="post" action="\/devices\/revoke[^"]*">.*?name="deviceId" value="([^"]+)"/gs
  return [...html.matchAll(row)].map((m) => ({
    name: unescapeHtml(m[1] ?? ''),
    client: unescapeHtml(m[2] ?? ''),
    createdAt: unescapeHtml(m[3] ?? ''),
    lastSeenAt: unescapeHtml(m[4] ?? ''),
    deviceId: unescapeHtml(m[5] ?? ''),
  }))
}

/**
 * 用上半块字符把二维码画进终端：每个字符表示上下两个模块，前景色是上面的模块，背景色是下面的模块。
 * 显式指定黑白两色，深色和浅色主题的终端都能扫。
 * @param {string} text
 * @returns {string[]} 每一行（带 ANSI 颜色）
 */
export function renderQr(text) {
  const qr = qrcode(0, 'M')
  qr.addData(text)
  qr.make()
  const size = qr.getModuleCount()
  const margin = 2
  /** @param {number} r @param {number} c */
  const dark = (r, c) => r >= 0 && c >= 0 && r < size && c < size && qr.isDark(r, c)
  const BLACK = 16
  const WHITE = 231
  const lines = []
  for (let r = -margin; r < size + margin; r += 2) {
    let line = ''
    for (let c = -margin; c < size + margin; c += 1) {
      line += `\x1b[38;5;${dark(r, c) ? BLACK : WHITE}m\x1b[48;5;${dark(r + 1, c) ? BLACK : WHITE}m▀`
    }
    lines.push(`${line}\x1b[0m`)
  }
  return lines
}

/** 指纹每 4 个字符一组。 @param {string} fp */
export const groupFingerprint = (fp) => fp.replace(/(.{4})/g, '$1 ').trim()
