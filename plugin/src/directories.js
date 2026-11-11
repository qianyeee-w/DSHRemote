// @ts-check
/** 项目选择所需的有限目录操作；不读写文件内容，不调用 shell 或 DSH。 */
import { mkdir, opendir, realpath, stat } from 'node:fs/promises'
import { homedir } from 'node:os'
import { dirname, join, posix, win32 } from 'node:path'

export const DIRECTORY_LIMIT = 1_000
const SCAN_LIMIT = 10_000
const TIMEOUT_MS = 10_000
// 这里有意匹配控制字符，以拒绝包含它们的路径和名称。
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/u
const WINDOWS_RESERVED = /^(?:con|prn|aux|nul|conin\$|conout\$|clock\$|com[1-9¹²³]|lpt[1-9¹²³])(?:\.|$)/i

export class DirectoryError extends Error {
  /** @param {number} status @param {string} code @param {string} message */
  constructor(status, code, message) {
    super(message)
    this.name = 'DirectoryError'
    this.status = status
    this.code = code
  }
}
const invalid = () => new DirectoryError(400, 'DIRECTORY_INVALID', '请使用完整的本机绝对路径和合法的目录名称')
const unavailable = () => new DirectoryError(422, 'DIRECTORY_UNAVAILABLE', '无法访问这个目录，请检查目录是否存在及访问权限')

/** @param {unknown} value @param {string} [platform] @returns {string} */
export function validateDirectoryPath(value, platform = process.platform) {
  if (typeof value !== 'string' || value.length === 0 || value.length > 4096 || CONTROL.test(value)) throw invalid()
  if (platform === 'win32') {
    if (!/^[a-z]:[\\/]/i.test(value)) throw invalid()
    const segments = value.slice(3).split(/[\\/]/).filter(Boolean)
    if (segments.some((s) => s !== '.' && s !== '..' && (/[<>:"|?*]/.test(s) || /[ .]$/.test(s) || WINDOWS_RESERVED.test(s)))) throw invalid()
    return win32.normalize(value)
  }
  if (!posix.isAbsolute(value)) throw invalid()
  return posix.normalize(value)
}

/** 新名称在两种平台上均使用可移植的单段规则。 @param {unknown} value @returns {string} */
export function validateDirectoryName(value) {
  if (typeof value !== 'string' || value.length === 0 || value.length > 128 || value.trim() === ''
    || value === '.' || value === '..' || CONTROL.test(value) || /[\\/<>:"|?*]/.test(value)
    || /[ .]$/.test(value) || WINDOWS_RESERVED.test(value)) throw invalid()
  return value
}

/** 等待文件系统时也受超时限制；迟到的异常由 then 消费。 @template T @param {Promise<T>} operation @param {AbortSignal} signal @returns {Promise<T>} */
function waitFor(operation, signal) {
  return new Promise((resolve, reject) => {
    const abort = () => reject(unavailable())
    if (signal.aborted) { operation.catch(() => {}); abort(); return }
    signal.addEventListener('abort', abort, { once: true })
    operation.then(
      (value) => { signal.removeEventListener('abort', abort); resolve(value) },
      (error) => { signal.removeEventListener('abort', abort); reject(error) },
    )
  })
}

/** @param {unknown} path @param {AbortSignal} [signal] */
export async function canonicalDirectory(path, signal = AbortSignal.timeout(TIMEOUT_MS)) {
  const requested = validateDirectoryPath(path)
  try {
    const actual = validateDirectoryPath(await waitFor(realpath(requested), signal))
    if (!(await waitFor(stat(actual), signal)).isDirectory()) throw unavailable()
    return actual
  } catch (error) {
    if (error instanceof DirectoryError) throw error
    throw unavailable()
  }
}

/** @param {unknown} [path] */
export async function listDirectories(path) {
  const signal = AbortSignal.timeout(TIMEOUT_MS)
  const target = await canonicalDirectory(path === undefined ? homedir() : path, signal)
  /** @type {{ name: string, path: string, hidden: boolean }[]} */
  const entries = []
  let scanned = 0
  let truncated = false
  try {
    const opening = opendir(target)
    const directory = await waitFor(opening, signal).catch((error) => {
      // 超时后才打开的句柄仍需关闭。
      void opening.then((d) => d.close().catch(() => {}), () => {})
      throw error
    })
    try {
      for (;;) {
        const entry = await waitFor(directory.read(), signal)
        if (entry === null) break
        if (++scanned > SCAN_LIMIT) { truncated = true; break }
        if (!entry.isDirectory() && !entry.isSymbolicLink()) continue
        let child
        try {
          child = validateDirectoryPath(join(target, entry.name))
          if (entry.isSymbolicLink()) child = await canonicalDirectory(child, signal)
        } catch {
          if (signal.aborted) throw unavailable()
          continue
        }
        if (entries.length === DIRECTORY_LIMIT) { truncated = true; break }
        entries.push({ name: entry.name, path: child, hidden: entry.name.startsWith('.') })
      }
    } finally {
      const closing = directory.close()
      if (signal.aborted) void closing.catch(() => {})
      else await closing
    }
  } catch {
    throw unavailable()
  }
  entries.sort((a, b) => a.name.localeCompare(b.name))
  const parent = dirname(target)
  return { path: target, home: homedir(), parent: parent === target ? null : parent, entries, truncated }
}

/** @param {unknown} path @param {unknown} name */
export async function createDirectory(path, name) {
  const segment = validateDirectoryName(name)
  const signal = AbortSignal.timeout(TIMEOUT_MS)
  const parent = await canonicalDirectory(path, signal)
  const target = validateDirectoryPath(join(parent, segment))
  try {
    await waitFor(mkdir(target), signal)
  } catch (error) {
    if (typeof error === 'object' && error !== null && 'code' in error && error.code === 'EEXIST') {
      throw new DirectoryError(409, 'DIRECTORY_EXISTS', '这个名称已经被占用，请换一个名称或导入已有目录')
    }
    throw new DirectoryError(422, 'DIRECTORY_CREATE_FAILED', '无法确认目录已创建，请刷新父目录后检查结果及写入权限')
  }
  return { path: target }
}
