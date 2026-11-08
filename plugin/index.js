// @ts-check
/**
 * DSHRemote 电脑端插件入口。
 *
 * 只负责组装各模块和管理生命周期；具体逻辑在 src/ 下。
 * 使用的 DSH 服务只有 webServer 和 connection（见 plugin/AGENTS.md）。
 */
import { parseConfig } from './src/config.js'
import { openStorage } from './src/storage.js'

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
    // 加载时不阻塞：后续任务在这里启动各项服务
    const storage = await openStorage()
    log('info', `已加载，状态目录：${storage.dir}`)
    return () => {
      log('info', '已卸载')
    }
  }, 'dshremote lifecycle')
}
