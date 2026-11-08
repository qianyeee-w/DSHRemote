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
import { dshVersionStatus } from './src/dsh/versions.js'
import { readFileSync } from 'node:fs'

export const name = 'dshremote'
export const inject = ['webServer', 'connection']

/** 日志前缀，便于在 DSH 的输出中筛选。 */
const LOG_PREFIX = '[dshremote]'
const PLUGIN_VERSION = String(JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).version)
/** 记录「是否已经自动打开过配对页」等不敏感的状态 */
const STATE_FILE = 'state.json'

/**
 * @param {any} ctx Cordis 插件上下文
 * @param {unknown} [rawConfig] cordis.patch.yml 中的配置
 */
export function apply(ctx, rawConfig) {
  // 配置非法时直接抛出：DSH 会把插件标记为加载失败，用户能在插件列表里看到
  const config = parseConfig(rawConfig)
  /** @param {'info' | 'warn' | 'error'} level @param {string} message */
  const log = (level, message) => ctx.logger?.[level]?.(`${LOG_PREFIX} ${message}`)

  if (!config.enabled) {
    log('info', '已安装但未启用（enabled: false）')
    return
  }

  ctx.effect(async () => {
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
    // 订阅 DSH 的应用事件，保持连接；审批和提问的处理在 T8 接入
    dsh.onEvent(() => {})

    const codes = new PairingCodes()
    const devices = await DeviceRegistry.open(storage)
    /** @param {string} event @param {Record<string, string>} [detail] */
    const audit = (event, detail) => log('info', `${event}${detail ? ` ${JSON.stringify(detail)}` : ''}`)
    const router = createRouter(
      {
        dshStatus: () => dshVersion,
        facade: new Facade(dsh),
        codes,
        devices,
        // 审计信息只记录事件和设备标识，不包含 token 或配对码
        audit,
      },
      { onError: (error) => log('error', `处理请求出错：${error instanceof Error ? error.message : String(error)}`) },
    )
    let lan
    try {
      lan = await startLanServer({ identity, host: config.lanBind, port: config.lanPort, handler: router })
    } catch (error) {
      const code = /** @type {NodeJS.ErrnoException} */ (error).code
      const hint = code === 'EADDRINUSE' ? `端口 ${config.lanPort} 已被占用，请在配置中修改 lanPort` : String(error)
      log('error', `局域网服务启动失败：${hint}`)
      dsh.close()
      throw error
    }
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
      await lan.close()
      dsh.close()
      const code = /** @type {NodeJS.ErrnoException} */ (error).code
      const hint = code === 'EADDRINUSE' ? `端口 ${config.adminPort} 已被占用，请在配置中修改 adminPort` : String(error)
      log('error', `配对页启动失败：${hint}`)
      throw error
    }
    log('info', `已加载。局域网 HTTPS：${config.lanBind}:${lan.port()}，证书指纹：${identity.fingerprint}，配对页：${admin.url}`)

    if (config.openAdminOnFirstRun) {
      const state = /** @type {{ adminOpenedAt?: number } | undefined} */ (await storage.readJson(STATE_FILE))
      if (!state?.adminOpenedAt) {
        await storage.writeJson(STATE_FILE, { ...state, adminOpenedAt: Date.now() })
        try { openInBrowser(admin.url) } catch (error) { log('warn', `无法自动打开配对页：${String(error)}`) }
      }
    }

    const adminServer = admin
    return async () => {
      dsh.close()
      await adminServer.close()
      await lan.close()
      await devices.flush()
      log('info', '已卸载')
    }
  }, 'dshremote lifecycle')
}
