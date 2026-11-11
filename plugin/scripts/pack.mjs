// @ts-check
/**
 * 生成插件安装包 dshremote-plugin-<版本>.tgz（M3 T3）。
 *
 *   npm run pack [-- --out <目录>]      默认输出到 plugin/dist/
 *
 * 安装包是 npm 格式的压缩包：官方 Desktop「添加插件」或 `dsh plugin add` 直接填它的绝对路径即可安装，不需要解压。
 * 运行时依赖（ws）打包在里面，安装时不需要联网。为什么用 .tgz、不用 zip 或目录，见 docs/research/dsh-plugin-install.md。
 *
 * 做的事：
 *   1. 把 package.json 的 files、README.md 和运行时依赖（从 node_modules 复制）放进临时目录；
 *   2. 写入去掉开发依赖和脚本、声明 bundleDependencies 的 package.json；
 *   3. npm pack，检查包里的文件（不能有测试、脚本、开发依赖、密钥和证书），不通过时删除安装包并失败。
 *
 * 输出：stdout 最后一行 JSON {file, version, sha256}。在 GitHub Actions 中还会写入步骤输出 package 和 sha256。
 * 运行前先 npm ci（需要 node_modules 中的运行时依赖）。
 */
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { appendFileSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import { checkPackFiles, packageFileName, stagedManifest } from './pack-lib.mjs'

const PLUGIN_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const { values: args } = parseArgs({ options: { out: { type: 'string' } } })
const outDir = resolve(args.out ?? join(PLUGIN_DIR, 'dist'))

/** @param {string} path */
const readJson = (path) => JSON.parse(readFileSync(path, 'utf8'))

/**
 * 运行时依赖及其依赖（npm 的扁平 node_modules 布局）。
 * @param {Record<string, string>} direct
 * @returns {string[]}
 */
function runtimeClosure(direct) {
  const seen = new Set()
  const queue = Object.keys(direct)
  while (queue.length > 0) {
    const name = /** @type {string} */ (queue.shift())
    if (seen.has(name)) continue
    const manifestPath = join(PLUGIN_DIR, 'node_modules', name, 'package.json')
    if (!existsSync(manifestPath)) throw new Error(`node_modules 中没有运行时依赖 ${name}，先运行 npm ci`)
    seen.add(name)
    queue.push(...Object.keys(readJson(manifestPath).dependencies ?? {}))
  }
  return [...seen].sort()
}

/** @param {string[]} npmArgs @param {string} cwd */
function npm(npmArgs, cwd) {
  const npmCli = process.env.npm_execpath
  if (npmCli && npmCli.endsWith('.js')) return execFileSync(process.execPath, [npmCli, ...npmArgs], { cwd, encoding: 'utf8' })
  return execFileSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', npmArgs, { cwd, encoding: 'utf8', shell: process.platform === 'win32' })
}

const manifest = readJson(join(PLUGIN_DIR, 'package.json'))
const fileName = packageFileName(manifest.version)
/** @type {string[]} */
const files = manifest.files ?? []
const closure = runtimeClosure(manifest.dependencies ?? {})
const staging = mkdtempSync(join(tmpdir(), 'dshremote-pack-'))
try {
  for (const entry of [...files, 'README.md']) {
    const from = join(PLUGIN_DIR, entry)
    if (existsSync(from)) cpSync(from, join(staging, entry), { recursive: true })
    else if (entry !== 'README.md') throw new Error(`package.json 的 files 中的 ${entry} 不存在`)
  }
  for (const name of closure) cpSync(join(PLUGIN_DIR, 'node_modules', name), join(staging, 'node_modules', name), { recursive: true })
  const staged = stagedManifest(manifest)
  writeFileSync(join(staging, 'package.json'), `${JSON.stringify(staged, null, 2)}\n`)

  mkdirSync(outDir, { recursive: true })
  const [packed] = JSON.parse(npm(['pack', '--json', '--pack-destination', outDir], staging))
  const packedPath = join(outDir, packed.filename)
  // 打包的依赖只声明直接依赖，它们的依赖由 npm 按 node_modules 一起打进去，所以检查时按整个闭包放行
  const problems = checkPackFiles(packed.files.map((/** @type {{ path: string }} */ f) => f.path), { files, bundled: closure })
  if (problems.length > 0) {
    rmSync(packedPath, { force: true })
    throw new Error(`安装包内容检查不通过：\n  ${problems.join('\n  ')}`)
  }
  const target = join(outDir, fileName)
  if (packedPath !== target) renameSync(packedPath, target)
  const sha256 = createHash('sha256').update(readFileSync(target)).digest('hex')
  console.error(`[pack] ${target}（${packed.files.length} 个文件，打包的依赖：${closure.join('、') || '无'}）`)
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `package=${target}\nsha256=${sha256}\n`)
  console.log(JSON.stringify({ file: target, version: manifest.version, sha256 }))
} finally {
  rmSync(staging, { recursive: true, force: true })
}
