// @ts-check
/**
 * DSH 新版本兼容性检测（M3 T1）的命令行入口，由 .github/workflows/dsh-compat.yml 调用，也可以在本地运行。
 *
 *   node scripts/dsh-compat.mjs candidates [--version 0.2.3] [--skip-reported]
 *       输出要检测的版本（JSON 数组）。指定 --version 时只检测这个版本，否则从 npm 的 latest、next、alpha 中选出比基线新的版本。
 *       --skip-reported 跳过已经开过 issue 的版本（用 gh），定时运行时用，避免同一个失败每天重跑、每天发失败通知。
 *   node scripts/dsh-compat.mjs run --version 0.2.3 --out <目录>
 *       用这个版本的 DSH 依次跑 load-check、contract、e2e，结果写入 <目录>/result-<版本>-<平台>.json。
 *       全部通过时退出码为 0。需要 pnpm；建议设置 DSH_INSTALL_DIR 复用下载的 DSH。
 *   node scripts/dsh-compat.mjs report --results <目录> [--run-url <链接>] [--dry-run]
 *       读取 <目录> 下（含子目录）所有结果文件，为失败的版本开 issue（用 gh，同一个版本只开一次）。
 *       --dry-run 只打印要开的 issue，不调用 gh issue create。
 */
import { execFileSync, spawn } from 'node:child_process'
import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import {
  CHECKS, DSH_PACKAGE, issueBody, issueTitle, needsIssue, parseCheckOutput, parseVersionInput, pickCandidates, runPassed,
} from './dsh-compat-lib.mjs'

const PLUGIN_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const WIN = process.platform === 'win32'

/** @param {string[]} args */
function npmViewJson(args) {
  const out = execFileSync('npm', ['view', DSH_PACKAGE, ...args, '--json'], { encoding: 'utf8', shell: WIN })
  return JSON.parse(out)
}

/** @param {string[]} args */
function gh(args) {
  return execFileSync('gh', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'] })
}

/**
 * 按标题搜索 issue（已关闭的也算），返回搜到的标题，由调用方精确比较。
 * @param {string} title
 * @returns {string[]}
 */
function findIssueTitles(title) {
  const found = JSON.parse(gh(['issue', 'list', '--state', 'all', '--limit', '100', '--search', `"${title}" in:title`, '--json', 'title']))
  return found.map((/** @type {{ title: string }} */ i) => i.title)
}

/**
 * 跑一个检测脚本：stdout 一边转发到控制台一边收集，stderr 直接转发。
 * @param {string} script
 * @param {string} version
 * @returns {Promise<{ stdout: string, code: number | null }>}
 */
function runCheck(script, version) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(process.execPath, [join(PLUGIN_DIR, script)], {
      cwd: PLUGIN_DIR,
      env: { ...process.env, DSH_VERSION: version },
      stdio: ['ignore', 'pipe', 'inherit'],
    })
    let stdout = ''
    child.stdout.setEncoding('utf8')
    child.stdout.on('data', (chunk) => { stdout += chunk; process.stdout.write(chunk) })
    child.on('error', reject)
    child.on('close', (code) => resolvePromise({ stdout, code }))
  })
}

/** @param {string} dir @returns {string[]} */
function findResultFiles(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return findResultFiles(path)
    return /^result-.*\.json$/.test(name) ? [path] : []
  })
}

async function main() {
  const [command, ...rest] = process.argv.slice(2)
  const { values } = parseArgs({
    args: rest,
    options: {
      version: { type: 'string' },
      out: { type: 'string' },
      results: { type: 'string' },
      'run-url': { type: 'string' },
      'dry-run': { type: 'boolean', default: false },
      'skip-reported': { type: 'boolean', default: false },
    },
  })

  if (command === 'candidates') {
    let versions = values.version ? [parseVersionInput(values.version)] : pickCandidates(npmViewJson(['dist-tags']))
    if (values['skip-reported']) {
      const reported = versions.filter((v) => !needsIssue(v, findIssueTitles(issueTitle(v))))
      for (const v of reported) console.error(`DSH ${v}：已有 issue「${issueTitle(v)}」，跳过`)
      versions = versions.filter((v) => !reported.includes(v))
    }
    console.log(JSON.stringify(versions))
    return
  }

  if (command === 'run') {
    if (!values.version || !values.out) throw new Error('run 需要 --version 和 --out')
    const version = parseVersionInput(values.version)
    /** @type {import('./dsh-compat-lib.mjs').CheckResult[]} */
    const checks = []
    for (const { check, script } of CHECKS) {
      console.error(`\n=== ${check}（DSH ${version}）===`)
      const { stdout, code } = await runCheck(script, version)
      checks.push(parseCheckOutput(check, stdout, code))
    }
    /** @type {import('./dsh-compat-lib.mjs').RunResult} */
    const result = { version, platform: process.platform, checks }
    mkdirSync(values.out, { recursive: true })
    writeFileSync(join(values.out, `result-${version}-${process.platform}.json`), `${JSON.stringify(result, null, 2)}\n`)
    for (const c of checks) console.error(`${c.ok ? 'PASS' : 'FAIL'}  ${c.check}`)
    process.exitCode = runPassed(result) ? 0 : 1
    return
  }

  if (command === 'report') {
    if (!values.results) throw new Error('report 需要 --results')
    /** @type {Map<string, import('./dsh-compat-lib.mjs').RunResult[]>} */
    const failedByVersion = new Map()
    for (const file of findResultFiles(values.results)) {
      /** @type {import('./dsh-compat-lib.mjs').RunResult} */
      const run = JSON.parse(readFileSync(file, 'utf8'))
      const version = parseVersionInput(run.version)
      if (runPassed(run)) { console.error(`PASS  DSH ${version}（${run.platform}）`); continue }
      failedByVersion.set(version, [...(failedByVersion.get(version) ?? []), run])
    }
    if (failedByVersion.size === 0) { console.error('没有失败的版本'); return }

    /** @type {Record<string, string>} */
    let times = {}
    try { times = npmViewJson(['time']) } catch (error) { console.error(`读取发布时间失败：${error instanceof Error ? error.message : error}`) }
    for (const [version, runs] of failedByVersion) {
      const title = issueTitle(version)
      const body = issueBody({ version, publishedAt: times[version], runUrl: values['run-url'], runs })
      if (values['dry-run']) { console.log(`--- ${title}\n${body}\n`); continue }
      if (!needsIssue(version, findIssueTitles(title))) {
        console.error(`DSH ${version}：已有 issue「${title}」，不再重复创建`)
        continue
      }
      const bodyFile = join(values.results, `issue-${version}.md`)
      writeFileSync(bodyFile, body)
      const url = gh(['issue', 'create', '--title', title, '--body-file', bodyFile]).trim()
      console.error(`DSH ${version}：已创建 issue ${url}`)
    }
    return
  }

  throw new Error(`未知的命令：${command ?? '（空）'}，可用的命令：candidates、run、report`)
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error)
  process.exitCode = 1
})
