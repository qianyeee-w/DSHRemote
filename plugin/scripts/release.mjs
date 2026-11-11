// @ts-check
/**
 * 发布用的命令（M3 T4）。.github/workflows/release.yml 调用，发版前也在本地用。步骤见 docs/release.md。
 *
 *   node plugin/scripts/release.mjs bump <版本>
 *       把 android/gradle.properties、plugin/package.json、plugin/package-lock.json 中的版本号统一改成 <版本>。
 *   node plugin/scripts/release.mjs check-version [--tag vX.Y.Z]
 *       检查上面三处版本号一致；给了 --tag 时还检查标签。输出版本号；在 GitHub Actions 中写入步骤输出 version、prerelease。
 *   node plugin/scripts/release.mjs notes --version <版本> --out <文件>
 *       从 CHANGELOG.md 取出这个版本的更新说明（rc 版用对应正式版的一节）。
 *   node plugin/scripts/release.mjs check-apk --apk <文件> --version <版本>
 *       用 apksigner 和 aapt2 检查 release APK：签名证书和 android/release-cert.sha256 一致、只有一个签名者、
 *       包名、versionName、versionCode 正确、不可调试。不通过时退出码为 1。
 *       工具在 $ANDROID_HOME/build-tools/<BUILD_TOOLS_VERSION，默认 37.0.0>/ 下。
 */
import { execFileSync } from 'node:child_process'
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import { checkApk, checkVersions, parseVersion, readGradleVersion, releaseNotes, setGradleVersion } from './release-lib.mjs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const FILES = {
  gradle: join(ROOT, 'android', 'gradle.properties'),
  plugin: join(ROOT, 'plugin', 'package.json'),
  pluginLock: join(ROOT, 'plugin', 'package-lock.json'),
  changelog: join(ROOT, 'CHANGELOG.md'),
  cert: join(ROOT, 'android', 'release-cert.sha256'),
}

/** @param {string} path */
const readJson = (path) => JSON.parse(readFileSync(path, 'utf8'))
/** @param {string} path @param {unknown} value */
const writeJson = (path, value) => writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`)
/** @param {Record<string, string>} values */
function githubOutput(values) {
  if (!process.env.GITHUB_OUTPUT) return
  appendFileSync(process.env.GITHUB_OUTPUT, Object.entries(values).map(([k, v]) => `${k}=${v}\n`).join(''))
}

const [command, ...rest] = process.argv.slice(2)
const { values: args, positionals } = parseArgs({
  args: rest,
  allowPositionals: true,
  options: { tag: { type: 'string' }, version: { type: 'string' }, out: { type: 'string' }, apk: { type: 'string' } },
})

/** @param {string | undefined} value @param {string} name */
function required(value, name) {
  if (!value) throw new Error(`缺少参数 ${name}`)
  return value
}

try {
  if (command === 'bump') {
    const version = required(positionals[0], '<版本>')
    parseVersion(version)
    writeFileSync(FILES.gradle, setGradleVersion(readFileSync(FILES.gradle, 'utf8'), version))
    const pkg = readJson(FILES.plugin)
    pkg.version = version
    writeJson(FILES.plugin, pkg)
    const lock = readJson(FILES.pluginLock)
    lock.version = version
    if (lock.packages?.['']) lock.packages[''].version = version
    writeJson(FILES.pluginLock, lock)
    console.log(`版本号已改为 ${version}：android/gradle.properties、plugin/package.json、plugin/package-lock.json`)
  } else if (command === 'check-version') {
    const lock = readJson(FILES.pluginLock)
    const version = checkVersions({
      tag: args.tag,
      gradle: readGradleVersion(readFileSync(FILES.gradle, 'utf8')),
      plugin: readJson(FILES.plugin).version,
      pluginLock: lock.packages?.['']?.version ?? lock.version,
    })
    const prerelease = parseVersion(version).prerelease
    githubOutput({ version, prerelease: String(prerelease) })
    console.log(version)
  } else if (command === 'notes') {
    const version = required(args.version, '--version')
    writeFileSync(required(args.out, '--out'), releaseNotes(readFileSync(FILES.changelog, 'utf8'), version))
    console.log(`已写入 ${version} 的更新说明：${args.out}`)
  } else if (command === 'check-apk') {
    const apk = required(args.apk, '--apk')
    const version = required(args.version, '--version')
    const tools = join(required(process.env.ANDROID_HOME, 'ANDROID_HOME 环境变量'), 'build-tools', process.env.BUILD_TOOLS_VERSION || '37.0.0')
    const run = (/** @type {string} */ tool, /** @type {string[]} */ toolArgs) =>
      execFileSync(join(tools, tool), toolArgs, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
    const apksigner = run('apksigner', ['verify', '--verbose', '--print-certs', apk])
    const badging = run('aapt2', ['dump', 'badging', apk])
    const expectedCert = readFileSync(FILES.cert, 'utf8')
    const problems = checkApk({ apksigner, badging, expectedCert, version })
    if (problems.length > 0) {
      console.error(`release APK 检查不通过：\n  ${problems.join('\n  ')}`)
      process.exitCode = 1
    } else {
      console.log(`release APK 检查通过：签名证书 ${expectedCert.trim()}，版本 ${version}`)
    }
  } else {
    throw new Error('用法见 plugin/scripts/release.mjs 开头：bump、check-version、notes、check-apk')
  }
} catch (error) {
  console.error(`错误：${error instanceof Error ? error.message : String(error)}`)
  process.exitCode = 1
}
