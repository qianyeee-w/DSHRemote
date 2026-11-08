// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, mkdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  parseSemver, compareSemver, satisfiesRange, detectDshVersion, dshVersionStatus, SUPPORTED_DSH_RANGE,
} from '../../src/dsh/versions.js'

/** @param {string} a @param {string} b */
const cmp = (a, b) => Math.sign(compareSemver(/** @type {any} */ (parseSemver(a)), /** @type {any} */ (parseSemver(b))))

test('parseSemver', () => {
  assert.deepEqual(parseSemver('0.2.2-rc.1'), { major: 0, minor: 2, patch: 2, pre: ['rc', 1] })
  assert.deepEqual(parseSemver('1.0.0+build.5'), { major: 1, minor: 0, patch: 0, pre: [] })
  for (const bad of ['', '1.2', '01.2.3', '1.2.3-', 'x.y.z', '1.2.3.4']) assert.equal(parseSemver(bad), undefined, bad)
})

test('semver 优先级（规范中的例子）', () => {
  const ordered = ['1.0.0-alpha', '1.0.0-alpha.1', '1.0.0-alpha.beta', '1.0.0-beta', '1.0.0-beta.2', '1.0.0-beta.11', '1.0.0-rc.1', '1.0.0']
  for (let i = 0; i < ordered.length - 1; i++) {
    assert.equal(cmp(/** @type {string} */ (ordered[i]), /** @type {string} */ (ordered[i + 1])), -1, `${ordered[i]} < ${ordered[i + 1]}`)
  }
  assert.equal(cmp('0.2.2-rc.1', '0.2.2-rc.1'), 0)
  assert.equal(cmp('0.2.10', '0.2.9'), 1)
})

test('支持范围：基线、之后的 rc 和正式版在内；更早的版本和 0.3 不在内', () => {
  assert.equal(SUPPORTED_DSH_RANGE, '>=0.2.2-rc.1 <0.3.0-0')
  for (const v of ['0.2.2-rc.1', '0.2.2-rc.2', '0.2.2', '0.2.3-alpha.1', '0.2.3-rc.1', '0.2.9']) assert.equal(satisfiesRange(v), true, v)
  for (const v of ['0.2.2-alpha.3', '0.2.1-rc.5', '0.2.1', '0.3.0-0', '0.3.0-alpha.1', '0.3.0', '1.0.0']) assert.equal(satisfiesRange(v), false, v)
  assert.equal(satisfiesRange('not-a-version'), undefined)
  assert.throws(() => satisfiesRange('1.0.0', '^1.0.0'), /不支持的版本范围/)
})

/**
 * 在临时目录中按给定结构创建文件。
 * @param {Record<string, unknown>} files 相对路径 → JSON 内容（字符串则原样写入）
 * @param {(root: string) => void | Promise<void>} fn
 */
async function withTree(files, fn) {
  const root = await mkdtemp(join(tmpdir(), 'dshremote-versions-'))
  try {
    for (const [rel, content] of Object.entries(files)) {
      const path = join(root, ...rel.split('/'))
      await mkdir(join(path, '..'), { recursive: true })
      await writeFile(path, typeof content === 'string' ? content : JSON.stringify(content))
    }
    await fn(root)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
}

test('检测版本：dsh web（入口为 @deepseek-ai/dsh/lib/bin.js）', async () => {
  await withTree({
    'node_modules/@deepseek-ai/dsh/package.json': { name: '@deepseek-ai/dsh', version: '0.2.2-rc.1' },
    'node_modules/@deepseek-ai/dsh/lib/bin.js': '',
  }, (root) => {
    const entry = join(root, 'node_modules', '@deepseek-ai', 'dsh', 'lib', 'bin.js')
    assert.deepEqual(detectDshVersion({ entry }), { version: '0.2.2-rc.1', source: '@deepseek-ai/dsh' })
    assert.deepEqual(dshVersionStatus({ entry }), { version: '0.2.2-rc.1', compatible: true })
  })
})

test('检测版本：官方 Desktop（入口为 runtime/node_modules/@deepseek-ai/dsh-desktop-host/lib/index.js）', async () => {
  await withTree({
    'runtime/node_modules/@deepseek-ai/dsh-desktop-host/package.json': { name: '@deepseek-ai/dsh-desktop-host', version: '0.2.2-rc.1' },
    'runtime/node_modules/@deepseek-ai/dsh-desktop-host/lib/index.js': '',
  }, (root) => {
    const entry = join(root, 'runtime', 'node_modules', '@deepseek-ai', 'dsh-desktop-host', 'lib', 'index.js')
    assert.deepEqual(detectDshVersion({ entry }), { version: '0.2.2-rc.1', source: '@deepseek-ai/dsh-desktop-host' })
  })
})

test('检测版本：入口旁边的 node_modules 里有 @deepseek-ai/dsh', async () => {
  await withTree({
    'app/node_modules/@deepseek-ai/dsh/package.json': { name: '@deepseek-ai/dsh', version: '0.2.5' },
    'app/main/start.js': '',
    'app/package.json': { name: 'something-else', version: '9.9.9' },
  }, (root) => {
    assert.deepEqual(detectDshVersion({ entry: join(root, 'app', 'main', 'start.js') }), { version: '0.2.5', source: '@deepseek-ai/dsh' })
  })
})

test('检测版本：不在范围内时 compatible 为 false', async () => {
  await withTree({
    'node_modules/@deepseek-ai/dsh/package.json': { name: '@deepseek-ai/dsh', version: '0.3.1' },
    'node_modules/@deepseek-ai/dsh/lib/bin.js': '',
  }, (root) => {
    assert.deepEqual(dshVersionStatus({ entry: join(root, 'node_modules', '@deepseek-ai', 'dsh', 'lib', 'bin.js') }), { version: '0.3.1', compatible: false })
  })
})

test('检测不到时返回未知，不报错（包括损坏的 package.json、名字不对的包）', async () => {
  await withTree({
    'x/package.json': '{ broken',
    'x/y/package.json': { name: '@deepseek-ai/dsh-fake', version: '0.2.2' },
    'x/y/z/entry.js': '',
  }, (root) => {
    assert.deepEqual(dshVersionStatus({ entry: join(root, 'x', 'y', 'z', 'entry.js') }), { version: null, compatible: null })
  })
  assert.equal(detectDshVersion({ entry: undefined }), undefined)
})
