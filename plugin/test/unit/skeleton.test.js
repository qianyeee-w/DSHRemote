// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile, mkdtemp, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import net from 'node:net'
import { DEFAULT_CONFIG } from '../../src/config.js'
import * as plugin from '../../index.js'
import { DSH_BASELINE_VERSION, SUPPORTED_DSH_RANGE } from '../../src/dsh/versions.js'

const pkg = JSON.parse(await readFile(new URL('../../package.json', import.meta.url), 'utf8'))

test('插件导出 name / inject / apply', () => {
  assert.equal(plugin.name, 'dshremote')
  assert.deepEqual(plugin.inject, ['webServer', 'connection'])
  assert.equal(typeof plugin.apply, 'function')
})

test('apply 是 async 函数：cordis 会把带 prototype 的普通函数当构造函数调用并丢掉返回值', () => {
  // cordis 4.1.0 的 isConstructor：有 prototype（且不是 generator）就用 new 调用。返回的 Promise 被丢掉后，
  // 启动失败就成了未处理的拒绝，DSH 会直接退出（load-check 的端口占用检查在真实 DSH 上覆盖这一点）
  assert.equal(plugin.apply.prototype, undefined)
  assert.equal(plugin.apply.constructor.name, 'AsyncFunction')
})

test('只注入允许的 DSH 服务（plugin/AGENTS.md）', () => {
  for (const service of plugin.inject) {
    assert.ok(['webServer', 'connection'].includes(service), `不允许注入 ${service}`)
  }
})

test('支持的 DSH 版本范围只有一个来源：package.json 与 versions.js 一致', () => {
  assert.equal(pkg.engines.dsh, SUPPORTED_DSH_RANGE)
  assert.ok(SUPPORTED_DSH_RANGE.startsWith(`>=${DSH_BASELINE_VERSION} `))
})

test('enabled: false 时不注册任何生命周期', async () => {
  let effects = 0
  const ctx = { effect: () => { effects++ }, logger: { info() {} } }
  await plugin.apply(ctx, { enabled: false })
  assert.equal(effects, 0)
})

test('默认启用：注册一个生命周期，打开状态目录，卸载函数可调用', async () => {
  const home = await mkdtemp(join(tmpdir(), 'dshremote-skeleton-'))
  const previous = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    /** @type {Promise<() => Promise<void>> | undefined} */
    let pending
    const ctx = {
      /** @param {() => Promise<() => Promise<void>>} fn */
      effect: (fn) => { pending = fn() },
      logger: { info() {}, warn() {}, error() {} },
    }
    // 端口用 0 之外的随机高位端口，避免和本机已有服务冲突
    const lanPort = 20000 + Math.floor(Math.random() * 20000)
    await plugin.apply(ctx, { enabled: true, lanBind: '127.0.0.1', lanPort, adminPort: lanPort + 1, openAdminOnFirstRun: false })
    assert.ok(pending, '应当注册生命周期')
    const dispose = await pending
    assert.ok((await stat(join(home, 'dshremote'))).isDirectory())
    assert.ok((await stat(join(home, 'dshremote', 'tls-cert.pem'))).isFile())
    assert.equal(typeof dispose, 'function')
    await dispose()
  } finally {
    if (previous === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = previous
    await rm(home, { recursive: true, force: true })
  }
})

/**
 * 模拟 cordis 的 ctx.effect：立即运行 effect，返回一个清理函数；这个清理函数可以 await，
 * 在 effect 启动完成后兑现，启动失败时拒绝（cordis 的 Fiber.effect）。
 */
function cordisLikeCtx() {
  /** @type {(() => Promise<void>) | undefined} */
  let disposer
  const ctx = {
    /** @param {() => Promise<() => Promise<void>>} fn */
    effect: (fn) => {
      const task = fn().then((d) => { disposer = d })
      const dispose = async () => { await task.catch(() => {}); await disposer?.() }
      // 和 cordis 一样，兑现的值是一个普通函数（不能是 dispose 本身：它带 then，Promise 会无限展开）
      const plain = () => dispose()
      return Object.assign(dispose, { then: (/** @type {any} */ ok, /** @type {any} */ fail) => task.then(() => plain).then(ok, fail) })
    },
    logger: { info() {}, warn() {}, error() {} },
    dispose: () => disposer?.(),
  }
  return ctx
}

