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

export const name = 'dshremote'
export const inject = ['webServer', 'connection']

/** 日志前缀，便于在 DSH 的输出中筛选。 */
const LOG_PREFIX = '[dshremote]'

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

    const router = createRouter(
      // DSH 版本检查在 T6 实现
      { dshStatus: () => ({ version: null, compatible: null }) },
      { onError: (error) => log('error', `处理请求出错：${error instanceof Error ? error.message : String(error)}`) },
    )
    let lan
    try {
      lan = await startLanServer({ identity, host: config.lanBind, port: config.lanPort, handler: router })
    } catch (error) {
      const code = /** @type {NodeJS.ErrnoException} */ (error).code
      const hint = code === 'EADDRINUSE' ? `端口 ${config.lanPort} 已被占用，请在配置中修改 lanPort` : String(error)
      log('error', `局域网服务启动失败：${hint}`)
      throw error
    }
    log('info', `已加载。局域网 HTTPS：${config.lanBind}:${lan.port()}，证书指纹：${identity.fingerprint}`)

    return async () => {
      await lan.close()
      log('info', '已卸载')
    }
  }, 'dshremote lifecycle')
}
