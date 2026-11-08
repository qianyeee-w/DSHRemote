// @ts-check
/**
 * /v1/* 路由表：外观层操作的白名单（安全红线第 8 条）。
 *
 * 不在表中的路径一律 404，不在表中的方法一律 405。
 * `auth: true` 的接口必须带有效的设备 token（protocol.md 第 3 节），否则 401。
 */
import { readFileSync } from 'node:fs'
import { hostname } from 'node:os'
import { DSH_BASELINE_VERSION } from '../dsh/versions.js'
import { readJsonBody, requireString, BodyError } from './body.js'
import { DeviceLimitError } from '../pairing/devices.js'

export const PROTOCOL_VERSION = 1

const PLUGIN_VERSION = String(JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8')).version)

/** 所有 JSON 响应都带的响应头。 */
const BASE_HEADERS = Object.freeze({
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'x-content-type-options': 'nosniff',
})

/**
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {unknown} body
 */
export function sendJson(res, status, body) {
  const text = JSON.stringify(body)
  res.writeHead(status, { ...BASE_HEADERS, 'content-length': Buffer.byteLength(text) })
  res.end(text)
}

/**
 * 统一的错误格式（protocol.md 第 7 节）。message 中不得包含 token、配对码、路径等敏感信息。
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {string} code
 * @param {string} message
 * @param {Record<string, string>} [headers]
 */
export function sendError(res, status, code, message, headers = {}) {
  const text = JSON.stringify({ error: { code, message } })
  res.writeHead(status, { ...BASE_HEADERS, ...headers, 'content-length': Buffer.byteLength(text) })
  res.end(text)
}

/**
 * @typedef {object} RouteContext
 * @property {() => { version: string | null, compatible: boolean | null }} dshStatus 当前 DSH 的版本信息
 * @property {import('../pairing/codes.js').PairingCodes} codes
 * @property {import('../pairing/devices.js').DeviceRegistry} devices
 * @property {(event: string, detail?: Record<string, string>) => void} [audit] 记录安全相关事件（不含任何秘密）
 */

/**
 * @typedef {object} RequestAuth
 * @property {import('../pairing/devices.js').DeviceInfo} [device] 已认证的设备（仅 auth: true 的接口有）
 */

/**
 * @typedef {object} Route
 * @property {string} method
 * @property {string} path
 * @property {boolean} auth 是否需要设备 token。只有 protocol.md 中标注「无需认证」的接口才能为 false
 * @property {(req: import('node:http').IncomingMessage, res: import('node:http').ServerResponse, ctx: RouteContext, auth: RequestAuth) => void | Promise<void>} handle
 */

const PAIR_BODY_LIMIT = 4 * 1024

/** @param {import('node:http').ServerResponse} res @param {import('../pairing/devices.js').DeviceInfo} device */
const sendDevice = (res, device) => sendJson(res, 200, {
  deviceId: device.deviceId, name: device.name, platform: device.platform, createdAt: device.createdAt,
})

/** @type {readonly Route[]} */
export const ROUTES = Object.freeze([
  {
    method: 'GET',
    path: '/v1/info',
    auth: false,
    handle(_req, res, ctx) {
      const dsh = ctx.dshStatus()
      sendJson(res, 200, {
        protocol: PROTOCOL_VERSION,
        pluginVersion: PLUGIN_VERSION,
        dsh: { baseline: DSH_BASELINE_VERSION, version: dsh.version, compatible: dsh.compatible },
      })
    },
  },
  {
    method: 'POST',
    path: '/v1/pair',
    auth: false,
    async handle(req, res, ctx) {
      const body = await readJsonBody(req, { limit: PAIR_BODY_LIMIT })
      // 先校验所有字段，格式不对的请求不消耗配对码的尝试次数
      const code = requireString(body, 'code', { max: 32 })
      const name = requireString(body, 'deviceName', { max: 64 })
      const platform = requireString(body, 'platform', { max: 16, pattern: /^[a-z][a-z0-9-]*$/ })
      const appVersion = requireString(body, 'appVersion', { max: 32, pattern: /^[0-9A-Za-z.+-]+$/ })

      const result = ctx.codes.verify(code)
      if (!result.ok) {
        ctx.audit?.('pair.rejected', { reason: result.reason })
        if (result.reason === 'locked') {
          const seconds = String(Math.max(1, Math.ceil((result.retryAfterMs ?? 0) / 1000)))
          sendError(res, 429, 'PAIRING_LOCKED', '输错次数过多，配对暂时锁定，请稍后在电脑上重新生成配对码', { 'retry-after': seconds })
        } else {
          sendError(res, 400, 'PAIRING_CODE_INVALID', '配对码错误或已过期')
        }
        return
      }
      let registered
      try {
        registered = await ctx.devices.register({ name, platform, appVersion })
      } catch (error) {
        if (error instanceof DeviceLimitError) {
          sendError(res, 409, 'PAIRING_DEVICE_LIMIT', error.message)
          return
        }
        throw error
      }
      ctx.audit?.('pair.accepted', { deviceId: registered.device.deviceId, name })
      sendJson(res, 200, {
        deviceId: registered.device.deviceId,
        token: registered.token,
        host: { name: hostname(), pluginVersion: PLUGIN_VERSION },
      })
    },
  },
  {
    method: 'GET',
    path: '/v1/me',
    auth: true,
    handle(_req, res, _ctx, auth) {
      sendDevice(res, /** @type {import('../pairing/devices.js').DeviceInfo} */ (auth.device))
    },
  },
  {
    method: 'DELETE',
    path: '/v1/me',
    auth: true,
    async handle(_req, res, ctx, auth) {
      const device = /** @type {import('../pairing/devices.js').DeviceInfo} */ (auth.device)
      await ctx.devices.revoke(device.deviceId)
      ctx.audit?.('device.self-revoked', { deviceId: device.deviceId })
      res.writeHead(204, { 'cache-control': 'no-store' })
      res.end()
    },
  },
])

/**
 * 从 Authorization 头取出 Bearer token。格式不对时返回 undefined。
 * @param {import('node:http').IncomingMessage} req
 */
export function bearerToken(req) {
  const header = req.headers.authorization
  if (typeof header !== 'string') return undefined
  const match = /^Bearer ([A-Za-z0-9_-]+)$/.exec(header)
  return match?.[1]
}

/**
 * 根据路由表创建请求处理函数。
 * @param {RouteContext} ctx
 * @param {{ routes?: readonly Route[], onError?: (error: unknown) => void }} [options]
 * @returns {(req: import('node:http').IncomingMessage, res: import('node:http').ServerResponse) => void}
 */
export function createRouter(ctx, options = {}) {
  const routes = options.routes ?? ROUTES
  return (req, res) => {
    let path
    try {
      path = new URL(req.url ?? '/', 'https://dshremote.invalid').pathname
    } catch {
      sendError(res, 400, 'BAD_REQUEST', '请求路径无效')
      return
    }
    const candidates = routes.filter((route) => route.path === path)
    if (candidates.length === 0) {
      sendError(res, 404, 'NOT_FOUND', '接口不存在')
      return
    }
    const route = candidates.find((r) => r.method === req.method)
    if (!route) {
      sendError(res, 405, 'METHOD_NOT_ALLOWED', '不支持该请求方法', { allow: candidates.map((r) => r.method).join(', ') })
      return
    }
    /** @type {RequestAuth} */
    const auth = {}
    if (route.auth) {
      const device = ctx.devices.authenticate(bearerToken(req))
      if (!device) {
        req.resume()
        sendError(res, 401, 'TOKEN_INVALID', '设备 token 无效或已被撤销，请重新配对', { 'www-authenticate': 'Bearer' })
        return
      }
      auth.device = device
    }
    Promise.resolve()
      .then(() => route.handle(req, res, ctx, auth))
      .catch((error) => {
        if (res.headersSent) { res.destroy(); return }
        if (error instanceof BodyError) {
          sendError(res, error.status, error.code, error.message)
          return
        }
        options.onError?.(error)
        sendError(res, 500, 'INTERNAL', '插件内部错误')
      })
  }
}
