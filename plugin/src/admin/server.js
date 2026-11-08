// @ts-check
/**
 * 本机配对页服务：只监听 127.0.0.1。
 *
 * 访问控制（docs/plans/m1-plugin.md 4.2、docs/security.md）：
 * 1. 只接受来自回环地址的连接。
 * 2. Host 必须是 127.0.0.1:<端口> 或 localhost:<端口>：挡住 DNS rebinding（恶意网页把自己的域名解析到 127.0.0.1）。
 * 3. 会改变状态的请求（POST）还必须同时满足：
 *    - Origin 是配对页自己的地址（浏览器跨站提交表单时一定会带上真实的 Origin）；
 *    - 表单里带有正确的 CSRF token（每次插件启动随机生成，只出现在配对页里）；
 *    - Content-Type 是 application/x-www-form-urlencoded。
 * 4. 响应禁止被嵌入框架（防点击劫持），禁止缓存，禁止发送 Referer。
 */
import http from 'node:http'
import { randomBytes, timingSafeEqual } from 'node:crypto'
import { hostname } from 'node:os'
import { renderAdminPage } from './page.js'
import { lanAddresses } from './addresses.js'

const FORM_BODY_LIMIT = 4 * 1024
const SECURITY_HEADERS = Object.freeze({
  'cache-control': 'no-store',
  'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
  'x-frame-options': 'DENY',
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
  'cross-origin-resource-policy': 'same-origin',
})

/** @param {string | undefined} address */
const isLoopback = (address) => address === '127.0.0.1' || address === '::ffff:127.0.0.1' || address === '::1'

/**
 * @param {http.ServerResponse} res
 * @param {number} status
 * @param {string} text
 * @param {Record<string, string>} [headers]
 */
function sendText(res, status, text, headers = {}) {
  res.writeHead(status, { ...SECURITY_HEADERS, 'content-type': 'text/plain; charset=utf-8', ...headers })
  res.end(text)
}

/**
 * @param {http.IncomingMessage} req
 * @returns {Promise<URLSearchParams | undefined>} 超过大小限制时返回 undefined
 */
async function readForm(req) {
  /** @type {Buffer[]} */
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += /** @type {Buffer} */ (chunk).length
    if (size > FORM_BODY_LIMIT) { req.resume(); return undefined }
    chunks.push(/** @type {Buffer} */ (chunk))
  }
  return new URLSearchParams(Buffer.concat(chunks).toString('utf8'))
}

/**
 * @typedef {object} AdminServerOptions
 * @property {number} port
 * @property {import('../pairing/codes.js').PairingCodes} codes
 * @property {import('../pairing/devices.js').DeviceRegistry} devices
 * @property {() => { fingerprint: string, lanPort: number, lanBind: string, pluginVersion: string, dsh: import('./page.js').DshView }} info
 * @property {(event: string, detail?: Record<string, string>) => void} [audit]
 * @property {() => import('./addresses.js').LanAddress[]} [addresses] 测试时注入
 * @property {string} [host] 监听地址，只用于测试；默认且只应为 127.0.0.1
 */

/**
 * @typedef {object} AdminServer
 * @property {string} url 配对页地址，例如 http://127.0.0.1:3191/
 * @property {() => number} port
 * @property {() => Promise<void>} close
 */

/**
 * @param {AdminServerOptions} options
 * @returns {Promise<AdminServer>}
 */
