// @ts-check
/**
 * 用真实的浏览器（系统安装的 Chrome / Edge）操作本机配对页：生成配对码、取消配对码、撤销设备。
 *
 * 单元测试用 Node 客户端手动设置 Origin，测不出浏览器的行为。T10 在 Edge 上发现：页面的
 * Referrer-Policy 为 no-referrer 时，浏览器提交表单发送 Origin: null，配对页因此无法生成配对码。
 * 这个检查保证页面上的每个按钮在真实浏览器里都能用。
 *
 * 用法：npm run browser-check
 *   BROWSER_CHANNEL=chrome（默认）或 msedge；也可以用 BROWSER_PATH 指定浏览器可执行文件。
 * 输出：stdout 最后一行是 JSON 结果，退出码 0 表示全部通过。
 */
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { chromium } from 'playwright-core'
import { startAdminServer } from '../../src/admin/server.js'
import { PairingCodes } from '../../src/pairing/codes.js'
import { DeviceRegistry } from '../../src/pairing/devices.js'
import { openStorage } from '../../src/storage.js'

/** @type {{ name: string, ok: boolean, detail?: string }[]} */
const results = []
/** @param {string} name @param {() => Promise<string | void>} fn */
async function step(name, fn) {
  try {
    const detail = await fn()
    results.push(detail ? { name, ok: true, detail } : { name, ok: true })
    console.error(`[browser] PASS  ${name}${detail ? `  ${detail}` : ''}`)
  } catch (error) {
    const detail = error instanceof Error ? error.message.split('\n')[0] ?? '' : String(error)
    results.push({ name, ok: false, detail })
    console.error(`[browser] FAIL  ${name}  ${detail}`)
  }
}

const home = mkdtempSync(join(tmpdir(), 'dshremote-browser-'))
const devices = await DeviceRegistry.open(await openStorage({ dshHome: home }))
const codes = new PairingCodes()
const admin = await startAdminServer({
  port: 0, codes, devices,
  info: () => ({ fingerprint: 'A'.repeat(43), lanPort: 3190, lanBind: '0.0.0.0', pluginVersion: 'browser-check', dsh: { version: '0.2.2-rc.1', compatible: true, state: 'ready' } }),
})
const channel = process.env.BROWSER_CHANNEL || 'chrome'
const browser = await chromium.launch(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : { channel })
try {
  const page = await browser.newPage()
  /** @type {(string | undefined)[]} */
  const postOrigins = []
  page.on('request', (r) => { if (r.method() === 'POST') postOrigins.push(r.headers().origin) })
  await page.goto(admin.url)

  await step('生成配对码：表单提交带正确的 Origin，页面显示配对码', async () => {
    await page.getByRole('button', { name: '生成配对码' }).click()
    await page.waitForLoadState()
    const origin = postOrigins.at(-1)
    if (origin !== admin.url.replace(/\/$/, '')) throw new Error(`浏览器发出的 Origin 为 ${origin}`)
    const code = await page.locator('.mono.big').first().textContent({ timeout: 5000 }).catch(() => null)
    if (!code || !/^[A-Z0-9]{4} [A-Z0-9]{4}$/.test(code.trim())) throw new Error(`页面上没有配对码：${(await page.content()).match(/class="bad">([^<]+)/)?.[1] ?? ''}`)
    return `Origin=${origin}`
  })

  await step('取消配对码', async () => {
    await page.getByRole('button', { name: '取消配对码' }).click()
    await page.waitForLoadState()
    if (await page.locator('.mono.big').count() > 0) throw new Error('取消后页面上仍有配对码')
  })

  await step('撤销设备', async () => {
    const { device } = await devices.register({ name: '浏览器测试手机', platform: 'android', appVersion: '0' })
    await page.reload()
    await page.getByRole('button', { name: '撤销' }).click()
    await page.waitForLoadState()
    if (devices.list().some((d) => d.deviceId === device.deviceId)) throw new Error('设备没有被撤销')
    if ((await page.content()).includes('浏览器测试手机')) throw new Error('页面上仍显示这台设备')
  })
} finally {
  await browser.close()
  await admin.close()
  await devices.flush()
  rmSync(home, { recursive: true, force: true })
}
const ok = results.length > 0 && results.every((r) => r.ok)
console.log(JSON.stringify({ check: 'browser', channel: process.env.BROWSER_PATH ? 'custom' : channel, platform: process.platform, ok, results }))
process.exitCode = ok ? 0 : 1
