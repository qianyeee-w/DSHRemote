// @ts-check
/**
 * /v1/* 路由表：外观层操作的白名单（安全红线第 8 条）。
 *
 * 不在表中的路径一律 404，不在表中的方法一律 405。后续任务在这里逐个加入 protocol.md 中定义的接口。
 */
import { readFileSync } from 'node:fs'
import { DSH_BASELINE_VERSION } from '../dsh/versions.js'

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
 * @property {() => { version: string | null, compatible: boolean | null }} dshStatus 当前 DSH 的版本信息（T6 之前未知）
 */

/**
 * @typedef {object} Route
 * @property {string} method
 * @property {string} path
 * @property {boolean} auth 是否需要设备 token。只有 protocol.md 中标注「无需认证」的接口才能为 false
 * @property {(req: import('node:http').IncomingMessage, res: import('node:http').ServerResponse, ctx: RouteContext) => void | Promise<void>} handle
 */

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
])

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
    if (route.auth) {
      // 设备 token 校验在 T4 实现。在此之前，需要认证的接口一律拒绝（fail closed）
      sendError(res, 401, 'TOKEN_INVALID', '需要设备 token')
      return
    }
    Promise.resolve()
      .then(() => route.handle(req, res, ctx))
      .catch((error) => {
        options.onError?.(error)
        if (!res.headersSent) sendError(res, 500, 'INTERNAL', '插件内部错误')
        else res.destroy()
      })
  }
}
