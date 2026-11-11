// @ts-check
/**
 * 插件安装包（M3 T3）的纯函数：文件名、安装包里的 package.json、内容检查。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { checkPackFiles, packageFileName, stagedManifest } from '../../scripts/pack-lib.mjs'

const manifest = { files: ['index.js', 'src', 'cordis.patch.yml', 'LICENSE'], bundled: ['ws'] }
const good = [
  'package.json', 'README.md', 'index.js', 'cordis.patch.yml', 'LICENSE',
  'src/config.js', 'src/tls/identity.js', 'src/vendor/README.md',
  'node_modules/ws/package.json', 'node_modules/ws/lib/websocket.js',
]

test('packageFileName：正式版和 rc 版', () => {
  assert.equal(packageFileName('0.1.0'), 'dshremote-plugin-0.1.0.tgz')
  assert.equal(packageFileName('0.1.0-rc.1'), 'dshremote-plugin-0.1.0-rc.1.tgz')
})

test('packageFileName：版本号格式不对时报错', () => {
  for (const bad of ['0.1', 'v0.1.0', '0.1.0-beta.1', '0.1.0/../x', '']) assert.throws(() => packageFileName(bad), /版本号格式不对/)
})

test('stagedManifest：去掉开发依赖和脚本，运行时依赖声明为 bundleDependencies', () => {
  const source = { name: 'dshremote', version: '0.1.0', dependencies: { ws: '8.22.1', a: '1.0.0' }, devDependencies: { eslint: '1' }, scripts: { test: 'x' }, files: ['src'] }
  const staged = stagedManifest(source)
  assert.equal(staged.devDependencies, undefined)
  assert.equal(staged.scripts, undefined)
  assert.deepEqual(staged.bundleDependencies, ['a', 'ws'])
  assert.deepEqual(staged.dependencies, source.dependencies)
  assert.equal(staged.version, '0.1.0')
})

test('stagedManifest：保留 DSH 需要的字段（engines、dsh.bundle、exports）', () => {
  const source = JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8'))
  const staged = stagedManifest(source)
  for (const key of ['name', 'version', 'type', 'main', 'exports', 'engines', 'dsh', 'files', 'dependencies', 'license']) {
    assert.deepEqual(staged[key], source[key], key)
  }
})

test('checkPackFiles：正常的安装包通过', () => {
  assert.deepEqual(checkPackFiles(good, manifest), [])
})

test('checkPackFiles：Windows 风格的路径分隔符也按目录判断', () => {
  assert.deepEqual(checkPackFiles(good.map((p) => p.replace(/\//g, '\\')), manifest), [])
})

test('checkPackFiles：不能包含测试和开发脚本', () => {
  const problems = checkPackFiles([...good, 'test/unit/pack.test.js', 'scripts/pack.mjs'], manifest)
  assert.equal(problems.length, 2)
  assert.match(problems[0] ?? '', /test\/unit\/pack\.test\.js/)
  assert.match(problems[1] ?? '', /scripts\/pack\.mjs/)
})

test('checkPackFiles：不能包含开发依赖和其他未声明的包', () => {
  const problems = checkPackFiles([...good, 'node_modules/eslint/package.json', 'node_modules/@types/node/index.d.ts'], manifest)
  assert.equal(problems.length, 2)
  assert.ok(problems.every((p) => p.includes('未声明为运行时依赖')))
})

test('checkPackFiles：不能包含密钥和证书，哪怕在允许的目录里', () => {
  const secrets = ['src/tls/test.pem', 'src/server.key', 'node_modules/ws/x.p12', 'release.jks', 'a.keystore', 'src/.env', '.env.local', 'b.PFX']
  const problems = checkPackFiles([...good, ...secrets], manifest)
  assert.equal(problems.length, secrets.length)
  assert.ok(problems.every((p) => p.includes('密钥或证书')))
})

test('checkPackFiles：不在 files 中的其他文件不允许', () => {
  assert.deepEqual(checkPackFiles([...good, 'tsconfig.json', 'AGENTS.md'], manifest).length, 2)
})

test('checkPackFiles：缺少必需的文件或运行时依赖时报告', () => {
  const problems = checkPackFiles(good.filter((p) => p !== 'index.js' && p !== 'cordis.patch.yml' && !p.startsWith('node_modules/')), manifest)
  assert.deepEqual(problems, ['缺少必需的文件：index.js', '缺少必需的文件：cordis.patch.yml', '缺少运行时依赖：ws'])
})
