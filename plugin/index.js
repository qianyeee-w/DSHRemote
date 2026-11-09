// @ts-check
/**
 * DSHRemote 电脑端插件入口。
 *
 * 只负责组装各模块和管理生命周期；具体逻辑在 src/ 下。
 * 使用的 DSH 服务只有 webServer 和 connection（见 plugin/AGENTS.md）。
 */
import { parseConfig } from './src/config.js'
import { openStorage } from './src/storage.js'
import { loadOrCreateIdentity } from './src/tls/identity.js'
import { startLanServer } from './src/server/https.js'
import { createRouter } from './src/server/routes.js'
import { PairingCodes } from './src/pairing/codes.js'
import { DeviceRegistry } from './src/pairing/devices.js'
import { startAdminServer } from './src/admin/server.js'
import { openInBrowser } from './src/admin/open-browser.js'
import { DshConnection } from './src/dsh/connection.js'
import { Facade } from './src/dsh/facade.js'
import { AttentionTracker } from './src/dsh/attention.js'
import { LiveHub } from './src/dsh/live.js'
import { attachStream } from './src/server/stream.js'
import { dshVersionStatus } from './src/dsh/versions.js'
import { listenError } from './src/listen-error.js'
import { readFileSync } from 'node:fs'

export const name = 'dshremote'
export const inject = ['webServer', 'connection']

/** 日志前缀，便于在 DSH 的输出中筛选。 */
const LOG_PREFIX = '[dshremote]'
const PLUGIN_VERSION = String(JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).version)
/** 记录「是否已经自动打开过配对页」等不敏感的状态 */
const STATE_FILE = 'state.json'

/**
 * 配置非法、启动失败（例如端口被占用）时，返回的 Promise 被拒绝，DSH 把插件标记为失败（插件列表中显示错误信息），
 * DSH 本身继续运行。
 *
 * 两处 cordis 的行为决定了这里的写法（DSH 0.2.2-rc.1 内置的 cordis 4.1.0）：
 * - 只有 apply 本身失败，插件才会被标记为失败。`ctx.effect` 里的异步错误只会被记进日志，插件仍然显示为 active，
 *   所以要等 effect 启动完成（类型声明中，async effect 返回的清理函数可以 await）。
 * - **apply 必须是 async 函数**。普通的 `function` 有 prototype，cordis 会把它当构造函数 `new apply(...)` 调用并丢掉返回值，
 *   返回的 Promise 被拒绝时就成了未处理的拒绝，DSH 会因此直接退出（app-boot 的 fail-loud）。
 *
 * @param {any} ctx Cordis 插件上下文
 * @param {unknown} [rawConfig] cordis.patch.yml 中的配置
 * @returns {Promise<void>}
 */
export async function apply(ctx, rawConfig) {
  // 配置非法时直接抛出（Promise 被拒绝）：DSH 会把插件标记为加载失败，用户能在插件列表里看到
  const config = parseConfig(rawConfig)
  /** @param {'info' | 'warn' | 'error'} level @param {string} message */
  const log = (level, message) => ctx.logger?.[level]?.(`${LOG_PREFIX} ${message}`)

  if (!config.enabled) {
    log('info', '已安装但未启用（enabled: false）')
    return
  }

  await start(ctx, config, log)
}

/**
 * @param {any} ctx
 * @param {import('./src/config.js').PluginConfig} config
 * @param {(level: 'info' | 'warn' | 'error', message: string) => void} log
 */
async function start(ctx, config, log) {
  await ctx.effect(async () => {
    const storage = await openStorage()
    const identity = await loadOrCreateIdentity(storage)
    if (identity.status === 'created') log('warn', '已生成新的 TLS 身份。之前配对过的手机需要重新配对')
    if (identity.status === 'renewed') log('info', `证书已用原私钥重新签发（${identity.renewReason}），指纹不变`)

    const dshVersion = dshVersionStatus()
    if (dshVersion.version === null) log('warn', '读不到 DSH 的版本，无法确认兼容性')
    else if (!dshVersion.compatible) log('warn', `DSH ${dshVersion.version} 不在插件支持的版本范围内，部分功能可能无法使用`)
    // DSH 的端口每次启动都可能变化，每次请求都实时读取。连接层只支持 http，DSH 自己开了 TLS 时视为不可用
    const dsh = new DshConnection({
      getPort: () => (ctx.webServer.protocol === 'http:' ? ctx.webServer.port : undefined),
      authenticatedUrl: (baseUrl) => ctx.connection.authenticatedUrl(baseUrl),
      log,
    })
    // 跟踪审批和提问；它订阅 DSH 的应用事件，也让插件和 DSH 一直保持连接
    const attention = new AttentionTracker(dsh)
    const facade = new Facade(dsh, { attention })
    const hub = new LiveHub(dsh, facade, attention)

    const codes = new PairingCodes()
    const devices = await DeviceRegistry.open(storage)
    /** @param {string} event @param {Record<string, string>} [detail] */
    const audit = (event, detail) => log('info', `${event}${detail ? ` ${JSON.stringify(detail)}` : ''}`)
    const router = createRouter(
      {
        dshStatus: () => dshVersion,
        facade,
        codes,
        devices,
        // 审计信息只记录事件和设备标识，不包含 token 或配对码
        audit,
        // 切换模型和权限模式后立即推送新的会话摘要
        sessionChanged: () => hub.refreshSoon(),
      },
      { onError: (error) => log('error', `处理请求出错：${error instanceof Error ? error.message : String(error)}`) },
    )
    let lan
    try {
      lan = await startLanServer({ identity, host: config.lanBind, port: config.lanPort, handler: router })
    } catch (error) {
      hub.close()
      attention.close()
      dsh.close()
      const failure = listenError('lan', config.lanPort, error)
      log('error', failure.message)
      throw failure
    }
    const stream = attachStream({ server: lan.server, devices, hub, audit })
    let admin
    try {
      admin = await startAdminServer({
        port: config.adminPort,
        codes,
        devices,
        audit,
        info: () => ({
          fingerprint: identity.fingerprint,
          lanPort: lan.port(),
          lanBind: config.lanBind,
          pluginVersion: PLUGIN_VERSION,
          dsh: { ...dshVersion, state: dsh.status().state },
        }),
      })
    } catch (error) {
      stream.close()
      hub.close()
      attention.close()
      await lan.close()
      dsh.close()
      const failure = listenError('admin', config.adminPort, error)
      log('error', failure.message)
      throw failure
    }
    log('info', `已加载。局域网 HTTPS：${config.lanBind}:${lan.port()}，证书指纹：${identity.fingerprint}，配对页：${admin.url}`)

    if (config.openAdminOnFirstRun) {
      const state = /** @type {{ adminOpenedAt?: number } | undefined} */ (await storage.readJson(STATE_FILE))
      if (!state?.adminOpenedAt) {
        // 浏览器进程启动成功后才记下「已经打开过」，启动失败时下次加载还会再试
        const opened = await openInBrowser(admin.url).catch(() => false)
        if (opened) await storage.writeJson(STATE_FILE, { ...state, adminOpenedAt: Date.now() })
        else log('warn', `无法自动打开配对页，请手动访问 ${admin.url}`)
      }
    }

    const adminServer = admin
    return async () => {
      stream.close()
      hub.close()
      attention.close()
      dsh.close()
      await adminServer.close()
      await lan.close()
      await devices.flush()
      log('info', '已卸载')
    }
  }, 'dshremote lifecycle')
}
