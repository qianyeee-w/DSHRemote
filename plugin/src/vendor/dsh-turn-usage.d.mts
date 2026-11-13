/** 一轮所有模型请求的用量合计（见 dsh-turn-usage.mjs）。 */
export interface TurnTokenUsage {
  readonly uncachedInputTokens: number
  readonly outputTokens: number
  readonly totalTokens: number
  readonly cacheReadTokens?: number
  readonly cacheWriteTokens?: number
  readonly reasoningTokens?: number
  readonly routes?: readonly { readonly provider: string, readonly model: string }[]
}

/**
 * 从一轮的事件（turn/start 到 turn/end）算出准确的用量；边界缺失、数字对不上时返回 undefined。
 * @param events DSH 会话日志里的事件（record.event）
 */
export declare function deriveTurnTokenUsage(events: readonly unknown[]): TurnTokenUsage | undefined
