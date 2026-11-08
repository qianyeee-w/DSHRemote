// @ts-check
/**
 * 真机验证脚本（M1 T10）：检查装在官方 Desktop（或 dsh web）里的插件，模拟手机走一遍对外接口。
 * 步骤和说明见 docs/plans/m1-windows-verification.md。
 *
 * 本机模式（在装了插件的电脑上运行）：
 *   node scripts/verify.mjs                 通过本机配对页生成配对码、配对一台临时设备，检查完后自行取消配对
 *   node scripts/verify.mjs --keep          检查完后保留这台临时设备（token 存在用户目录下），用于重启 DSH 后的验证
 *   node scripts/verify.mjs --reuse         用上一次 --keep 保存的 token 连接，不重新配对（验证重启 DSH 后免配对）
 *   node scripts/verify.mjs --prompt        额外新建一个会话，发一条消息，检查流式输出和「任务完成」（会消耗模型额度）
 *
 * 局域网模式（在同一局域网的另一台机器上运行，验证防火墙和局域网访问）：
 *   node scripts/verify.mjs --host <电脑的局域网 IP> --code <配对码> --fingerprint <证书指纹>
 *   配对码和完整的证书指纹在电脑的配对页上（「这台电脑」一栏）。
 *
 * 可选：--lan-port <端口>（默认 3190）、--admin-port <端口>（默认 3191）
 *
 * 输出：每一项的检查结果，最后一行是 JSON（不包含 token、配对码），把它发给开发者即可。
 */
import http from 'node:http'
import { readFileSync, writeFileSync, rmSync, existsSync } from 'node:fs'
import { homedir, platform, release } from 'node:os'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { parseArgs } from 'node:util'
import { pinnedRequest, pinnedWebSocket, collectMessages, PinMismatchError } from '../test/helpers/pinned-client.js'

const { values: args } = parseArgs({
  options: {
    host: { type: 'string' },
    code: { type: 'string' },
    fingerprint: { type: 'string' },
    'lan-port': { type: 'string', default: '3190' },
    'admin-port': { type: 'string', default: '3191' },
    keep: { type: 'boolean', default: false },
    reuse: { type: 'boolean', default: false },
    prompt: { type: 'boolean', default: false },
  },
})
const LAN_PORT = Number(args['lan-port'])
const ADMIN_PORT = Number(args['admin-port'])
const REMOTE = args.host !== undefined
const HOST = args.host ?? '127.0.0.1'
/** --keep 保存的临时设备。只在本机用户目录下，不在仓库里（安全红线 5） */
const STATE_FILE = join(homedir(), '.dshremote-verify.json')

/** @type {{ name: string, ok: boolean, detail?: string }[]} */
const results = []
/** @type {Record<string, unknown>} */
const facts = {}

/** @param {string} name @param {() => Promise<string | void>} fn */
async function check(name, fn) {
  try {
    const detail = await fn()
    results.push(detail ? { name, ok: true, detail } : { name, ok: true })
    console.log(`  ✓ ${name}${detail ? `：${detail}` : ''}`)
    return true
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error)
    results.push({ name, ok: false, detail })
    console.log(`  ✗ ${name}：${detail}`)
    return false
  }
}

/** @param {string} path @param {{ method?: string, body?: string, headers?: Record<string, string> }} [options] */
function admin(path, options = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: ADMIN_PORT, path, method: options.method ?? 'GET', headers: { host: `127.0.0.1:${ADMIN_PORT}`, ...options.headers }, timeout: 10_000 }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (c) => { body += c })
      res.on('end', () => resolve(/** @type {{ status: number, body: string }} */ ({ status: res.statusCode ?? 0, body })))
    })
    req.on('timeout', () => req.destroy(new Error('超时')))
    req.on('error', reject)
    if (options.body) req.write(options.body)
    req.end()
  })
}

