// @ts-check
/**
 * DSH 兼容性检测（M3 T1）的纯函数：版本选择、检测结果解析、issue 内容。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  CHECKS, issueBody, issueTitle, needsIssue, parseCheckOutput, parseVersionInput, pickCandidates, redact, runPassed,
} from '../../scripts/dsh-compat-lib.mjs'
import { DSH_BASELINE_VERSION, SUPPORTED_DSH_RANGE } from '../../src/dsh/versions.js'

test('pickCandidates：只留比基线新的版本，去重，从旧到新', () => {
  const tags = { latest: '0.2.3', next: '0.2.3', alpha: '0.2.4-alpha.1', beta: '9.9.9' }
  assert.deepEqual(pickCandidates(tags, '0.2.2-rc.1'), ['0.2.3', '0.2.4-alpha.1'])
})

test('pickCandidates：和基线相同或更旧的版本不检测', () => {
  assert.deepEqual(pickCandidates({ latest: '0.2.2-rc.1', next: '0.2.2-rc.1', alpha: '0.2.2-alpha.3' }, '0.2.2-rc.1'), [])
  // 当前 npm 上的实际情况（2026-11-11）：没有比基线新的版本
  assert.deepEqual(pickCandidates({ latest: '0.2.2-rc.1', alpha: '0.2.2-alpha.3', next: '0.2.2-rc.1' }), [])
})

test('pickCandidates：按 semver 优先级排序（rc 早于正式版，数字按数值比较）', () => {
  assert.deepEqual(pickCandidates({ latest: '0.2.2', next: '0.2.2-rc.10', alpha: '0.2.2-rc.2' }, '0.2.2-rc.1'), ['0.2.2-rc.2', '0.2.2-rc.10', '0.2.2'])
})

test('pickCandidates：版本号不合法的渠道被忽略，dist-tags 不是对象时报错', () => {
  assert.deepEqual(pickCandidates({ latest: 'not-a-version', next: '0.3.0; rm -rf /', alpha: 123 }, '0.2.2-rc.1'), [])
  assert.throws(() => pickCandidates(null), /dist-tags/)
})

test('parseVersionInput：接受普通版本和预发布版本，拒绝其他内容', () => {
  assert.equal(parseVersionInput(' 0.2.3 '), '0.2.3')
  assert.equal(parseVersionInput('0.2.3-rc.1'), '0.2.3-rc.1')
  for (const bad of ['', 'latest', 'v0.2.3', '0.2', '0.2.3 && echo', '0.2.3-rc.1$(x)', '0.2.3+build', '01.2.3']) {
    assert.throws(() => parseVersionInput(bad), /版本号格式不对/, bad)
  }
})

test('parseCheckOutput：取最后一个 check 相符的 JSON 行', () => {
  const stdout = [
    'some log',
    JSON.stringify({ check: 'contract', ok: false, results: [{ name: 'old', ok: false }] }),
    JSON.stringify({ check: 'other', ok: true }),
    JSON.stringify({ check: 'contract', ok: true, results: [{ name: 'a', ok: true }, { name: 'b', ok: true, detail: 3 }] }),
    '',
  ].join('\n')
  assert.deepEqual(parseCheckOutput('contract', stdout, 0), {
    check: 'contract', ok: true, items: [{ name: 'a', ok: true }, { name: 'b', ok: true, detail: '3' }],
  })
})

test('parseCheckOutput：没有输出、JSON 说失败或退出码非 0 都算失败，并留下说明', () => {
  const none = parseCheckOutput('e2e', 'crash\n', 1)
  assert.equal(none.ok, false)
  assert.match(none.items[0]?.detail ?? '', /没有输出结果（退出码 1）/)

  const okJsonBadExit = parseCheckOutput('e2e', JSON.stringify({ check: 'e2e', ok: true, results: [{ name: 'a', ok: true }] }), 1)
  assert.equal(okJsonBadExit.ok, false)
  assert.ok(okJsonBadExit.items.some((i) => !i.ok && /退出码 1/.test(i.detail ?? '')))

  const failed = parseCheckOutput('load', `\r\n${JSON.stringify({ check: 'load', ok: false, results: [{ name: '插件加载', ok: false, detail: 'phase=failed' }] })}\r\n`, 1)
  assert.deepEqual(failed, { check: 'load', ok: false, items: [{ name: '插件加载', ok: false, detail: 'phase=failed' }] })
})

test('CHECKS：三项检测都在，名称和脚本输出的 check 字段一致', () => {
  assert.deepEqual(CHECKS.map((c) => c.check), ['load', 'contract', 'e2e'])
})

test('redact：去掉 token 这类长字符串，保留普通文字和路径', () => {
  const token = 'q3Jx9ZbD0yH7mK2pL5vN8wR4tA6sC1eF3gU0iO9jB7k' // 43 个字符的 base64url，和设备 token 同样长
  assert.equal(redact(`token=${token} 无效`), 'token=[已隐去] 无效')
  assert.equal(redact('/home/runner/work/DSHRemote/plugin/test/e2e.mjs 第 3 行'), '/home/runner/work/DSHRemote/plugin/test/e2e.mjs 第 3 行')
})

test('needsIssue：同一个版本已有同名 issue（不论开关）时不再创建', () => {
  assert.equal(needsIssue('0.2.3', []), true)
  assert.equal(needsIssue('0.2.3', ['DSH 0.2.3-rc.1 兼容性检测失败', 'DSH 0.2.30 兼容性检测失败']), true)
  assert.equal(needsIssue('0.2.3', [issueTitle('0.2.3')]), false)
})

test('runPassed：所有检测都通过才算通过，没有检测不算通过', () => {
  assert.equal(runPassed({ version: '0.2.3', platform: 'linux', checks: [] }), false)
  assert.equal(runPassed({ version: '0.2.3', platform: 'linux', checks: [{ check: 'load', ok: true, items: [] }, { check: 'e2e', ok: false, items: [] }] }), false)
  assert.equal(runPassed({ version: '0.2.3', platform: 'linux', checks: [{ check: 'load', ok: true, items: [] }] }), true)
})

test('issueBody：列出版本、支持范围、失败的项目和处理方法，详情截断并隐去秘密', () => {
  const token = 'q3Jx9ZbD0yH7mK2pL5vN8wR4tA6sC1eF3gU0iO9jB7k'
  const body = issueBody({
    version: '0.2.3',
    publishedAt: '2026-11-20T00:00:00.000Z',
    runUrl: 'https://github.com/x/y/actions/runs/1',
    runs: [{
      version: '0.2.3',
      platform: 'linux',
      checks: [
        { check: 'load', ok: true, items: [{ name: '插件加载', ok: true }] },
        { check: 'contract', ok: false, items: [{ name: 'session/list', ok: false, detail: `a|b ${token} ${'长'.repeat(500)}` }, { name: 'ok item', ok: true }] },
      ],
    }],
  })
  assert.match(body, /DSH `0\.2\.3` 的兼容性检测失败/)
  assert.ok(body.includes(`基线 \`${DSH_BASELINE_VERSION}\``))
  assert.ok(body.includes(SUPPORTED_DSH_RANGE))
  assert.match(body, /内：是。/) // 0.2.3 在 <0.3.0-0 之内
  assert.match(body, /2026-11-20/)
  assert.match(body, /actions\/runs\/1/)
  assert.match(body, /upgrade-guide\/0\.2\.3/)
  assert.match(body, /\| contract \| \*\*失败\*\* \|/)
  assert.match(body, /\| contract \| session\/list \| a\\\|b \[已隐去\] 长+… \|/)
  assert.ok(!body.includes(token))
  assert.ok(!body.includes('ok item'))
  assert.ok(!body.includes('长'.repeat(300)))
})

test('issueBody：超出支持范围的版本提示需要升级基线；失败项目太多时截断', () => {
  const items = Array.from({ length: 40 }, (_, i) => ({ name: `item ${i}`, ok: false }))
  const body = issueBody({ version: '0.3.0', runs: [{ version: '0.3.0', platform: 'win32', checks: [{ check: 'e2e', ok: false, items }] }] })
  assert.match(body, /内：否。.*需要升级基线/)
  assert.match(body, /发布时间：未知/)
  assert.match(body, /## win32/)
  assert.ok(body.includes('item 29'))
  assert.ok(!body.includes('item 30'))
  assert.match(body, /还有 10 项失败/)
})
