// @ts-check
/**
 * DSH 新版本兼容性检测（M3 T1，roadmap.md 第 1 节）的纯函数：选择要检测的版本、解析检测结果、生成 issue。
 * 命令行入口是 scripts/dsh-compat.mjs，由 .github/workflows/dsh-compat.yml 调用。
 */
import { DSH_BASELINE_VERSION, SUPPORTED_DSH_RANGE, compareSemver, parseSemver, satisfiesRange } from '../src/dsh/versions.js'

export const DSH_PACKAGE = '@deepseek-ai/dsh'

/** 关注的 npm 发布渠道。 */
export const WATCHED_TAGS = Object.freeze(['latest', 'next', 'alpha'])

/** 每个版本要跑的检测：名称（结果 JSON 中的 check 字段）和脚本路径（相对 plugin/）。 */
export const CHECKS = Object.freeze([
  { check: 'load', script: 'test/load/load-check.mjs' },
  { check: 'contract', script: 'test/contract/contract.mjs' },
  { check: 'e2e', script: 'test/e2e/e2e.mjs' },
])

/** DSH 官方升级指南（不兼容的改动在 `<版本号>/` 子目录中说明）。 */
export const UPGRADE_GUIDE_URL = 'https://github.com/deepseek-ai/deepseek-harness/tree/main/docs/upgrade-guide'

const MAX_DETAIL = 300
const MAX_ITEMS = 30

/**
 * 严格校验版本号（手动触发时由用户输入，之后会用在命令行和 issue 标题里）。
 * @param {string} text
 * @returns {string}
 */
export function parseVersionInput(text) {
  const version = text.trim()
  if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?$/.test(version) || !parseSemver(version)) {
    throw new Error(`版本号格式不对：${JSON.stringify(text)}，应为 0.2.3 或 0.2.3-rc.1 这样的格式`)
  }
  return version
}

/**
 * 从 `npm view @deepseek-ai/dsh dist-tags --json` 的结果中选出要检测的版本：比基线新、去重、从旧到新。
 * @param {unknown} distTags
 * @param {string} [baseline]
 * @returns {string[]}
 */
export function pickCandidates(distTags, baseline = DSH_BASELINE_VERSION) {
  const base = parseSemver(baseline)
  if (!base) throw new Error(`基线版本号无法解析：${baseline}`)
  if (typeof distTags !== 'object' || distTags === null) throw new Error('dist-tags 的格式不对')
  /** @type {Map<string, import('../src/dsh/versions.js').SemVer>} */
  const found = new Map()
  for (const tag of WATCHED_TAGS) {
    const value = /** @type {Record<string, unknown>} */ (distTags)[tag]
    if (typeof value !== 'string') continue
    let version
    try { version = parseVersionInput(value) } catch { continue }
    const parsed = /** @type {import('../src/dsh/versions.js').SemVer} */ (parseSemver(version))
    if (compareSemver(parsed, base) > 0) found.set(version, parsed)
  }
  return [...found.entries()].sort((a, b) => compareSemver(a[1], b[1])).map(([v]) => v)
}

/**
 * @typedef {{ name: string, ok: boolean, detail?: string }} CheckItem
 * @typedef {{ check: string, ok: boolean, items: CheckItem[] }} CheckResult
 */

/**
 * 解析一个检测脚本的输出：stdout 中最后一个 check 字段相符的 JSON 行。
 * 只有 JSON 中 ok 为 true 并且退出码为 0 才算通过。
 * @param {string} check
 * @param {string} stdout
 * @param {number | null} exitCode
 * @returns {CheckResult}
 */
export function parseCheckOutput(check, stdout, exitCode) {
  const lines = stdout.split(/\r?\n/).map((l) => l.trim()).filter((l) => l.startsWith('{')).reverse()
  for (const line of lines) {
    let json
    try { json = JSON.parse(line) } catch { continue }
    if (json?.check !== check) continue
    /** @type {CheckItem[]} */
    const items = Array.isArray(json.results)
      ? json.results.filter((/** @type {any} */ r) => r && typeof r.name === 'string').map((/** @type {any} */ r) => ({
        name: r.name,
        ok: r.ok === true,
        ...(r.detail === undefined ? {} : { detail: String(r.detail) }),
      }))
      : []
    const ok = json.ok === true && exitCode === 0
    if (!ok && items.every((i) => i.ok)) {
      items.push({ name: '检测结果', ok: false, detail: `ok=${json.ok}，退出码 ${exitCode}` })
    }
    return { check, ok, items }
  }
  return { check, ok: false, items: [{ name: '检测结果', ok: false, detail: `没有输出结果（退出码 ${exitCode}），见运行日志` }] }
}