export async function startAdminServer(options) {
  const csrfToken = randomBytes(32).toString('base64url')
  const csrfBuffer = Buffer.from(csrfToken, 'utf8')
  const listenHost = options.host ?? '127.0.0.1'
  /** 操作结果提示，只显示一次 */
  /** @type {string | undefined} */
  let notice

  const server = http.createServer({ maxHeaderSize: 8 * 1024 }, (req, res) => {
    handle(req, res).catch(() => {
      if (!res.headersSent) sendText(res, 500, '配对页内部错误')
      else res.destroy()
    })
  })

  const port = () => {
    const address = server.address()
    return typeof address === 'object' && address !== null ? address.port : options.port
  }
  const allowedHosts = () => [`127.0.0.1:${port()}`, `localhost:${port()}`]
  const allowedOrigins = () => allowedHosts().map((h) => `http://${h}`)

  /** @param {string | undefined} candidate */
  function csrfMatches(candidate) {
    if (typeof candidate !== 'string') return false
    const buffer = Buffer.from(candidate, 'utf8')
    return buffer.length === csrfBuffer.length && timingSafeEqual(buffer, csrfBuffer)
  }

  /** @param {URL} url @param {import('./addresses.js').LanAddress[]} list */
  function selectedAddress(url, list) {
    const requested = url.searchParams.get('addr')
    if (requested && list.some((a) => a.address === requested)) return requested
    return list[0]?.address
  }

  /** @param {URL} url */
  const redirectHome = (url) => {
    const addr = url.searchParams.get('addr')
    return addr ? `/?addr=${encodeURIComponent(addr)}` : '/'
  }

  /**
   * @param {http.IncomingMessage} req
   * @param {http.ServerResponse} res
   */
  async function handle(req, res) {
    // 1. 只接受回环连接
    if (!isLoopback(req.socket.remoteAddress)) { req.resume(); sendText(res, 403, 'forbidden'); return }
    // 2. Host 校验（DNS rebinding）
    const hostHeader = String(req.headers.host ?? '').toLowerCase()
    if (!allowedHosts().includes(hostHeader)) { req.resume(); sendText(res, 403, '请通过 http://127.0.0.1 访问配对页'); return }

    const url = new URL(req.url ?? '/', `http://${hostHeader}`)

    if (req.method === 'GET' && url.pathname === '/') {
      const info = options.info()
      const list = (options.addresses ?? (() => lanAddresses(info.lanBind)))()
      const html = renderAdminPage({
        csrfToken,
        pluginVersion: info.pluginVersion,
        dsh: info.dsh,
        hostName: hostname(),
        fingerprint: info.fingerprint,
        lanPort: info.lanPort,
        lanBind: info.lanBind,
        addresses: list,
        selectedAddress: selectedAddress(url, list),
        pairing: options.codes.status(),
        devices: options.devices.list(),
        notice,
      })
      notice = undefined
      res.writeHead(200, { ...SECURITY_HEADERS, 'content-type': 'text/html; charset=utf-8' })
      res.end(html)
      return
    }

    const actions = ['/pair-code', '/pair-code/cancel', '/devices/revoke']
    if (!actions.includes(url.pathname)) { req.resume(); sendText(res, 404, 'not found'); return }
    if (req.method !== 'POST') { req.resume(); sendText(res, 405, 'method not allowed', { allow: 'POST' }); return }

    // 3. CSRF：Origin + Content-Type + token，三项都要满足
    const origin = req.headers.origin
    if (typeof origin !== 'string' || !allowedOrigins().includes(origin.toLowerCase())) {
      req.resume()
      options.audit?.('admin.csrf-rejected', { reason: 'origin' })
      sendText(res, 403, '请求来源不正确')
      return
    }
    const mediaType = String(req.headers['content-type'] ?? '').split(';', 1)[0]?.trim().toLowerCase()
    if (mediaType !== 'application/x-www-form-urlencoded') { req.resume(); sendText(res, 415, 'unsupported media type'); return }
    const form = await readForm(req)
    if (!form) { sendText(res, 413, 'payload too large'); return }
    if (!csrfMatches(form.get('csrf') ?? undefined)) {
      options.audit?.('admin.csrf-rejected', { reason: 'token' })
      sendText(res, 403, '页面已过期，请刷新配对页后重试')
      return
    }

    if (url.pathname === '/pair-code') {
      options.codes.generate()
      options.audit?.('admin.pair-code-generated')
      notice = '已生成新的配对码'
    } else if (url.pathname === '/pair-code/cancel') {
      options.codes.clear()
      notice = '已取消配对码'
    } else {
      const deviceId = form.get('deviceId') ?? ''
      const removed = await options.devices.revoke(deviceId)
      if (removed) options.audit?.('admin.device-revoked', { deviceId })
      notice = removed ? '已撤销该设备，它将无法再连接' : '设备不存在，可能已经被撤销'
    }
    // POST 之后重定向回页面，避免刷新时重复提交
    res.writeHead(303, { ...SECURITY_HEADERS, location: redirectHome(url) })
    res.end()
  }

  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(options.port, listenHost, () => { server.off('error', reject); resolve(undefined) })
  })

  return {
    get url() { return `http://127.0.0.1:${port()}/` },
    port,
    close: () => new Promise((resolve) => {
      server.close(() => resolve())
      server.closeAllConnections()
    }),
  }
}
