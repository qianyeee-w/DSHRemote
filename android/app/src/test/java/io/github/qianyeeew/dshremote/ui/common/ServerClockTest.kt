package io.github.qianyeeew.dshremote.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** 手机和电脑的时钟不一致时，「现在」按电脑的时钟算。 */
class ServerClockTest {
    @Test
    fun smallOffsetsAreIgnored() {
        assertEquals(1_000_000L, serverNow(1_000_000, 59_000))
        assertEquals(1_000_000L, serverNow(1_000_000, -59_000))
    }

    @Test
    fun largeOffsetsAreApplied() {
        val eightHours = 8 * 3_600_000L
        assertEquals(1_000_000L + eightHours, serverNow(1_000_000, eightHours))
        assertEquals(1_000_000L - eightHours, serverNow(1_000_000, -eightHours))
    }
}
