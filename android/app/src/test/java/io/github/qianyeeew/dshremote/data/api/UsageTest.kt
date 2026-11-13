package io.github.qianyeeew.dshremote.data.api

import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.ui.chat.contextPhrase
import io.github.qianyeeew.dshremote.ui.chat.formatTokens
import io.github.qianyeeew.dshremote.ui.chat.percent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 用量的口径（调研文档 9.2、设计规格 7.11，落地方案 B4）。 */
class UsageTest {
    @Test
    fun cacheHitUsesAllInputAsTheDenominatorAndIsAbsentWhenItIsZero() {
        // 缓存读取 ÷（未缓存 + 读 + 写），输出不算
        assertEquals(0.71f, cacheHitRatio(25, 71, 4)!!, 0.0001f)
        assertEquals(0f, cacheHitRatio(10, 0, 0)!!, 0f)
        assertNull("分母为零时不显示，不是 0%", cacheHitRatio(0, 0, 0))
        assertEquals(182L, TotalUsage(41, 12, 138, 3).inputTokens)
        // 本轮缺了缓存的任何一项就算不出来
        assertNull(TurnUsage(15, 10, 5).cacheHit)
        assertNull(TurnUsage(15, 10, 5, cacheReadTokens = 3).cacheHit)
        assertEquals(0.5f, TurnUsage(30, 10, 5, cacheReadTokens = 10, cacheWriteTokens = 0).cacheHit!!, 0f)
    }

    @Test
    fun contextRatioNeedsBothNumbers() {
        assertEquals(0.25f, ContextUsage(32_768, 131_072).ratio!!, 0f)
        assertNull(ContextUsage(tokens = 100).ratio)
        assertNull(ContextUsage(window = 100).ratio)
        assertNull(ContextUsage(100, 0).ratio)
        assertEquals(1f, ContextUsage(200, 100).ratio!!, 0f)
    }

    @Test
    fun tokenCountsAndPhrases() {
        assertEquals(
            listOf("850", "1.2k", "1k", "59k", "128k", "1M", "1.5M"),
            listOf(850L, 1_234L, 1_000L, 59_210L, 128_000L, 1_048_576L, 1_500_000L).map(::formatTokens),
        )
        assertEquals(71, percent(0.7149f))
        assertEquals(R.string.usage_context_low, contextPhrase(0.44f))
        assertEquals(R.string.usage_context_half, contextPhrase(0.5f))
        assertEquals(R.string.usage_context_high, contextPhrase(0.79f))
        assertEquals(R.string.usage_context_full, contextPhrase(0.8f))
    }

    @Test
    fun turnMergeKeepsUsageFromWhicheverSourceHadIt() {
        val ended = Turn(3, endedAt = 9, result = TurnResult.COMPLETED, usage = TurnUsage(15, 10, 5))
        assertEquals(ended.usage, ended.merge(Turn(3, startedAt = 1)).usage)
        assertEquals(ended.usage, Turn(3, startedAt = 1).merge(ended).usage)
    }
}
