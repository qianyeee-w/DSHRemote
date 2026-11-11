// @ts-check
/**
 * 发布工作流（M3 T4，.github/workflows/release.yml）的纯函数：版本号、更新说明、APK 检查。
 * 规则见 ADR 0012 和 docs/release.md。
 */

/** release 包的 applicationId（debug 包另加 .debug 后缀） */
export const APPLICATION_ID = 'io.github.qianyeeew.dshremote'

/** 和 android/app/build.gradle.kts 的 releaseVersionCode 使用同一个正则 */
const VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d?)\.(0|[1-9]\d?)(?:-rc\.([1-9]\d?))?$/

/**
 * 解析版本号 X.Y.Z 或 X.Y.Z-rc.N（Y、Z ≤ 99，N 为 1–98，主版本号 ≤ 2000，不能有前导 0）。
 * 规则和 android/app/build.gradle.kts 的 releaseVersionCode 一致。
 * @param {string} version
 * @returns {{ major: number, minor: number, patch: number, rc: number | null, base: string, prerelease: boolean }}
 */
export function parseVersion(version) {
  const m = VERSION.exec(version)
  if (!m) throw new Error(`版本号格式不对：${JSON.stringify(version)}，应为 X.Y.Z 或 X.Y.Z-rc.N（Y、Z ≤ 99）`)
  const [major, minor, patch] = [Number(m[1]), Number(m[2]), Number(m[3])]
  const rc = m[4] === undefined ? null : Number(m[4])
  if (rc !== null && rc > 98) throw new Error(`版本号 ${version} 的 rc 编号最大为 98`)
  if (major > 2000) throw new Error(`版本号 ${version} 的主版本号太大`)
  return { major, minor, patch, rc, base: `${major}.${minor}.${patch}`, prerelease: rc !== null }
}

/** versionCode = X*1000000 + Y*10000 + Z*100 + (rc 的 N，正式版 99)。 @param {string} version */
export function versionCode(version) {
  const v = parseVersion(version)
  return v.major * 1_000_000 + v.minor * 10_000 + v.patch * 100 + (v.rc ?? 99)
}

/** 从 android/gradle.properties 的内容中读出 dshremote.version。 @param {string} text */
export function readGradleVersion(text) {
  const m = /^dshremote\.version=(.*)$/m.exec(text)
  if (!m) throw new Error('android/gradle.properties 中没有 dshremote.version')
  return (m[1] ?? '').trim()
}

/**
 * 检查各处的版本号一致。tag 为空时（手动触发的试运行）只检查文件之间一致。
 * @param {{ tag?: string | undefined, gradle: string, plugin: string, pluginLock: string }} sources
 * @returns {string} 版本号
 */
export function checkVersions(sources) {
  const problems = []
  if (sources.plugin !== sources.gradle) problems.push(`plugin/package.json 是 ${sources.plugin}，android/gradle.properties 是 ${sources.gradle}`)
  if (sources.pluginLock !== sources.plugin) problems.push(`plugin/package-lock.json 是 ${sources.pluginLock}，plugin/package.json 是 ${sources.plugin}`)
  if (sources.tag !== undefined && sources.tag !== `v${sources.gradle}`) problems.push(`标签是 ${sources.tag}，版本号是 ${sources.gradle}（标签应为 v${sources.gradle}）`)
  if (problems.length > 0) throw new Error(`版本号不一致：\n  ${problems.join('\n  ')}\n用 node plugin/scripts/release.mjs bump <版本> 统一修改`)
  parseVersion(sources.gradle)
  return sources.gradle
}

/**
 * 修改 gradle.properties 中的 dshremote.version。
 * @param {string} text @param {string} version
 */
export function setGradleVersion(text, version) {
  parseVersion(version)
  readGradleVersion(text)
  return text.replace(/^dshremote\.version=.*$/m, `dshremote.version=${version}`)
}

/**
 * 从 CHANGELOG.md 中取出这个版本的更新说明：标题为「## X.Y.Z」（后面可以跟日期等）的一节，到下一个二级标题为止。
 * rc 版没有自己的一节时，用它对应的正式版的一节。找不到或内容为空时报错。
 * @param {string} changelog @param {string} version
 */
