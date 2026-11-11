// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, readFile, rm, symlink, realpath, readdir, chmod } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { canonicalDirectory, createDirectory, listDirectories, validateDirectoryName, validateDirectoryPath, DIRECTORY_LIMIT } from '../../src/directories.js'
import { Facade, FacadeError } from '../../src/dsh/facade.js'

/** @param {import('node:test').TestContext} t */
async function temporary(t) {
  const root = await mkdtemp(join(tmpdir(), 'dshremote-projects-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  return realpath(root)
}

test('目录浏览：只显示一级子目录，路径含中文／空格／# 仍保持完整', async (t) => {
  const root = await temporary(t)
  await mkdir(join(root, '中文 # 项目'))
  await mkdir(join(root, '.hidden'))
  await writeFile(join(root, 'secret.txt'), '不应读取或返回')
  const result = await listDirectories(root)
  assert.equal(result.path, root)
  assert.deepEqual(result.entries.map((e) => e.name).sort(), ['.hidden', '中文 # 项目'])
  assert.equal(result.entries.find((e) => e.name === '.hidden')?.hidden, true)
  assert.equal(result.truncated, false)
  assert.equal(JSON.stringify(result).includes('secret.txt'), false)
})

test('新建目录：创建单层，重复调用拒绝且保留已有内容', async (t) => {
  const root = await temporary(t)
  const created = await createDirectory(root, '项目')
  assert.equal(created.path, join(root, '项目'))
  await writeFile(join(created.path, 'keep'), '保留')
  await assert.rejects(createDirectory(root, '项目'), { code: 'DIRECTORY_EXISTS' })
  await writeFile(join(root, 'file'), '保留')
  await assert.rejects(createDirectory(root, 'file'), { code: 'DIRECTORY_EXISTS' })
  assert.deepEqual(await readdir(created.path), ['keep'])
  await assert.rejects(createDirectory(join(root, 'absent'), 'child'), { code: 'DIRECTORY_UNAVAILABLE' })
  assert.equal((await readdir(root)).includes('absent'), false)
})

test('【反向】名称不能越过父目录、使用 Windows 设备名或特殊路径语义', () => {
  for (const name of ['', ' ', '.', '..', '../escape', 'a/b', 'a\\b', 'C:escape', 'x\0', 'x\n', 'aux', 'NUL.txt', 'COM1', 'LPT².txt', 'CONIN$', 'a.', 'a ', 'x?y', 'x'.repeat(129)]) {
    assert.throws(() => validateDirectoryName(name), { code: 'DIRECTORY_INVALID' })
  }
  assert.equal(validateDirectoryName('中文 # 项目'), '中文 # 项目')
})

test('【反向】绝对路径规则：拒绝相对路径、控制字符、Windows UNC／设备／盘符相对路径', () => {
  for (const path of ['', '.', 'a/b', 'a\0']) assert.throws(() => validateDirectoryPath(path, 'linux'), { code: 'DIRECTORY_INVALID' })
  for (const path of ['C:foo', '\\foo', '/foo', '\\\\server\\share', '\\\\?\\C:\\foo', 'C:\\aux\\a', 'C:\\a:stream', 'C:\\foo.']) {
    assert.throws(() => validateDirectoryPath(path, 'win32'), { code: 'DIRECTORY_INVALID' })
  }
  assert.equal(validateDirectoryPath('E:/项目', 'win32'), 'E:\\项目')
  assert.equal(validateDirectoryPath('/tmp/项目', 'linux'), '/tmp/项目')
})

test('目录数量有界并标明截断', async (t) => {
  const root = await temporary(t)
  await Promise.all(Array.from({ length: DIRECTORY_LIMIT + 1 }, (_, i) => mkdir(join(root, `dir-${i}`))))
  const listing = await listDirectories(root)
  assert.equal(listing.entries.length, DIRECTORY_LIMIT)
  assert.equal(listing.truncated, true)
})

test('目录链接解析到实际路径，坏链接跳过', async (t) => {
  const root = await temporary(t)
  await mkdir(join(root, 'target'))
  await symlink(join(root, 'target'), join(root, 'alias'), process.platform === 'win32' ? 'junction' : 'dir')
  await symlink(join(root, 'absent'), join(root, 'broken'), process.platform === 'win32' ? 'junction' : 'dir')
  assert.equal(await canonicalDirectory(join(root, 'alias')), join(root, 'target'))
  const listing = await listDirectories(root)
  assert.equal(listing.entries.find((e) => e.name === 'alias')?.path, join(root, 'target'))
  assert.equal(listing.entries.some((e) => e.name === 'broken'), false)
})

test('【反向】不可读目录与文件：拒绝且异常中没有原始路径', async (t) => {
  const root = await temporary(t)
  const path = join(root, 'private-secret-file')
  await writeFile(path, '保留')
  for (const target of [path, join(root, 'absent')]) {
    await assert.rejects(listDirectories(target), (error) => {
      assert.equal(/** @type {any} */ (error).code, 'DIRECTORY_UNAVAILABLE')
      assert.equal(String(error).includes(root), false)
      return true
    })
  }
  if (process.platform !== 'win32' && process.getuid?.() !== 0) {
    const denied = join(root, 'denied')
    await mkdir(denied)
    await chmod(denied, 0)
    try { await assert.rejects(listDirectories(denied), { code: 'DIRECTORY_UNAVAILABLE' }) } finally { await chmod(denied, 0o700) }
  }
})

test('登记只通过 workspace/create 公开接口、带设备身份且只返回允许字段；失败保留目录', async (t) => {
  const root = await temporary(t)
  const recorded = JSON.parse(await readFile(new URL('../fixtures/dsh-0.2.2-rc.1/workspace-create.json', import.meta.url), 'utf8'))
  /** @type {unknown[]} */
  const calls = []
  let fail = false
  const dsh = /** @type {import('../../src/dsh/connection.js').DshConnection} */ (/** @type {unknown} */ ({
    async call(/** @type {string} */ endpoint, /** @type {unknown} */ args, /** @type {unknown} */ context) {
      calls.push({ endpoint, args, context })
      return fail ? { ok: false, error: { code: 'workspace/invalid-path', message: root } }
        : { ok: true, value: { workspace: { ...recorded, path: root, private: '禁止透传' }, created: calls.length === 1 } }
    },
  }))
  const facade = new Facade(dsh)
  assert.deepEqual(await facade.registerWorkspace('d1', { path: root }), { workspace: { workspaceId: recorded.workspaceId, path: root, title: recorded.title }, created: true })
  assert.deepEqual(calls[0], { endpoint: 'workspace/create', args: { request: { path: root } }, context: { actor: 'd1' } })
  assert.equal((await facade.registerWorkspace('d1', { path: root })).created, false)
  fail = true
  await assert.rejects(facade.registerWorkspace('d1', { path: root }), (error) => error instanceof FacadeError && !error.message.includes(root))
  assert.equal(await canonicalDirectory(root), root)
  await assert.rejects(facade.registerWorkspace('d1', { path: '../escape' }), { code: 'DIRECTORY_INVALID' })
  assert.equal(calls.length, 3)
})
