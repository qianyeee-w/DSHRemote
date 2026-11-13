package io.github.qianyeeew.dshremote.data.api

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// 用量与上下文（protocol.md 第 4 节「用量」、5.4 的 usage，落地方案 B4）。
// 拿不到的值是 null，界面上不显示，不能当成 0。
// ---------------------------------------------------------------------------

/** `GET /v1/sessions/{id}/usage`。 */
@Serializable
data class SessionUsage(
    val context: ContextUsage? = null,
    val total: TotalUsage? = null,
)

/** 下一次请求大约要带多少 token（估算值），和当前模型的上下文上限。 */
@Serializable
data class ContextUsage(
    val tokens: Long? = null,
    val window: Long? = null,
) {
    /** 用了上限的多少（0–1）。两项都拿到、上限大于零时才有。 */
    val ratio: Float?
        get() {
            val t = tokens ?: return null
            val w = window?.takeIf { it > 0 } ?: return null
            return (t.toDouble() / w).toFloat().coerceIn(0f, 1f)
        }
}

/** 整个会话累计的用量。四项互不重叠。 */
@Serializable
data class TotalUsage(
    val uncachedInputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
) {
    /** 输入合计：没走缓存的加上缓存读写。 */
    val inputTokens: Long get() = uncachedInputTokens + cacheReadTokens + cacheWriteTokens

    val cacheHit: Float? get() = cacheHitRatio(uncachedInputTokens, cacheReadTokens, cacheWriteTokens)
}

/** 一轮所有模型请求（包括重试）的用量合计。缓存两项只在每次请求都报告了时才有。 */
@Serializable
data class TurnUsage(
    val totalTokens: Long,
    val uncachedInputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
) {
    /** 缓存两项都拿到时才算得出命中率。 */
    val cacheHit: Float?
        get() {
            val read = cacheReadTokens ?: return null
            val write = cacheWriteTokens ?: return null
            return cacheHitRatio(uncachedInputTokens, read, write)
        }
}

/**
 * 缓存命中率（调研文档 9.2，和官方界面同一个口径）：缓存读取 ÷（未缓存输入 + 缓存读取 + 缓存写入）。
 * 输出不算在内；分母为零时没有（不显示），不是 0%。
 */
fun cacheHitRatio(
    uncachedInput: Long,
    cacheRead: Long,
    cacheWrite: Long,
): Float? {
    val denominator = uncachedInput + cacheRead + cacheWrite
    if (denominator <= 0) return null
    return (cacheRead.toDouble() / denominator).toFloat()
}
