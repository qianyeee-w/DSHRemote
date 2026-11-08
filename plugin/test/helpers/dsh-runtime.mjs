// @ts-check
/**
 * 测试用：安装指定版本的 DSH、把插件装进 web profile、启动 `dsh web`。
 * 加载检查（test/load）和契约测试（test/contract）共用。
 *
 * 环境变量：
 *   DSH_VERSION      要测试的 DSH 版本，默认为基线版本（src/dsh/versions.js）
 *   DSH_BIN          已安装的 DSH 的 JS 入口，设置后不再安装
 *   DSH_INSTALL_DIR  DSH 的安装目录，已有同版本时直接复用（CI 用它做缓存）
 *
 * 跨平台：一律用 node 直接运行 DSH 的 JS 入口，不依赖 shell 和 .cmd 包装。
 */
import { spawn, execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync, readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname, resolve, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { DSH_BASELINE_VERSION } from '../../src/dsh/versions.js'

export const PLUGIN_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
export const DSH_VERSION = process.env.DSH_VERSION || DSH_BASELINE_VERSION
const STARTUP_TIMEOUT_MS = 120_000

/** @param {string} message */
export const log = (message) => console.error(`[${basename(process.argv[1] ?? 'dsh-runtime').replace(/\.m?js$/, '')}] ${message}`)

/** npm 版的 dsh 安装插件时会调用 pnpm；找不到就尽早给出明确的提示。 */
export function assertPnpm() {
  try {
    execFileSync('pnpm', ['--version'], { encoding: 'utf8', shell: process.platform === 'win32' })
  } catch {
    throw new Error('PATH 上找不到 pnpm。npm 版的 dsh 安装插件时需要 pnpm，请先运行：npm install -g pnpm')
  }
}

/** @param {string} dir */
function readInstalledDsh(dir) {
  const pkgPath = join(dir, 'node_modules', '@deepseek-ai', 'dsh', 'package.json')
  if (!existsSync(pkgPath)) return undefined
  const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'))
  const bin = typeof pkg.bin === 'string' ? pkg.bin : pkg.bin?.dsh
  if (typeof bin !== 'string') throw new Error('找不到 @deepseek-ai/dsh 的 bin 入口')
  return { entry: join(dirname(pkgPath), bin), version: String(pkg.version) }
}

/**
 * 安装（或复用）指定版本的 DSH。
 * @param {string} fallbackDir 没有设置 DSH_INSTALL_DIR 时的安装目录
 * @returns {{ entry: string, version: string }}
 */
export function installDsh(fallbackDir) {
  if (process.env.DSH_BIN) return { entry: process.env.DSH_BIN, version: DSH_VERSION }
  const dir = process.env.DSH_INSTALL_DIR || fallbackDir
  mkdirSync(dir, { recursive: true })
  const existing = readInstalledDsh(dir)
  if (existing?.version === DSH_VERSION) {
    log(`复用已安装的 @deepseek-ai/dsh@${existing.version}`)
    return existing
  }
  writeFileSync(join(dir, 'package.json'), JSON.stringify({ name: 'dsh-test-runtime', private: true }))
  log(`安装 @deepseek-ai/dsh@${DSH_VERSION} ...`)
  const npmCli = process.env.npm_execpath
  const args = ['install', '--no-audit', '--no-fund', '--loglevel=error', `@deepseek-ai/dsh@${DSH_VERSION}`]
  if (npmCli && npmCli.endsWith('.js')) execFileSync(process.execPath, [npmCli, ...args], { cwd: dir, stdio: ['ignore', 'inherit', 'inherit'] })
  else execFileSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', args, { cwd: dir, stdio: ['ignore', 'inherit', 'inherit'], shell: process.platform === 'win32' })
  const installed = readInstalledDsh(dir)
  if (!installed) throw new Error('安装 @deepseek-ai/dsh 后找不到它的 package.json')
  return installed
}

/**
 * @typedef {object} DshWeb
 * @property {URL} launch 带启动 token 的地址
 * @property {string} dshHome
 * @property {string} workspace dsh web 的工作目录
 * @property {string} version
 * @property {string} work 临时目录（传给下一次 startDshWeb 的 reuse，可以用同一个 DSH_HOME 重启）
 * @property {(options?: { keepFiles?: boolean }) => Promise<void>} stop keepFiles：只停止进程，保留目录
 */

/**
 * 创建临时 DSH_HOME，可选地装入插件，启动 `dsh web --port 0`。
 * 传入 reuse（上一次返回的 work）时，沿用原来的 DSH_HOME 和已安装的插件，模拟 DSH 重启。
 * @param {{ withPlugin: boolean, pluginConfig?: Record<string, unknown>, env?: NodeJS.ProcessEnv, reuse?: string }} options
 * @returns {Promise<DshWeb>}
 */
export async function startDshWeb(options) {
  assertPnpm()
  const work = options.reuse ?? mkdtempSync(join(tmpdir(), 'dshremote-rt-'))
  const dshHome = join(work, 'home')
  const workspace = join(work, 'workspace')
  if (!options.reuse) { mkdirSync(dshHome); mkdirSync(workspace) }
  const env = { ...process.env, ...options.env, DSH_HOME: dshHome }
  const dsh = installDsh(join(work, 'dsh'))
  /** @type {import('node:child_process').ChildProcess | undefined} */
  let child

  /** @param {{ keepFiles?: boolean }} [stopOptions] */
  const stop = async (stopOptions = {}) => {
    if (child && child.exitCode === null) {
      if (process.platform === 'win32' && child.pid) {
        try { execFileSync('taskkill', ['/pid', String(child.pid), '/t', '/f'], { stdio: 'ignore' }) } catch { /* 可能已退出 */ }
      } else {
        child.kill('SIGINT')
        await new Promise((r) => setTimeout(r, 3000))
        if (child.exitCode === null) child.kill('SIGKILL')
      }
    }
    if (stopOptions.keepFiles) return
    try { rmSync(work, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 }) } catch { /* Windows 上文件可能仍被占用 */ }
  }

  try {
    if (options.withPlugin && !options.reuse) {
      log(`DSH ${dsh.version}，把插件装进 web profile ...`)
      execFileSync(process.execPath, [dsh.entry, 'plugin', '--profile', 'web', 'add', '-w', PLUGIN_DIR], { env, cwd: workspace, stdio: ['ignore', 'inherit', 'inherit'] })
      if (options.pluginConfig) {
        // DSH 会整行替换 config，所以要写全
        const lines = ['- id: dshremote', '  name: dshremote', '  config:', ...Object.entries(options.pluginConfig).map(([k, v]) => `    ${k}: ${v}`)]
        writeFileSync(join(dshHome, 'profiles', 'web', 'cordis.patch.yml'), `${lines.join('\n')}\n`)
      }
    }
    log('启动 dsh web --port 0 ...')
    const started = spawn(process.execPath, [dsh.entry, 'web', '--no-open', '--port', '0'], { env, cwd: workspace, stdio: ['ignore', 'pipe', 'pipe'] })
    child = started
    /** 错误信息里带出 DSH 的输出时，去掉其中的启动 token（安全红线 5） */
    const redact = (/** @type {string} */ text) => text.slice(-2000).replace(/token=[A-Za-z0-9_-]+/g, 'token=<redacted>')
    const launch = await new Promise((resolveLaunch, reject) => {
      let output = ''
      const timer = setTimeout(() => reject(new Error(`等待 dsh web 启动超时，输出：\n${redact(output)}`)), STARTUP_TIMEOUT_MS)
      /** @param {Buffer} chunk */
      const onData = (chunk) => {
        output += chunk
        const match = output.match(/http:\/\/127\.0\.0\.1:\d+\/\?token=[A-Za-z0-9_-]+/)
        if (match) { clearTimeout(timer); resolveLaunch(new URL(match[0])) }
      }
      started.stdout?.on('data', onData)
      started.stderr?.on('data', onData)
      started.on('exit', (code) => { clearTimeout(timer); reject(new Error(`dsh web 提前退出（code=${code}），输出：\n${redact(output)}`)) })
    })
    return { launch, dshHome, workspace, version: dsh.version, work, stop }
  } catch (error) {
    await stop()
    throw error
  }
}
