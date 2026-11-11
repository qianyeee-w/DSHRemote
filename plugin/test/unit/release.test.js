// @ts-check
/**
 * 发布工作流（M3 T4）的纯函数：版本号、更新说明、APK 检查。
 * apksigner / aapt2 的输出取自 build-tools 37.0.0 的真实输出（证书指纹换成了测试值）。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  checkApk, checkVersions, parseApksigner, parseBadging, parseVersion, readGradleVersion, releaseNotes, setGradleVersion, versionCode,
} from '../../scripts/release-lib.mjs'

const CERT = 'aa'.repeat(32)
const OTHER = 'bb'.repeat(32)

/** apksigner verify --verbose --print-certs 的输出（build-tools 37.0.0） */
function apksignerOutput({ certs = [CERT], v3 = false, verifies = true } = {}) {
  const lines = [
    verifies ? 'Verifies' : 'DOES NOT VERIFY',
    'Verified using v1 scheme (JAR signing): false',
    'Verified using v2 scheme (APK Signature Scheme v2): true',
    `Verified using v3 scheme (APK Signature Scheme v3): ${v3}`,
    'Verified using v4 scheme (APK Signature Scheme v4): false',
    'Verified for SourceStamp: false',
    `Number of signers: ${certs.length}`,
  ]
  const multi = certs.length > 1
  for (const [i, cert] of certs.entries()) {
    const who = multi ? `V2 Signer #${i + 1}` : 'V2 Signer'
    lines.push(`${who}: certificate DN: CN=DSHRemote`, `${who}: certificate SHA-256 digest: ${cert}`, `${who}: certificate SHA-1 digest: ${'cc'.repeat(20)}`)
    if (v3) lines.push(`V3 Signer: certificate SHA-256 digest: ${cert}`)
  }
  return `${lines.join('\n')}\n`
}

/** aapt2 dump badging 的输出 */
function badgingOutput({ name = 'io.github.qianyeeew.dshremote', code = 10001, versionName = '0.1.0-rc.1', debuggable = false } = {}) {
  return [
    `package: name='${name}' versionCode='${code}' versionName='${versionName}' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'`,
    "sdkVersion:'26'",
    "targetSdkVersion:'35'",
    "application-label:'DSHRemote'",
    ...(debuggable ? ['application-debuggable'] : []),
    '',
  ].join('\n')
}

const goodApk = () => ({ apksigner: apksignerOutput(), badging: badgingOutput(), expectedCert: `${CERT}\n`, version: '0.1.0-rc.1' })

test('parseVersion / versionCode：和 build.gradle.kts 的公式一致', () => {
  assert.equal(versionCode('0.1.0'), 10099)
  assert.equal(versionCode('0.1.0-rc.1'), 10001)
  assert.equal(versionCode('1.2.3-rc.98'), 1020398)
  assert.equal(versionCode('2000.99.99'), 2000999999)
  assert.ok(versionCode('0.1.0-rc.98') < versionCode('0.1.0'))
  assert.ok(versionCode('0.1.0') < versionCode('0.1.1-rc.1'))
  assert.deepEqual(parseVersion('0.1.0-rc.2'), { major: 0, minor: 1, patch: 0, rc: 2, base: '0.1.0', prerelease: true })
  assert.equal(parseVersion('0.1.0').prerelease, false)
})

test('parseVersion：和 build.gradle.kts 拒绝同样的版本号', () => {
  for (const bad of ['0.1', '0.100.0', '0.1.0-rc.99', '0.1.0-rc.0', '0.1.0-rc.01', '0.1.0-beta.1', '01.1.0', 'v0.1.0', '2001.0.0', '']) {
    assert.throws(() => parseVersion(bad), /版本号/, bad)
  }
})

test('readGradleVersion / setGradleVersion：只改 dshremote.version 一行', () => {
  const text = 'org.gradle.caching=true\ndshremote.version=0.1.0\nkotlin.code.style=official\n'
  assert.equal(readGradleVersion(text), '0.1.0')
  assert.equal(setGradleVersion(text, '0.1.1-rc.1'), 'org.gradle.caching=true\ndshremote.version=0.1.1-rc.1\nkotlin.code.style=official\n')
  assert.throws(() => setGradleVersion(text, '0.1'), /版本号格式不对/)
  assert.throws(() => readGradleVersion('a=b\n'), /没有 dshremote\.version/)
})

test('readGradleVersion：仓库中的 gradle.properties 和 package.json 版本一致', () => {
  const gradle = readGradleVersion(readFileSync(new URL('../../../android/gradle.properties', import.meta.url), 'utf8'))
  const pkg = JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8')).version
  const lock = JSON.parse(readFileSync(new URL('../../package-lock.json', import.meta.url), 'utf8')).packages[''].version
  assert.equal(checkVersions({ gradle, plugin: pkg, pluginLock: lock }), gradle)
})

test('checkVersions：标签和三处版本号一致时通过', () => {
  assert.equal(checkVersions({ tag: 'v0.1.0-rc.1', gradle: '0.1.0-rc.1', plugin: '0.1.0-rc.1', pluginLock: '0.1.0-rc.1' }), '0.1.0-rc.1')
  // 手动触发的试运行没有标签
  assert.equal(checkVersions({ gradle: '0.1.0', plugin: '0.1.0', pluginLock: '0.1.0' }), '0.1.0')
})

