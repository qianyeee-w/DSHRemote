package io.github.qianyeeew.dshremote.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 正在看的会话按次数持有（落地方案 B5：会话页和计划页同时持有同一个会话）。 */
class VisibleSessionsTest {
    private val s1 = VisibleSession("h1", "s1")
    private val s2 = VisibleSession("h1", "s2")

    @Test
    fun goingToThePlanPageAndBackNeverClearsTheSession() {
        val visible = VisibleSessions()
        val chat = visible.hold(s1)
        // 进计划页：计划页先开始，会话页后停止
        val plan = visible.hold(s1)
        chat()
        assertEquals(s1, visible.current.value)
        // 返回：会话页先开始，计划页后停止
        val chatAgain = visible.hold(s1)
        plan()
        assertEquals(s1, visible.current.value)
        chatAgain()
        assertNull(visible.current.value)
    }

    @Test
    fun releasingTwiceCountsOnceAndTheLastHeldSessionWins() {
        val visible = VisibleSessions()
        val first = visible.hold(s1)
        val second = visible.hold(s2)
        assertEquals(s2, visible.current.value)
        second()
        second()
        assertEquals(s1, visible.current.value)
        first()
        assertNull(visible.current.value)
    }
}
