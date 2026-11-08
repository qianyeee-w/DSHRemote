// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { openStorage } from '../../src/storage.js'
import { loadOrCreateIdentity, KEY_FILE, CERT_FILE } from '../../src/tls/identity.js'
import { issueSelfSignedCertificate, CERT_VALIDITY_DAYS } from '../../src/tls/certificate.js'

const DAY = 24 * 60 * 60 * 1000

/** @param {(storage: import('../../src/storage.js').Storage) => Promise<void>} fn */
async function withStorage(fn) {
  const home = await mkdtemp(join(tmpdir(), 'dshremote-identity-'))
  try { await fn(await openStorage({ dshHome: home })) } finally { await rm(home, { recursive: true, force: true }) }
}

test('第一次运行：生成私钥和证书', async () => {
  await withStorage(async (storage) => {
    const identity = await loadOrCreateIdentity(storage)
    assert.equal(identity.status, 'created')
    assert.match(identity.fingerprint, /^[A-Za-z0-9_-]{43}$/)
    assert.equal(await storage.readText(KEY_FILE), identity.keyPem)
    assert.equal(await storage.readText(CERT_FILE), identity.certPem)
  })
})

test('再次启动：沿用同一套身份，指纹不变', async () => {
  await withStorage(async (storage) => {
    const first = await loadOrCreateIdentity(storage)
    const second = await loadOrCreateIdentity(storage)
    assert.equal(second.status, 'loaded')
    assert.equal(second.fingerprint, first.fingerprint)
    assert.equal(second.certPem, first.certPem)
  })
})

test('证书过期：用同一把私钥重新签发，指纹不变（手机不需要重新配对）', async () => {
  await withStorage(async (storage) => {
    const first = await loadOrCreateIdentity(storage)
    const expired = issueSelfSignedCertificate(first.keyPem, { now: new Date(Date.now() - (CERT_VALIDITY_DAYS + 1) * DAY) })
    await storage.writeText(CERT_FILE, expired)
    const renewed = await loadOrCreateIdentity(storage)
    assert.equal(renewed.status, 'renewed')
    assert.match(String(renewed.renewReason), /过期/)
    assert.equal(renewed.fingerprint, first.fingerprint)
    assert.notEqual(renewed.certPem, expired)
  })
})

test('证书文件损坏：重新签发，指纹不变', async () => {
  await withStorage(async (storage) => {
    const first = await loadOrCreateIdentity(storage)
    await writeFile(join(storage.dir, CERT_FILE), 'garbage')
    const renewed = await loadOrCreateIdentity(storage)
    assert.equal(renewed.status, 'renewed')
    assert.equal(renewed.fingerprint, first.fingerprint)
  })
})

test('私钥被删除：生成新私钥，指纹改变（所有手机需要重新配对）', async () => {
  await withStorage(async (storage) => {
    const first = await loadOrCreateIdentity(storage)
    await storage.remove(KEY_FILE)
    const recreated = await loadOrCreateIdentity(storage)
    assert.equal(recreated.status, 'created')
    assert.notEqual(recreated.fingerprint, first.fingerprint)
  })
})
