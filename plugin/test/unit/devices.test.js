// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { openStorage } from '../../src/storage.js'
import { DeviceRegistry, DeviceLimitError, DEVICES_FILE, MAX_DEVICES } from '../../src/pairing/devices.js'

const info = { name: 'Pixel 8', platform: 'android', appVersion: '0.1.0' }

/** @param {(storage: import('../../src/storage.js').Storage) => Promise<void>} fn */
async function withStorage(fn) {
  const home = await mkdtemp(join(tmpdir(), 'dshremote-devices-'))
  try { await fn(await openStorage({ dshHome: home })) } finally { await rm(home, { recursive: true, force: true }) }
}

test('登记设备：token 为 32 字节 base64url，deviceId 格式固定', async () => {
  await withStorage(async (storage) => {
    const registry = await DeviceRegistry.open(storage)
    const { device, token } = await registry.register(info)
    assert.match(token, /^[A-Za-z0-9_-]{43}$/)
    assert.match(device.deviceId, /^d_[A-Za-z0-9_-]{12}$/)
    assert.equal(device.name, 'Pixel 8')
    assert.equal(/** @type {any} */ (device).tokenHash, undefined, '返回给调用方的设备信息里不能有 token 哈希')
  })
})

test('【安全】磁盘上只有 token 的 SHA-256，没有 token 本身', async () => {
  await withStorage(async (storage) => {
    const registry = await DeviceRegistry.open(storage)
    const { token } = await registry.register(info)
    const raw = await readFile(join(storage.dir, DEVICES_FILE), 'utf8')
    assert.ok(!raw.includes(token), 'devices.json 中出现了 token 原文')
    assert.ok(raw.includes(createHash('sha256').update(token).digest('hex')))
  })
})

test('用正确的 token 认证成功；错误、格式不对、缺失的 token 都失败', async () => {
  await withStorage(async (storage) => {
    const registry = await DeviceRegistry.open(storage)
    const a = await registry.register(info)
    const b = await registry.register({ ...info, name: 'Tablet' })
    assert.equal(registry.authenticate(a.token)?.deviceId, a.device.deviceId)
    assert.equal(registry.authenticate(b.token)?.deviceId, b.device.deviceId)
    const other = 'A'.repeat(43)
    for (const bad of [other, a.token.slice(1), `${a.token}x`, '', undefined, 'not base64!', a.token.toLowerCase() === a.token ? `${a.token.slice(0, 42)}Z` : a.token.toLowerCase()]) {
      assert.equal(registry.authenticate(bad), undefined, `token=${String(bad)}`)
    }
  })
})

test('【反向】撤销后 token 立刻失效；撤销不存在的设备返回 false', async () => {
  await withStorage(async (storage) => {
    const registry = await DeviceRegistry.open(storage)
    const { device, token } = await registry.register(info)
    assert.equal(await registry.revoke(device.deviceId), true)
    assert.equal(registry.authenticate(token), undefined)
    assert.equal(await registry.revoke(device.deviceId), false)
    assert.deepEqual(registry.list(), [])
  })
})

test('重新打开后设备仍然有效；撤销结果也被持久化', async () => {
  await withStorage(async (storage) => {
    const first = await DeviceRegistry.open(storage)
    const keep = await first.register(info)
    const drop = await first.register({ ...info, name: 'Old phone' })
    await first.revoke(drop.device.deviceId)
    const reopened = await DeviceRegistry.open(storage)
    assert.equal(reopened.authenticate(keep.token)?.deviceId, keep.device.deviceId)
    assert.equal(reopened.authenticate(drop.token), undefined)
    assert.deepEqual(reopened.list().map((d) => d.name), ['Pixel 8'])
  })
})

test('list 不包含 token 哈希', async () => {
  await withStorage(async (storage) => {
    const registry = await DeviceRegistry.open(storage)
    await registry.register(info)
    const [device] = registry.list()
    assert.ok(device)
    assert.deepEqual(Object.keys(device).sort(), ['appVersion', 'createdAt', 'deviceId', 'lastSeenAt', 'name', 'platform'])
  })
})

test('设备数量有上限', async () => {
  await withStorage(async (storage) => {
    const registry = await DeviceRegistry.open(storage)
    for (let i = 0; i < MAX_DEVICES; i++) await registry.register({ ...info, name: `p${i}` })
    await assert.rejects(registry.register(info), DeviceLimitError)
    assert.equal(registry.list().length, MAX_DEVICES)
  })
})

test('lastSeenAt 随认证更新，但不会每次都写盘', async () => {
  await withStorage(async (storage) => {
    let t = 1_000_000
    const registry = await DeviceRegistry.open(storage, { now: () => t })
    const { token } = await registry.register(info)
    const before = await readFile(join(storage.dir, DEVICES_FILE), 'utf8')
    t += 60_000
    assert.equal(registry.authenticate(token)?.lastSeenAt, t)
    await registry.flush()
    assert.equal(await readFile(join(storage.dir, DEVICES_FILE), 'utf8'), before, '1 分钟内不应写盘')
    t += 10 * 60_000
    registry.authenticate(token)
    await registry.flush()
    assert.notEqual(await readFile(join(storage.dir, DEVICES_FILE), 'utf8'), before, '超过 10 分钟后应写盘')
  })
})

test('【反向】devices.json 格式不对时报错，而不是当作没有设备', async () => {
  await withStorage(async (storage) => {
    const file = join(storage.dir, DEVICES_FILE)
    for (const content of ['{"version":2,"devices":[]}', '{"version":1}', '{"version":1,"devices":[{"deviceId":"x"}]}', '[]']) {
      await writeFile(file, content)
      await assert.rejects(DeviceRegistry.open(storage), /格式不正确/, content)
    }
    await writeFile(file, '{ broken')
    await assert.rejects(DeviceRegistry.open(storage), /已损坏/)
  })
})

test('并发登记：所有设备都写进文件', async () => {
  await withStorage(async (storage) => {
    const registry = await DeviceRegistry.open(storage)
    const results = await Promise.all(Array.from({ length: 5 }, (_, i) => registry.register({ ...info, name: `p${i}` })))
    const reopened = await DeviceRegistry.open(storage)
    for (const { token, device } of results) assert.equal(reopened.authenticate(token)?.deviceId, device.deviceId)
  })
})