/** 像在配对页上点按钮一样提交表单。 @param {string} action */
async function adminPost(action) {
  const page = /** @type {{ body: string }} */ (await admin('/'))
  const csrf = page.body.match(/name="csrf" value="([^"]+)"/)?.[1]
  if (!csrf) throw new Error('配对页上没有找到表单')
  const body = new URLSearchParams({ csrf }).toString()
  const res = /** @type {{ status: number }} */ (await admin(action, {
    method: 'POST', body,
    headers: { origin: `http://127.0.0.1:${ADMIN_PORT}`, 'content-type': 'application/x-www-form-urlencoded', 'content-length': String(Buffer.byteLength(body)) },
  }))
  if (res.status !== 303) throw new Error(`配对页返回 ${res.status}`)
}

// 配对页上的指纹每 4 个字符一组显示，复制时可能带空格
const device = { fingerprint: (args.fingerprint ?? '').replace(/\s/g, ''), token: '' }

/** @param {string} method @param {string} path @param {unknown} [body] @param {{ token?: string }} [options] */
async function api(method, path, body, options = {}) {
  const token = options.token ?? device.token
  const res = await pinnedRequest({
    host: HOST, port: LAN_PORT, fingerprint: device.fingerprint, method, path,
    headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  return { status: res.status, json: () => res.json() }
}

const wait = (/** @type {number} */ ms) => new Promise((r) => setTimeout(r, ms))

async function main() {
  console.log(`DSHRemote 插件验证（${REMOTE ? `局域网模式，目标 ${HOST}` : '本机模式'}）\n`)
  facts.mode = REMOTE ? 'lan' : 'local'
  facts.os = `${platform()} ${release()}`
  facts.node = process.version

  // ------------------------------------------------ 本机：配对页
  if (!REMOTE) {
    console.log('本机配对页')
    const pageOk = await check('配对页可以打开', async () => {
      const page = /** @type {{ status: number, body: string }} */ (await admin('/').catch((error) => {
        throw new Error(`连不上 http://127.0.0.1:${ADMIN_PORT}（${error.message}）。插件可能没有加载，或者端口被改过`)
      }))
      if (page.status !== 200) throw new Error(`返回 ${page.status}`)
      const fp = page.body.match(/<strong>([A-Za-z0-9_-]{8})<\/strong>([A-Za-z0-9_-]+)<br>/)
      if (!fp) throw new Error('页面上没有证书指纹')
      device.fingerprint = `${fp[1]}${fp[2]}`
      facts.pluginVersion = page.body.match(/<th>插件版本<\/th><td>([^<]+)<\/td>/)?.[1]
      facts.addressesShown = (page.body.match(/没有找到可用的局域网地址/) ? 0 : 1)
      return `插件版本 ${facts.pluginVersion}`
    })
    if (!pageOk) return
    await check('配对页显示插件已连上 DSH', async () => {
      let state = ''
      for (let i = 0; i < 20; i++) {
        const page = /** @type {{ body: string }} */ (await admin('/'))
        state = page.body.match(/data-state="([a-z]+)"/)?.[1] ?? ''
        facts.dshShownOnPage = page.body.match(/<th>DeepSeek Harness<\/th><td>([^·<]+)/)?.[1]?.trim()
        if (state === 'ready') return `DeepSeek Harness：${facts.dshShownOnPage}`
        await wait(500)
      }
      facts.dshState = state
      throw new Error(`连接状态为 ${state || '未知'}（应为 ready）`)
    })
    await check('配对页拒绝非本机的 Host（防 DNS rebinding）', async () => {
      const res = /** @type {{ status: number }} */ (await admin('/', { headers: { host: 'evil.example' } }))
      if (res.status !== 403) throw new Error(`返回 ${res.status}`)
    })
  }

  // ------------------------------------------------ 局域网 HTTPS
  console.log('\n局域网接口（HTTPS，按证书指纹校验）')
  if (!device.fingerprint) { console.log('  ✗ 缺少证书指纹（局域网模式请加 --fingerprint）'); return }
  const reachable = await check(`可以访问 ${HOST}:${LAN_PORT}，证书指纹一致`, async () => {
    const res = await api('GET', '/v1/info').catch((error) => {
      if (error instanceof PinMismatchError) throw new Error('证书指纹不一致。请确认 --fingerprint 是配对页上显示的完整指纹')
      throw new Error(`${error.message}${REMOTE ? '。多半是 Windows 防火墙拦截了，见验证文档的「防火墙」一节' : ''}`)
    })
    const info = res.json()
    facts.info = info
    if (res.status !== 200 || info.protocol !== 1) throw new Error(`/v1/info 返回 ${res.status}`)
    return `插件 ${info.pluginVersion}`
  })
  if (!reachable) return
  await check('插件读到了 DSH 的版本，并且在支持范围内', async () => {
    const dsh = /** @type {any} */ (facts.info).dsh
    if (dsh.version === null) throw new Error('version 为 null：插件在这个环境里读不到 DSH 的版本（请把结果发给开发者）')
    if (dsh.compatible !== true) throw new Error(`DSH ${dsh.version} 不在支持范围内`)
    return `DSH ${dsh.version}`
  })
  await check('【反向】证书指纹不对时，客户端拒绝连接', async () => {
    const wrong = `${device.fingerprint.slice(0, -2)}${device.fingerprint.endsWith('AA') ? 'BB' : 'AA'}`
    try {
      await pinnedRequest({ host: HOST, port: LAN_PORT, fingerprint: wrong, path: '/v1/info' })
    } catch (error) {
      if (error instanceof PinMismatchError) return
      throw error
    }
    throw new Error('指纹不对却连上了')
  })
  await check('【反向】没有 token 的请求被拒绝（401）', async () => {
    const res = await api('GET', '/v1/sessions', undefined, { token: '' })
    if (res.status !== 401) throw new Error(`返回 ${res.status}`)
  })

  // ------------------------------------------------ 配对
  console.log('\n配对')
  let paired
  if (args.reuse) {
    paired = await check('用上次保存的 token 连接（不重新配对）', async () => {
      if (!existsSync(STATE_FILE)) throw new Error('没有找到上次保存的设备，请先用 --keep 运行一次')
      const saved = JSON.parse(readFileSync(STATE_FILE, 'utf8'))
      device.token = saved.token
      const me = await api('GET', '/v1/me')
      if (me.status !== 200) throw new Error(`/v1/me 返回 ${me.status}，token 已失效`)
      return `设备 ${me.json().name}，配对于 ${new Date(me.json().createdAt).toLocaleString('zh-CN', { hour12: false })}`
    })
  } else {
    paired = await check('配对一台临时设备', async () => {
      let code = args.code?.replace(/\s/g, '').toUpperCase()
      if (!code) {
        if (REMOTE) throw new Error('局域网模式请用 --code 提供配对页上的配对码')
        await adminPost('/pair-code')
        const page = /** @type {{ body: string }} */ (await admin('/'))
        code = page.body.match(/class="mono big">([A-Z0-9 ]+)</)?.[1]?.replace(/ /g, '')
        if (!code) throw new Error('配对页上没有显示配对码（是不是没有可用的局域网地址？）')
      }
      const res = await api('POST', '/v1/pair', { code, deviceName: `验证脚本（${REMOTE ? '局域网' : '本机'}）`, platform: 'android', appVersion: '0.0.0-verify' }, { token: '' })
      if (res.status !== 200) throw new Error(`配对失败：${res.status} ${res.json().error?.message ?? ''}`)
      device.token = res.json().token
      return `设备 id ${res.json().deviceId}`
    })
  }
  if (!paired) return

  // ------------------------------------------------ 读取
  console.log('\n读取')
  await check('会话列表', async () => {
    const res = await api('GET', '/v1/sessions')
    if (res.status !== 200) throw new Error(`返回 ${res.status} ${JSON.stringify(res.json().error)}`)
    const sessions = res.json().sessions
    facts.sessionCount = sessions.length
    return `${sessions.length} 个会话`
  })
  await check('工作区、模型、权限模式', async () => {
    const [w, m, p] = await Promise.all([api('GET', '/v1/workspaces'), api('GET', '/v1/models'), api('GET', '/v1/permission-presets')])
    for (const r of [w, m, p]) if (r.status !== 200) throw new Error(`返回 ${r.status} ${JSON.stringify(r.json().error)}`)
    facts.models = m.json().models.length
    facts.presets = p.json().presets.map((/** @type {any} */ x) => x.id)
    return `${w.json().workspaces.length} 个工作区，${facts.models} 个模型，权限模式 ${/** @type {string[]} */ (facts.presets).join(' / ')}`
  })
  await check('读取最近一个会话的历史消息', async () => {
    const list = (await api('GET', '/v1/sessions')).json().sessions
    if (list.length === 0) return '没有会话，跳过'
    const res = await api('GET', `/v1/sessions/${list[0].sessionId}/messages?limit=20`)
    if (res.status !== 200) throw new Error(`返回 ${res.status} ${JSON.stringify(res.json().error)}`)
    return `${res.json().messages.length} 条消息`
  })

  // ------------------------------------------------ 实时流
  console.log('\n实时流')
  /** @type {Awaited<ReturnType<typeof pinnedWebSocket>> | undefined} */
  let ws
  /** @type {ReturnType<typeof collectMessages> | undefined} */
  let inbox
  await check('连接 /v1/stream，收到 hello', async () => {
    ws = await pinnedWebSocket({ host: HOST, port: LAN_PORT, fingerprint: device.fingerprint, headers: { authorization: `Bearer ${device.token}` } })
    inbox = collectMessages(ws)
    const hello = await inbox.waitFor((m) => m.type === 'hello')
    return `${hello.sessions.length} 个会话，${hello.attention.length} 个待处理事项`
  })

  if (args.prompt && ws && inbox) {
    const stream = inbox
    const socket = ws
    console.log('\n发消息（--prompt，会消耗模型额度）')
    await check('新建会话并发消息，看到流式输出，最后收到「任务完成」', async () => {
      const created = await api('POST', '/v1/sessions', {})
      if (created.status !== 200) throw new Error(`新建会话返回 ${created.status}`)
      const sessionId = created.json().sessionId
      socket.send(JSON.stringify({ type: 'subscribe', sessionId }))
      await wait(300)
      const sent = await api('POST', `/v1/sessions/${sessionId}/prompt`, { requestId: randomUUID(), text: '这是 DSHRemote 的验证消息，请用三句话介绍一下你自己，不要调用任何工具。' })
      if (sent.status !== 200) throw new Error(`发消息返回 ${sent.status} ${JSON.stringify(sent.json().error)}`)
      const draft = await stream.waitFor((m) => m.type === 'message.upsert' && m.message.draft, 60_000).catch(() => undefined)
      await stream.waitFor((m) => m.type === 'message.upsert' && m.message.role === 'assistant' && !m.message.draft, 120_000)
      await stream.waitFor((m) => m.type === 'session.idle' && m.sessionId === sessionId, 60_000)
      facts.sawDraft = Boolean(draft)
      return `${draft ? '看到了流式草稿' : '没有看到流式草稿（回复太短也可能出现）'}；请在 Desktop 里确认出现了这个会话`
    })
  }

  // ------------------------------------------------ 清理
  console.log('\n清理')
  ws?.close()
  if (args.keep) {
    await check('保留这台临时设备，供重启 DSH 后用 --reuse 验证', async () => {
      writeFileSync(STATE_FILE, JSON.stringify({ token: device.token }), { mode: 0o600 })
      return `token 保存在 ${STATE_FILE}（验证完后用不带 --keep 的方式再运行一次即可删除）`
    })
  } else {
    await check('取消这台临时设备的配对', async () => {
      const res = await api('DELETE', '/v1/me')
      if (res.status !== 204) throw new Error(`返回 ${res.status}`)
      if ((await api('GET', '/v1/me')).status !== 401) throw new Error('取消配对后 token 仍然有效')
      if (existsSync(STATE_FILE)) rmSync(STATE_FILE)
    })
  }
}

try {
  await main()
} catch (error) {
  results.push({ name: '运行验证脚本', ok: false, detail: error instanceof Error ? error.message : String(error) })
  console.log(`\n✗ 验证脚本出错：${error instanceof Error ? error.message : String(error)}`)
}
const ok = results.length > 0 && results.every((r) => r.ok)
console.log(`\n${ok ? '全部通过。' : '有未通过的项目。'}请把下面这一行发给开发者（不包含 token 和配对码）：\n`)
console.log(JSON.stringify({ check: 'verify', ok, facts, results }))
process.exitCode = ok ? 0 : 1
