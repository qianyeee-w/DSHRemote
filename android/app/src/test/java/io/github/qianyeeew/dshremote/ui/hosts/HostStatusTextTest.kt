package io.github.qianyeeew.dshremote.ui.hosts

import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.OfflineReason
import io.github.qianyeeew.dshremote.data.live.PairingReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 电脑菜单的短状态（设计规格 7.7）和「电脑与设置」里的一句状态（7.8）。 */
class HostStatusTextTest {
    private val online = LinkStatus.Online(HostAddress("192.168.1.5", 3190))

    @Test
    fun menuShowsNothingWhenTheComputerIsFine() {
        assertNull(menuStatus(online))
        assertNull(menuStatus(LinkStatus.Stopped))
    }

    @Test
    fun menuShortStatusesFollowTheDesignTable() {
        val cases =
            mapOf(
                LinkStatus.Connecting to StatusText(R.string.host_menu_connecting, StatusTone.NORMAL),
                LinkStatus.Offline(OfflineReason.UNREACHABLE, 1_000) to StatusText(R.string.host_menu_unreachable, StatusTone.NORMAL),
                LinkStatus.FingerprintMismatch to StatusText(R.string.host_menu_rejected, StatusTone.DANGER),
                LinkStatus.NeedsPairing(PairingReason.TOKEN_REVOKED) to StatusText(R.string.host_menu_needs_pairing, StatusTone.WAITING),
                LinkStatus.DshIncompatible("0.1.0") to StatusText(R.string.host_menu_incompatible, StatusTone.WAITING),
                LinkStatus.AppTooOld(9) to StatusText(R.string.host_menu_app_old, StatusTone.WAITING),
            )
        cases.forEach { (status, expected) -> assertEquals(status.toString(), expected, menuStatus(status)) }
    }

    @Test
    fun lineStatusSaysWhichComputerIsInUse() {
        assertEquals(StatusText(R.string.host_line_current, StatusTone.NORMAL), lineStatus(online, current = true))
        assertEquals(StatusText(R.string.host_line_online, StatusTone.NORMAL), lineStatus(online, current = false))
        assertEquals(StatusText(R.string.host_line_rejected, StatusTone.DANGER), lineStatus(LinkStatus.FingerprintMismatch, current = true))
        assertEquals(
            StatusText(R.string.host_line_incompatible, StatusTone.WAITING),
            lineStatus(LinkStatus.DshIncompatible(null), current = false),
        )
        assertEquals(StatusText(R.string.host_line_stopped, StatusTone.NORMAL), lineStatus(LinkStatus.Stopped, current = false))
    }
}