test('checkVersions：【反向】任何一处不一致都失败，并说明是哪一处', () => {
  assert.throws(() => checkVersions({ tag: 'v0.1.1', gradle: '0.1.0', plugin: '0.1.0', pluginLock: '0.1.0' }), /标签是 v0\.1\.1/)
  assert.throws(() => checkVersions({ tag: '0.1.0', gradle: '0.1.0', plugin: '0.1.0', pluginLock: '0.1.0' }), /标签应为 v0\.1\.0/)
  assert.throws(() => checkVersions({ gradle: '0.1.0', plugin: '0.1.1', pluginLock: '0.1.1' }), /plugin\/package\.json 是 0\.1\.1/)
  assert.throws(() => checkVersions({ gradle: '0.1.0', plugin: '0.1.0', pluginLock: '0.0.9' }), /package-lock\.json 是 0\.0\.9/)
  assert.throws(() => checkVersions({ tag: 'v0.1', gradle: '0.1', plugin: '0.1', pluginLock: '0.1' }), /版本号格式不对/)
})

const CHANGELOG = `# 更新记录

说明文字。

## 0.1.1 — 2026-12-01

- 修复 B

## 0.1.0-rc.2

- rc.2 自己的说明

## 0.1.0 — 2026-11-20

第一个版本。

- 功能 A
- 功能 B

## 0.0.9

`

test('releaseNotes：取出对应的一节，不含标题，到下一个二级标题为止', () => {
  assert.equal(releaseNotes(CHANGELOG, '0.1.0'), '第一个版本。\n\n- 功能 A\n- 功能 B\n')
  assert.equal(releaseNotes(CHANGELOG, '0.1.1'), '- 修复 B\n')
})

test('releaseNotes：rc 版有自己的一节时用它，否则用对应正式版的一节', () => {
  assert.equal(releaseNotes(CHANGELOG, '0.1.0-rc.2'), '- rc.2 自己的说明\n')
  assert.equal(releaseNotes(CHANGELOG, '0.1.0-rc.1'), '第一个版本。\n\n- 功能 A\n- 功能 B\n')
})

test('releaseNotes：【反向】没有这一节或内容为空时失败', () => {
  assert.throws(() => releaseNotes(CHANGELOG, '0.2.0'), /没有 0\.2\.0 的更新说明/)
  assert.throws(() => releaseNotes(CHANGELOG, '0.2.0-rc.1'), /没有 0\.2\.0-rc\.1 或 0\.2\.0/)
  assert.throws(() => releaseNotes(CHANGELOG, '0.0.9'), /没有 0\.0\.9/)
})

test('releaseNotes：仓库中的 CHANGELOG.md 有当前版本的说明', () => {
  const changelog = readFileSync(new URL('../../../CHANGELOG.md', import.meta.url), 'utf8')
  const version = JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8')).version
  assert.ok(releaseNotes(changelog, version).length > 20)
})

test('parseApksigner：同一个证书在 V2、V3 中各出现一次时只算一个', () => {
  assert.deepEqual(parseApksigner(apksignerOutput({ v3: true })), { verified: true, v2: true, signerCount: 1, certs: [CERT] })
  const two = parseApksigner(apksignerOutput({ certs: [CERT, OTHER] }))
  assert.equal(two.signerCount, 2)
  assert.deepEqual(two.certs, [CERT, OTHER])
})

test('parseBadging：包名、版本、是否可调试', () => {
  assert.deepEqual(parseBadging(badgingOutput()), { name: 'io.github.qianyeeew.dshremote', versionCode: 10001, versionName: '0.1.0-rc.1', debuggable: false, label: 'DSHRemote' })
  assert.equal(parseBadging(badgingOutput({ debuggable: true })).debuggable, true)
  assert.throws(() => parseBadging('nothing'), /没有 package 行/)
})

test('checkApk：正常的 release 包通过（指纹大小写、末尾换行不影响）', () => {
  assert.deepEqual(checkApk(goodApk()), [])
  assert.deepEqual(checkApk({ ...goodApk(), expectedCert: CERT.toUpperCase() }), [])
})

test('checkApk：【反向】签名证书和 release-cert.sha256 不一致时失败', () => {
  const problems = checkApk({ ...goodApk(), apksigner: apksignerOutput({ certs: [OTHER] }) })
  assert.equal(problems.length, 1)
  assert.match(problems[0] ?? '', /不一致/)
})

test('checkApk：【反向】多个签名者、校验失败、release-cert.sha256 格式不对时失败', () => {
  assert.match(checkApk({ ...goodApk(), apksigner: apksignerOutput({ certs: [CERT, OTHER] }) }).join(), /签名者应为 1 个/)
  assert.match(checkApk({ ...goodApk(), apksigner: apksignerOutput({ verifies: false }) }).join(), /校验没有通过/)
  assert.match(checkApk({ ...goodApk(), apksigner: apksignerOutput({ certs: [] }) }).join(), /签名者应为 1 个/)
  assert.match(checkApk({ ...goodApk(), expectedCert: 'not-a-hash' }).join(), /不是 64 位十六进制/)
})

test('checkApk：【反向】debug 包、版本不对、可调试时失败', () => {
  assert.match(checkApk({ ...goodApk(), badging: badgingOutput({ name: 'io.github.qianyeeew.dshremote.debug' }) }).join(), /包名/)
  assert.match(checkApk({ ...goodApk(), badging: badgingOutput({ versionName: '0.1.0' }) }).join(), /versionName/)
  assert.match(checkApk({ ...goodApk(), badging: badgingOutput({ code: 10099 }) }).join(), /versionCode/)
  assert.match(checkApk({ ...goodApk(), badging: badgingOutput({ debuggable: true }) }).join(), /可调试/)
})
