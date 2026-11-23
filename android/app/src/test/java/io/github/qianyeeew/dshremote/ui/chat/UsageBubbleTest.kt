package io.github.qianyeeew.dshremote.ui.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** 按住发送键时用量气泡的位置（设计规格 7.11）。 */
class UsageBubbleTest {
    private val window = IntSize(1080, 2400)
    private val bubble = IntSize(840, 400)

    @Test
    fun sitsAboveTheSendKeyWithRightEdgesAligned() {
        // 发送键在右下角：气泡右边和它对齐，底边在它上方 18
        val key = IntRect(left = 904, top = 2000, right = 1000, bottom = 2096)
        assertEquals(IntOffset(1000 - 840, 2000 - 18 - 400), usageBubblePosition(key, window, bubble, gap = 18, margin = 36))
    }

    @Test
    fun staysInsideTheWindowSides() {
        // 发送键太靠左：气泡左边至少留出 margin
        val left = IntRect(0, 2000, 96, 2096)
        assertEquals(36, usageBubblePosition(left, window, bubble, gap = 18, margin = 36).x)
        // 发送键贴着右边：气泡右边也留出 margin
        val right = IntRect(1000, 2000, 1080, 2096)
        assertEquals(1080 - 840 - 36, usageBubblePosition(right, window, bubble, gap = 18, margin = 36).x)
        // 窗口比气泡还窄：贴着左边的 margin
        assertEquals(36, usageBubblePosition(right, IntSize(800, 2400), bubble, gap = 18, margin = 36).x)
    }

    @Test
    fun goesBelowWhenThereIsNoRoomAbove() {
        val key = IntRect(960, 100, 1056, 196)
        assertEquals(196 + 18, usageBubblePosition(key, window, bubble, gap = 18, margin = 36).y)
    }
}