export function releaseNotes(changelog, version) {
  const { base } = parseVersion(version)
  const sections = new Map()
  let current = null
  for (const line of changelog.split(/\r?\n/)) {
    const heading = /^## +v?(\S+)/.exec(line)
    if (heading) { current = heading[1]; sections.set(current, []); continue }
    if (/^# /.test(line)) { current = null; continue }
    if (current !== null) sections.get(current).push(line)
  }
  const lines = sections.get(version) ?? sections.get(base)
  const text = lines?.join('\n').trim()
  if (!text) throw new Error(`CHANGELOG.md 中没有 ${version}${version === base ? '' : ` 或 ${base}`} 的更新说明（标题格式：## ${base}）`)
  return `${text}\n`
}

/**
 * 解析 apksigner verify --verbose --print-certs 的输出。
 * 同一个证书会按签名方案（V2、V3…）各打印一次，所以按「Number of signers」计数，证书指纹去重。
 * @param {string} output
 * @returns {{ verified: boolean, v2: boolean, signerCount: number, certs: string[] }} certs 为出现过的证书 SHA-256（小写十六进制，去重）
 */
export function parseApksigner(output) {
  const certs = new Set()
  for (const m of output.matchAll(/^(?:V[\d.]+ )?Signer(?: #\d+)?: certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$/gm)) certs.add((m[1] ?? '').toLowerCase())
  const count = /^Number of signers: (\d+)\s*$/m.exec(output)
  return {
    verified: /^Verifies\s*$/m.test(output),
    v2: /^Verified using v2 scheme \(APK Signature Scheme v2\): true\s*$/m.test(output),
    signerCount: count ? Number(count[1]) : 0,
    certs: [...certs],
  }
}

/**
 * 解析 aapt2 dump badging 的输出。
 * @param {string} output
 */
export function parseBadging(output) {
  const pkg = /^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'/m.exec(output)
  if (!pkg) throw new Error('aapt2 dump badging 的输出中没有 package 行')
  return {
    name: pkg[1] ?? '',
    versionCode: Number(pkg[2]),
    versionName: pkg[3] ?? '',
    debuggable: /^application-debuggable\s*$/m.test(output),
    label: /^application-label:'([^']*)'/m.exec(output)?.[1] ?? null,
  }
}

/**
 * 检查 release APK：签名证书和仓库中记录的指纹一致、只有一个签名者、包名和版本正确、不可调试。
 * @param {{ apksigner: string, badging: string, expectedCert: string, version: string }} input
 * @returns {string[]} 问题列表，空数组表示通过
 */
export function checkApk(input) {
  const problems = []
  const expected = input.expectedCert.trim().toLowerCase()
  if (!/^[0-9a-f]{64}$/.test(expected)) problems.push('android/release-cert.sha256 的内容不是 64 位十六进制的 SHA-256')
  const signing = parseApksigner(input.apksigner)
  if (!signing.verified) problems.push('apksigner 校验没有通过')
  if (!signing.v2) problems.push('没有使用 APK Signature Scheme v2 签名')
  if (signing.signerCount !== 1 || signing.certs.length !== 1) problems.push(`签名者应为 1 个，实际 ${signing.signerCount} 个（${signing.certs.length} 个不同的证书）`)
  else if (signing.certs[0] !== expected) problems.push(`签名证书的指纹 ${signing.certs[0]} 和 android/release-cert.sha256 中的 ${expected} 不一致：GitHub Secrets 中的密钥可能被换掉了`)
  const badging = parseBadging(input.badging)
  if (badging.name !== APPLICATION_ID) problems.push(`包名是 ${badging.name}，应为 ${APPLICATION_ID}`)
  if (badging.versionName !== input.version) problems.push(`versionName 是 ${badging.versionName}，应为 ${input.version}`)
  if (badging.versionCode !== versionCode(input.version)) problems.push(`versionCode 是 ${badging.versionCode}，应为 ${versionCode(input.version)}`)
  if (badging.debuggable) problems.push('release 包不能是可调试的')
  return problems
}