/** @param {number} port @returns {Promise<net.Server>} */
function occupy(port) {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(port, '127.0.0.1', () => resolve(server))
  })
}

/** @param {number} port @returns {Promise<boolean>} 端口是否空闲 */
function isFree(port) {
  return new Promise((resolve) => {
    const probe = net.createServer()
    probe.once('error', () => resolve(false))
    probe.listen(port, '127.0.0.1', () => probe.close(() => resolve(true)))
  })
}

/** @param {(home: string) => Promise<void>} fn */
async function withHome(fn) {
  const home = await mkdtemp(join(tmpdir(), 'dshremote-skeleton-'))
  const previous = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    await fn(home)
  } finally {
    if (previous === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = previous
    await rm(home, { recursive: true, force: true })
  }
}

test('apply 等启动完成后才返回：启动成功时兑现，卸载后端口释放', async () => {
  await withHome(async () => {
    const ctx = cordisLikeCtx()
    const lanPort = 20000 + Math.floor(Math.random() * 20000)
    const started = plugin.apply(ctx, { enabled: true, lanBind: '127.0.0.1', lanPort, adminPort: lanPort + 1, openAdminOnFirstRun: false })
    assert.ok(started instanceof Promise, 'apply 应当返回 Promise，cordis 才会等待启动结果')
    await started
    assert.equal(await isFree(lanPort), false, '局域网端口应当在监听')
    await ctx.dispose()
    assert.equal(await isFree(lanPort), true)
    assert.equal(await isFree(lanPort + 1), true)
  })
})

test('局域网端口被占用：apply 被拒绝（DSH 把插件标记为失败），错误指出端口和处理方法', async () => {
  await withHome(async () => {
    const lanPort = 20000 + Math.floor(Math.random() * 20000)
    const blocker = await occupy(lanPort)
    try {
      const errors = /** @type {string[]} */ ([])
      const ctx = { ...cordisLikeCtx(), logger: { info() {}, warn() {}, error: (/** @type {string} */ m) => errors.push(m) } }
      await assert.rejects(
        plugin.apply(ctx, { enabled: true, lanBind: '127.0.0.1', lanPort, adminPort: lanPort + 1, openAdminOnFirstRun: false }),
        (error) => {
          assert.ok(error instanceof Error)
          assert.match(error.message, new RegExp(`局域网端口 ${lanPort} 已被其他程序占用`))
          assert.match(error.message, /lanPort/)
          return true
        },
      )
      assert.equal(errors.length, 1, '日志中也要有这条错误')
      assert.equal(await isFree(lanPort + 1), true, '配对页不应启动')
    } finally {
      await new Promise((r) => blocker.close(r))
    }
  })
})

test('配对页端口被占用：apply 被拒绝，已经启动的局域网服务被关闭', async () => {
  await withHome(async () => {
    const lanPort = 20000 + Math.floor(Math.random() * 20000)
    const blocker = await occupy(lanPort + 1)
    try {
      await assert.rejects(
        plugin.apply(cordisLikeCtx(), { enabled: true, lanBind: '127.0.0.1', lanPort, adminPort: lanPort + 1, openAdminOnFirstRun: false }),
        new RegExp(`配对页端口 ${lanPort + 1} 已被其他程序占用.*adminPort`),
      )
      assert.equal(await isFree(lanPort), true, '局域网端口应当已经释放')
    } finally {
      await new Promise((r) => blocker.close(r))
    }
  })
})

test('配置非法时 apply 被拒绝，让 DSH 把插件标记为加载失败', async () => {
  const ctx = { effect() { assert.fail('不应注册生命周期') }, logger: { info() {} } }
  await assert.rejects(plugin.apply(ctx, { lanPort: 'abc' }), /配置错误/)
  await assert.rejects(plugin.apply(ctx, { unknownKey: 1 }), /未知的配置项/)
})

test('cordis.patch.yml 的默认配置与 DEFAULT_CONFIG 一致', async () => {
  const yml = await readFile(new URL('../../cordis.patch.yml', import.meta.url), 'utf8')
  for (const [key, value] of Object.entries(DEFAULT_CONFIG)) {
    assert.match(yml, new RegExp(`^\\s+${key}: ${String(value).replaceAll('.', '\\.')}$`, 'm'), `cordis.patch.yml 中 ${key} 应为 ${value}`)
  }
})
