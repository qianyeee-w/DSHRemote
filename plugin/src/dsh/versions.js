// @ts-check
/**
 * DSH 版本：支持范围的唯一定义处，以及运行时版本检测。
 *
 * package.json 的 `engines.dsh` 必须和 SUPPORTED_DSH_RANGE 一致（由单元测试保证）。
 * 升级基线时只改这里和 package.json，并新写一条 ADR（见 docs/decisions/0005）。
 */
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'

/** 适配基线版本。 */
export const DSH_BASELINE_VERSION = '0.2.2-rc.1'

/** 支持的版本范围。只支持「>=最低版本 <上限版本」这一种写法。 */
export const SUPPORTED_DSH_RANGE = '>=0.2.2-rc.1 <0.3.0-0'

/** DSH 官方包统一使用同一个版本号（实测 0.2.2-rc.1 的 194 个包一致），以下任一包的版本即 DSH 版本。 */
const DSH_PACKAGE_NAMES = Object.freeze(['@deepseek-ai/dsh', '@deepseek-ai/dsh-desktop-host'])
const MAX_ASCEND = 10

/**
 * @typedef {{ major: number, minor: number, patch: number, pre: (string | number)[] }} SemVer
 */

/**
 * @param {string} text
 * @returns {SemVer | undefined}
 */
export function parseSemver(text) {
  const match = /^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/.exec(text.trim())
  if (!match) return undefined
  const pre = match[4] === undefined ? [] : match[4].split('.').map((p) => (/^\d+$/.test(p) ? Number(p) : p))
  return { major: Number(match[1]), minor: Number(match[2]), patch: Number(match[3]), pre }
}

/**
 * 按 semver 2.0 的优先级比较。
 * @param {SemVer} a
 * @param {SemVer} b
 * @returns {number} a < b 为负，相等为 0，a > b 为正
 */
export function compareSemver(a, b) {
  for (const key of /** @type {const} */ (['major', 'minor', 'patch'])) {
    if (a[key] !== b[key]) return a[key] - b[key]
  }
  // 没有预发布标识的版本优先级更高
  if (a.pre.length === 0 || b.pre.length === 0) return (a.pre.length === 0 ? 1 : 0) - (b.pre.length === 0 ? 1 : 0)
  for (let i = 0; i < Math.max(a.pre.length, b.pre.length); i++) {
    const x = a.pre[i]
    const y = b.pre[i]
    if (x === undefined) return -1
    if (y === undefined) return 1
    if (x === y) continue
    if (typeof x === 'number' && typeof y === 'number') return x - y
    if (typeof x === 'number') return -1
    if (typeof y === 'number') return 1
    return x < y ? -1 : 1
  }
  return 0
}

/**
 * 版本是否在「>=最低 <上限」范围内。
 *
 * 注意：这里按 semver 优先级直接比较，**不采用** npm 对预发布版本的特殊规则。
 * 例如 0.2.3-rc.1 在 SUPPORTED_DSH_RANGE 之内（npm 的规则会把它排除）。
 * 这样 DSH 发布新的 rc 时插件仍然可用；它是否真的兼容，由启动时的接口探测和契约测试确认。
 * @param {string} version
 * @param {string} [range]
 * @returns {boolean | undefined} 版本号无法解析时返回 undefined
 */
export function satisfiesRange(version, range = SUPPORTED_DSH_RANGE) {
  const parsed = parseSemver(version)
  if (!parsed) return undefined
  const match = /^>=(\S+) <(\S+)$/.exec(range)
  const min = match?.[1] === undefined ? undefined : parseSemver(match[1])
  const max = match?.[2] === undefined ? undefined : parseSemver(match[2])
  if (!min || !max) throw new Error(`不支持的版本范围写法：${range}`)
  return compareSemver(parsed, min) >= 0 && compareSemver(parsed, max) < 0
}

/**
 * @param {string} file
 * @returns {{ name?: unknown, version?: unknown } | undefined}
 */
function readPackageJson(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'))
  } catch {
    return undefined
  }
}

/**
 * 检测当前运行的 DSH 版本。
 *
 * 从 DSH 进程的入口脚本（process.argv[1]）所在目录逐级向上查找：
 * - 该目录的 package.json 是否就是 DSH 的包（dsh web：@deepseek-ai/dsh；官方 Desktop：@deepseek-ai/dsh-desktop-host）；
 * - 该目录下的 node_modules/@deepseek-ai/dsh/package.json。
 * 只读取文件，不访问 DSH 的任何内部对象。
 * @param {{ entry?: string | undefined }} [options]
 * @returns {{ version: string, source: string } | undefined}
 */
export function detectDshVersion(options = {}) {
  const entry = 'entry' in options ? options.entry : process.argv[1]
  if (!entry) return undefined
  let dir = dirname(resolve(entry))
  for (let i = 0; i < MAX_ASCEND; i++) {
    for (const file of [join(dir, 'package.json'), join(dir, 'node_modules', '@deepseek-ai', 'dsh', 'package.json')]) {
      const pkg = readPackageJson(file)
      if (pkg && typeof pkg.name === 'string' && DSH_PACKAGE_NAMES.includes(pkg.name)
        && typeof pkg.version === 'string' && parseSemver(pkg.version)) {
        return { version: pkg.version, source: pkg.name }
      }
    }
    const parent = dirname(dir)
    if (parent === dir) break
    dir = parent
  }
  return undefined
}

/**
 * @typedef {object} DshVersionStatus
 * @property {string | null} version 检测到的版本；检测不到时为 null
 * @property {boolean | null} compatible 是否在支持范围内；版本未知时为 null（由接口探测决定能否继续）
 */

/**
 * @param {{ entry?: string | undefined }} [options]
 * @returns {DshVersionStatus}
 */
export function dshVersionStatus(options = {}) {
  const detected = detectDshVersion(options)
  if (!detected) return { version: null, compatible: null }
  return { version: detected.version, compatible: satisfiesRange(detected.version) ?? null }
}
