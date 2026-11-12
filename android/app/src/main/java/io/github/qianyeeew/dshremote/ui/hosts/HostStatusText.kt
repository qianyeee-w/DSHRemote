package io.github.qianyeeew.dshremote.ui.hosts

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.DesignTheme

/** 状态文字的颜色（设计规格 7.7）：次要文字、等你（字）、危险。 */
enum class StatusTone { NORMAL, WAITING, DANGER }

data class StatusText(
    @param:StringRes val text: Int,
    val tone: StatusTone,
)

/**
 * 电脑菜单里的短状态（设计规格 7.7）。状态正常时不显示（设计原则 2），返回 null；
 * 还没开始连接（[LinkStatus.Stopped]）也不算问题。
 */
fun menuStatus(status: LinkStatus): StatusText? =
    when (status) {
        is LinkStatus.Online, LinkStatus.Stopped -> null
        LinkStatus.Connecting -> StatusText(R.string.host_menu_connecting, StatusTone.NORMAL)
        is LinkStatus.Offline -> StatusText(R.string.host_menu_unreachable, StatusTone.NORMAL)
        LinkStatus.FingerprintMismatch -> StatusText(R.string.host_menu_rejected, StatusTone.DANGER)
        is LinkStatus.NeedsPairing -> StatusText(R.string.host_menu_needs_pairing, StatusTone.WAITING)
        is LinkStatus.DshIncompatible -> StatusText(R.string.host_menu_incompatible, StatusTone.WAITING)
        is LinkStatus.AppTooOld -> StatusText(R.string.host_menu_app_old, StatusTone.WAITING)
    }

/** 「电脑与设置」和单台电脑页里名字下面的一句状态（设计规格 7.8、7.9）。[current] 是首页正在用的那台。 */
fun lineStatus(
    status: LinkStatus,
    current: Boolean,
): StatusText =
    when (status) {
        is LinkStatus.Online -> StatusText(if (current) R.string.host_line_current else R.string.host_line_online, StatusTone.NORMAL)
        LinkStatus.Connecting -> StatusText(R.string.host_menu_connecting, StatusTone.NORMAL)
        LinkStatus.Stopped -> StatusText(R.string.host_line_stopped, StatusTone.NORMAL)
        is LinkStatus.Offline -> StatusText(R.string.host_menu_unreachable, StatusTone.NORMAL)
        LinkStatus.FingerprintMismatch -> StatusText(R.string.host_line_rejected, StatusTone.DANGER)
        is LinkStatus.NeedsPairing -> StatusText(R.string.host_menu_needs_pairing, StatusTone.WAITING)
        is LinkStatus.DshIncompatible -> StatusText(R.string.host_line_incompatible, StatusTone.WAITING)
        is LinkStatus.AppTooOld -> StatusText(R.string.host_menu_app_old, StatusTone.WAITING)
    }

@Composable
fun StatusTone.color(): Color =
    when (this) {
        StatusTone.NORMAL -> DesignTheme.colors.textSecondary
        StatusTone.WAITING -> DesignTheme.colors.waitingText
        StatusTone.DANGER -> DesignTheme.colors.danger
    }
