// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, writeFile, readdir, stat, mkdir, chmod } from 'node:fs/promises'
import { tmpdir, homedir } from 'node:os'
import { join } from 'node:path'
import { openStorage, resolveDshHome, StorageError } from '../../src/storage.js'

const IS_WINDOWS = process.platform === 'win32'

/** @param {(home: string) => Promise<void>} fn */
async function withHome(fn) {
  const home = await mkdtemp(join(tmpdir(), 'dshremote-storage-'))
  try { await fn(home) } finally { await rm(home, { recursive: true, force: true }) }
}

test('resolveDshHome：优先使用 DSH_HOME，否则为 ~/.dsh', () => {
  assert.equal(resolveDshHome({ DSH_HOME: '/data/dsh' }), '/data/dsh')
  assert.equal(resolveDshHome({}), join(homedir(), '.dsh'))
  assert.equal(resolveDshHome({ DSH_HOME: '  ' }), join(homedir(), '.dsh'))
})

test('状态目录位于 <DSH_HOME>/dshremote', async () => {
  await withHome(async (home) => {
    const storage = await openStorage({ dshHome: home })
    assert.equal(storage.dir, join(home, 'dshremote'))
    assert.ok((await stat(storage.dir)).isDirectory())
  })
})

test('JSON 读写往返；文件不存在时返回 undefined', async () => {
  await withHome(async (home) => {
    const storage = await openStorage({ dshHome: home })
    assert.equal(await storage.readJson('devices.json'), undefined)
    await storage.writeJson('devices.json', { devices: [{ id: 'd_1' }] })
    assert.deepEqual(await storage.readJson('devices.json'), { devices: [{ id: 'd_1' }] })
    await storage.writeJson('devices.json', { devices: [] })
    assert.deepEqual(await storage.readJson('devices.json'), { devices: [] })
  })
})

test('文本读写往返，删除后读取为 undefined', async () => {
  await withHome(async (home) => {
    const storage = await openStorage({ dshHome: home })
    await storage.writeText('key.pem', '-----BEGIN PRIVATE KEY-----\nabc\n')
    assert.equal(await storage.readText('key.pem'), '-----BEGIN PRIVATE KEY-----\nabc\n')
    await storage.remove('key.pem')
    assert.equal(await storage.readText('key.pem'), undefined)
    await storage.remove('key.pem') // 不存在时不报错
  })
})

test('写入后不留下临时文件', async () => {
  await withHome(async (home) => {
    const storage = await openStorage({ dshHome: home })
    for (let i = 0; i < 5; i++) await storage.writeJson('state.json', { i })
    assert.deepEqual(await readdir(storage.dir), ['state.json'])
  })
})

test('模拟写到一半崩溃：目标文件保持旧内容，遗留的临时文件在下次打开时被清理', async () => {
  await withHome(async (home) => {
    const storage = await openStorage({ dshHome: home })
    await storage.writeJson('devices.json', { version: 'old' })
    // 崩溃现场：临时文件写了一半，还没来得及 rename
    const stale = join(storage.dir, 'devices.json.1234.abcdef.tmp')
    await writeFile(stale, '{"version": "ne')
    assert.deepEqual(await storage.readJson('devices.json'), { version: 'old' })

    const reopened = await openStorage({ dshHome: home })
    assert.deepEqual(await readdir(reopened.dir), ['devices.json'])
    assert.deepEqual(await reopened.readJson('devices.json'), { version: 'old' })
  })
})

test('文件损坏时报错，而不是当作空数据', async () => {
  await withHome(async (home) => {
    const storage = await openStorage({ dshHome: home })
    await writeFile(join(storage.dir, 'devices.json'), '{ not json')
    await assert.rejects(storage.readJson('devices.json'), StorageError)
    await assert.rejects(storage.readJson('devices.json'), /已损坏/)
  })
})

test('拒绝可能造成路径穿越的文件名', async () => {
  await withHome(async (home) => {
    const storage = await openStorage({ dshHome: home })
    for (const name of ['../evil.json', 'a/b.json', 'a\\b.json', '.hidden', 'Upper.json', 'x.tmp', '', '..']) {
      await assert.rejects(storage.writeJson(name, {}), StorageError, `name=${JSON.stringify(name)}`)
      await assert.rejects(storage.readJson(name), StorageError, `name=${JSON.stringify(name)}`)
    }
  })
})

test('不能写入无法序列化的值', async () => {
  await withHome(async (home) => {
    const storage = await openStorage({ dshHome: home })
    await assert.rejects(storage.writeJson('x.json', undefined), StorageError)
  })
})

test('POSIX：目录权限 0700，文件权限 0600（即使目录原先权限更宽）', { skip: IS_WINDOWS }, async () => {
  await withHome(async (home) => {
    await mkdir(join(home, 'dshremote'), { mode: 0o755 })
    await chmod(join(home, 'dshremote'), 0o755)
    const storage = await openStorage({ dshHome: home })
    assert.equal((await stat(storage.dir)).mode & 0o777, 0o700)
    await storage.writeJson('devices.json', {})
    await storage.writeText('key.pem', 'secret')
    assert.equal((await stat(join(storage.dir, 'devices.json'))).mode & 0o777, 0o600)
    assert.equal((await stat(join(storage.dir, 'key.pem'))).mode & 0o777, 0o600)
  })
})

test('并发写同一个文件：最终内容是其中一次完整的写入', async () => {
  await withHome(async (home) => {
    const storage = await openStorage({ dshHome: home })
    await Promise.all(Array.from({ length: 10 }, (_, i) => storage.writeJson('state.json', { i, padding: 'x'.repeat(1000) })))
    const value = /** @type {{ i: number, padding: string }} */ (await storage.readJson('state.json'))
    assert.ok(Number.isInteger(value.i) && value.i >= 0 && value.i < 10)
    assert.equal(value.padding.length, 1000)
    assert.deepEqual(await readdir(storage.dir), ['state.json'])
  })
})
