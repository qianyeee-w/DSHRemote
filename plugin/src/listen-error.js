// @ts-check
/**
 * 插件的端口监听失败时，给用户看的错误。
 *
 * 插件启动失败时，DSH 会把插件标记为失败，并把错误信息作为诊断显示在插件列表里（pluginInventory 的 diagnostic），
 * 同时写进日志。所以错误信息要让用户知道是哪个端口、怎么处理。不包含任何秘密。
 */

/** @typedef {'lan' | 'admin'} ListenTarget */

const TARGETS = {
  lan: { label: '局域网端口', key: 'lanPort' },
  admin: { label: '配对页端口', key: 'adminPort' },
}

/**
 * @param {ListenTarget} target 哪个端口
 * @param {number} port 配置中的端口
 * @param {unknown} error 监听时的错误
 * @returns {Error} 带有处理方法的错误，原始错误放在 cause 中
 */
export function listenError(target, port, error) {
  const { label, key } = TARGETS[target]
  const code = /** @type {NodeJS.ErrnoException} */ (error)?.code
  const message = code === 'EADDRINUSE'
    ? `${label} ${port} 已被其他程序占用，插件没能启动。请关闭占用它的程序（常见的是 VS Code 的端口转发，或者另一个装了本插件的 DSH），或在插件配置中修改 ${key}`
    : `${label} ${port} 监听失败（${code ?? (error instanceof Error ? error.message : String(error))}），插件没能启动。请检查插件配置中的 ${key}${target === 'lan' ? ' 和 lanBind' : ''}`
  return new Error(message, { cause: error })
}
