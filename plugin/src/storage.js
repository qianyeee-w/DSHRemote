// @ts-check
/**
 * 插件状态文件的读写，统一放在 <DSH_HOME>/dshremote/ 下。
 *
 * - 原子写入：先写同目录下的临时文件并 fsync，再 rename 覆盖目标。中途崩溃时，
 *   目标文件要么是旧内容、要么是新内容，不会出现写了一半的文件。
 * - 权限：POSIX 上目录 0700、文件 0600。Windows 上依赖用户目录本身的访问控制。
 * - 文件损坏时直接报错，不静默重置。例如设备列表损坏时，不能悄悄清空让所有手机失效，
 *   更不能当作「没有设备」继续运行。
 */
import { mkdir, readFile, rename, rm, readdir, chmod, open } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { randomBytes } from 'node:crypto'

const IS_WINDOWS = process.platform === 'win32'
const DIR_MODE = 0o700
const FILE_MODE = 0o600
/** 合法的文件名：只允许小写字母、数字、点、下划线、连字符，防止路径穿越。 */
const NAME_PATTERN = /^[a-z0-9][a-z0-9._-]*$/
const TEMP_SUFFIX = '.tmp'

export class StorageError extends Error {
  /** @param {string} message @param {unknown} [cause] */
  constructor(message, cause) {
    super(message, cause === undefined ? undefined : { cause })
    this.name = 'StorageError'
  }
}

/**
 * DSH 的数据目录：优先使用 DSH_HOME 环境变量，否则为 ~/.dsh。
 * @param {NodeJS.ProcessEnv} [env]
 * @returns {string}
 */
export function resolveDshHome(env = process.env) {
  const fromEnv = env.DSH_HOME
  return fromEnv && fromEnv.trim() !== '' ? fromEnv : join(homedir(), '.dsh')
}

/** @param {string} name */
function assertName(name) {
  if (!NAME_PATTERN.test(name) || name.includes('..') || name.endsWith(TEMP_SUFFIX)) {
    throw new StorageError(`非法的状态文件名：${JSON.stringify(name)}`)
  }
}

/**
 * Windows 上杀毒软件或索引服务可能短暂占用文件，rename 会失败，稍等重试几次。
 * @param {string} from @param {string} to
 */
async function renameWithRetry(from, to) {
  for (let attempt = 0; ; attempt++) {
    try {
      await rename(from, to)
      return
    } catch (error) {
      const code = /** @type {NodeJS.ErrnoException} */ (error).code
      if (!IS_WINDOWS || attempt >= 5 || (code !== 'EPERM' && code !== 'EBUSY' && code !== 'EACCES')) throw error
      await new Promise((resolve) => setTimeout(resolve, 50 * (attempt + 1)))
    }
  }
}

/** POSIX 上 rename 之后 fsync 目录，确保目录项本身也已落盘。 */
async function fsyncDir(/** @type {string} */ dir) {
  if (IS_WINDOWS) return
  const handle = await open(dir, 'r')
  try { await handle.sync() } finally { await handle.close() }
}

/**
 * @typedef {object} Storage
 * @property {string} dir 状态目录的绝对路径
 * @property {(name: string) => Promise<unknown>} readJson 读取 JSON；文件不存在时返回 undefined；内容损坏时抛出 StorageError
 * @property {(name: string, value: unknown) => Promise<void>} writeJson 原子写入 JSON
 * @property {(name: string) => Promise<string | undefined>} readText 读取文本；文件不存在时返回 undefined
 * @property {(name: string, text: string) => Promise<void>} writeText 原子写入文本
 * @property {(name: string) => Promise<void>} remove 删除文件；不存在时忽略
 */

/**
 * 打开（必要时创建）状态目录，并清理上次崩溃遗留的临时文件。
 * @param {{ dshHome?: string }} [options]
 * @returns {Promise<Storage>}
 */
export async function openStorage(options = {}) {
  const dir = join(options.dshHome ?? resolveDshHome(), 'dshremote')
  await mkdir(dir, { recursive: true, mode: DIR_MODE })
  if (!IS_WINDOWS) await chmod(dir, DIR_MODE) // 目录已存在时，mkdir 不会修改权限

  for (const entry of await readdir(dir)) {
    if (entry.endsWith(TEMP_SUFFIX)) await rm(join(dir, entry), { force: true })
  }

  /** @param {string} name @param {string} text */
  async function writeAtomic(name, text) {
    assertName(name)
    const target = join(dir, name)
    const temp = join(dir, `${name}.${process.pid}.${randomBytes(6).toString('hex')}${TEMP_SUFFIX}`)
    const handle = await open(temp, 'wx', FILE_MODE)
    try {
      await handle.writeFile(text, 'utf8')
      await handle.sync()
    } finally {
      await handle.close()
    }
    try {
      if (!IS_WINDOWS) await chmod(temp, FILE_MODE) // 不受 umask 影响
      await renameWithRetry(temp, target)
    } catch (error) {
      await rm(temp, { force: true })
      throw error
    }
    await fsyncDir(dir)
  }

  /** @param {string} name */
  async function readOptional(name) {
    assertName(name)
    try {
      return await readFile(join(dir, name), 'utf8')
    } catch (error) {
      if (/** @type {NodeJS.ErrnoException} */ (error).code === 'ENOENT') return undefined
      throw error
    }
  }

  return {
    dir,
    async readJson(name) {
      const text = await readOptional(name)
      if (text === undefined) return undefined
      try {
        return JSON.parse(text)
      } catch (error) {
        throw new StorageError(`状态文件已损坏，无法解析：${join(dir, name)}。请检查或删除该文件后重启 DSH。`, error)
      }
    },
    async writeJson(name, value) {
      const text = JSON.stringify(value, null, 2)
      if (text === undefined) throw new StorageError('不能写入无法序列化的值')
      await writeAtomic(name, `${text}\n`)
    },
    readText: readOptional,
    writeText: writeAtomic,
    async remove(name) {
      assertName(name)
      await rm(join(dir, name), { force: true })
    },
  }
}