/**
 * 兜底：去掉看起来像 token、密钥的长字符串。检测结果本来就不应含秘密（安全红线 5），这里再保险一次。
 * @param {string} text
 * @returns {string}
 */
export function redact(text) {
  return text.replace(/[A-Za-z0-9_-]{32,}/g, '[已隐去]')
}

/**
 * @param {string} text
 * @param {number} [max]
 */
function short(text, max = MAX_DETAIL) {
  const oneLine = redact(text).replace(/\s+/g, ' ').trim()
  return oneLine.length > max ? `${oneLine.slice(0, max)}…` : oneLine
}

/** Markdown 表格单元格转义。 @param {string} text */
const cell = (text) => text.replace(/\|/g, '\\|')

/** @param {string} version */
export function issueTitle(version) {
  return `DSH ${version} 兼容性检测失败`
}

/**
 * 是否需要开 issue：同一个版本只开一次，已关闭的也算。
 * @param {string} version
 * @param {string[]} existingTitles
 */
export function needsIssue(version, existingTitles) {
  return !existingTitles.includes(issueTitle(version))
}

/**
 * @typedef {{ version: string, platform: string, checks: CheckResult[] }} RunResult
 */

/**
 * @param {RunResult} run
 */
export function runPassed(run) {
  return run.checks.length > 0 && run.checks.every((c) => c.ok)
}

/**
 * 生成 issue 正文。只使用检测结果中的 name、ok、detail 字段。
 * @param {{ version: string, publishedAt?: string | undefined, runUrl?: string | undefined, runs: RunResult[] }} input
 * @returns {string}
 */
export function issueBody({ version, publishedAt, runUrl, runs }) {
  const inRange = satisfiesRange(version)
  const lines = [
    `DSH \`${version}\` 的兼容性检测失败（基线 \`${DSH_BASELINE_VERSION}\`）。`,
    '',
    `- 发布时间：${publishedAt ?? '未知'}`,
    `- 是否在插件支持的范围（\`${SUPPORTED_DSH_RANGE}\`）内：${inRange ? '是。用户的 DSH 升级到这个版本后，插件会照常加载，但可能有接口不兼容' : '否。用户的 DSH 升级到这个版本后，插件会提示「DSH 版本不兼容」，需要升级基线'}`,
    `- 升级指南：${UPGRADE_GUIDE_URL}/${version}（不一定每个版本都有）`,
    ...(runUrl ? [`- 运行记录：${runUrl}`] : []),
    '',
  ]
  for (const run of runs) {
    lines.push(`## ${run.platform}`, '')
    lines.push('| 检测 | 结果 |', '|---|---|')
    for (const c of run.checks) lines.push(`| ${c.check} | ${c.ok ? '通过' : '**失败**'} |`)
    lines.push('')
    const failed = run.checks.flatMap((c) => c.items.filter((i) => !i.ok).map((i) => ({ check: c.check, ...i })))
    if (failed.length > 0) {
      lines.push('失败的项目：', '', '| 检测 | 项目 | 说明 |', '|---|---|---|')
      for (const f of failed.slice(0, MAX_ITEMS)) lines.push(`| ${f.check} | ${cell(short(f.name, 80))} | ${cell(short(f.detail ?? ''))} |`)
      if (failed.length > MAX_ITEMS) lines.push('', `还有 ${failed.length - MAX_ITEMS} 项失败，见运行记录。`)
      lines.push('')
    }
  }
  lines.push(
    '## 怎么处理',
    '',
    '1. 阅读这个版本的升级指南，确认是哪些接口变了。',
    '2. 只修改插件的 DSH 适配层（`plugin/src/dsh/`），用 `DSH_VERSION=' + version + '` 跑通 load-check、contract、e2e 和全部测试。',
    '3. 需要升级基线时，新写一条 ADR，并更新 `plugin/src/dsh/versions.js`、`package.json` 的 `engines.dsh` 和 `AGENTS.md`。',
    '',
    '本 issue 由 `.github/workflows/dsh-compat.yml` 自动创建，同一个版本只创建一次。',
  )
  return lines.join('\n')
}
