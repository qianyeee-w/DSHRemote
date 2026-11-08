// @ts-check
/**
 * DSHRemote 电脑端插件入口。
 *
 * 只负责组装各模块和管理生命周期；具体逻辑在 src/ 下。
 * 使用的 DSH 服务只有 webServer 和 connection（见 plugin/AGENTS.md）。
 */

export const name = 'dshremote'
export const inject = ['webServer', 'connection']

/** 日志前缀，便于在 DSH 的输出中筛选。 */
const LOG_PREFIX = '[dshremote]'

/**
 * @param {any} ctx Cordis 插件上下文
 * @param {unknown} [rawConfig] cordis.patch.yml 中的配置
 */
export function apply(ctx, rawConfig) {
  const config = /** @type {{ enabled?: unknown } | undefined} */ (rawConfig)
  if (config?.enabled === false) {
    ctx.logger?.info?.(`${LOG_PREFIX} 已安装但未启用（enabled: false）`)
    return
  }
  ctx.effect(() => {
    ctx.logger?.info?.(`${LOG_PREFIX} 已加载`)
    return () => {
      ctx.logger?.info?.(`${LOG_PREFIX} 已卸载`)
    }
  }, 'dshremote lifecycle')
}
