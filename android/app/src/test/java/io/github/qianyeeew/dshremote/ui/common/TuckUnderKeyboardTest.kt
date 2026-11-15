package io.github.qianyeeew.dshremote.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TuckUnderKeyboardTest {
    private val row = 44
    private val gap = 8

    /** 没有键盘：整行都占位。 */
    @Test
    fun noKeyboardShowsTheWholeRow() {
        assertEquals(row, tuckedHeight(row, ime = 0, gap = gap, exposed = false))
    }

    /** 键盘弹出：这一行藏在键盘后面，只留空隙，输入框贴着键盘。 */
    @Test
    fun keyboardHidesTheRowBehindIt() {
        assertEquals(gap, tuckedHeight(row, ime = 900, gap = gap, exposed = false))
    }

    /**
     * 键盘收起的过程中，输入框底边（键盘 + 这一行占的高度）随键盘连续下移，到整行高时停住，
     * 不会先落到底再被顶上去（用户真机走查的问题）。
     */
    @Test
    fun composerFollowsTheKeyboardDownWithoutBouncing() {
        var previous = Int.MAX_VALUE
        for (ime in 900 downTo 0 step 10) {
            val bottom = ime + tuckedHeight(row, ime, gap, exposed = false)
            assertEquals(maxOf(row, ime + gap), bottom)
            // 只往下走，不回弹
            assertTrue(bottom <= previous)
            previous = bottom
        }
        assertEquals(row, previous)
    }

    /** 面板开着时整行占位（面板自己补足键盘让出的那一截）。 */
    @Test
    fun exposedRowAlwaysTakesItsFullHeight() {
        assertEquals(row, tuckedHeight(row, ime = 900, gap = gap, exposed = true))
        assertEquals(row, tuckedHeight(row, ime = 0, gap = gap, exposed = true))
    }
}
