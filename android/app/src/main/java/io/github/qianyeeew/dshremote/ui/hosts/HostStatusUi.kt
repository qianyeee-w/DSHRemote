package io.github.qianyeeew.dshremote.ui.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.OfflineReason
import io.github.qianyeeew.dshremote.data.live.PairingReason
import io.github.qianyeeew.dshremote.ui.LocalStatusColors

/** 状态是否需要用户处理（显示为错误色）。 */
val LinkStatus.needsAttention: Boolean
    get() =
        this is LinkStatus.NeedsPairing ||
            this is LinkStatus.FingerprintMismatch ||
            this is LinkStatus.AppTooOld ||
            this is LinkStatus.DshIncompatible

/** 需要重新配对才能恢复的状态。 */
val LinkStatus.offersRepair: Boolean
    get() = this is LinkStatus.NeedsPairing || this is LinkStatus.FingerprintMismatch

/**
 * 可以手动「立即重试」的状态。指纹不一致不在其中：网络变化后会自动再试，其他情况只能重新配对
 * （不提供任何绕过指纹校验的操作，红线第 2 条）。
 */
val LinkStatus.offersRetry: Boolean
    get() = this is LinkStatus.Offline || this is LinkStatus.DshIncompatible

@Composable
fun statusLabel(status: LinkStatus): String =
    stringResource(
        when (status) {
            is LinkStatus.Online -> R.string.status_online
            LinkStatus.Connecting -> R.string.status_connecting
            LinkStatus.Stopped -> R.string.status_stopped
            is LinkStatus.Offline -> R.string.status_offline
            is LinkStatus.DshIncompatible -> R.string.status_dsh_incompatible
            is LinkStatus.NeedsPairing -> R.string.status_needs_pairing
            LinkStatus.FingerprintMismatch -> R.string.status_fingerprint_mismatch
            is LinkStatus.AppTooOld -> R.string.status_app_too_old
        },
    )

@Composable
fun statusDetail(status: LinkStatus): String =
    when (status) {
        is LinkStatus.Online -> stringResource(R.string.status_detail_online, status.address.toString())
        LinkStatus.Connecting -> stringResource(R.string.status_detail_connecting)
        LinkStatus.Stopped -> stringResource(R.string.status_detail_stopped)
        is LinkStatus.Offline ->
            stringResource(
                when (status.reason) {
                    OfflineReason.UNREACHABLE -> R.string.status_detail_offline_unreachable
                    OfflineReason.DSH_UNAVAILABLE -> R.string.status_detail_offline_dsh
                    OfflineReason.TOO_MANY_CONNECTIONS -> R.string.status_detail_offline_too_many
                    OfflineReason.DISCONNECTED -> R.string.status_detail_offline_disconnected
                    OfflineReason.PROTOCOL_ERROR -> R.string.status_detail_offline_protocol
                },
            )
        is LinkStatus.DshIncompatible ->
            stringResource(
                R.string.status_detail_dsh_incompatible,
                status.dshVersion ?: stringResource(R.string.status_detail_dsh_unknown_version),
            )
        is LinkStatus.NeedsPairing ->
            stringResource(
                when (status.reason) {
                    PairingReason.TOKEN_REVOKED -> R.string.status_detail_revoked
                    PairingReason.CREDENTIALS_LOST -> R.string.status_detail_credentials_lost
                },
            )
        LinkStatus.FingerprintMismatch -> stringResource(R.string.status_detail_fingerprint_mismatch)
        is LinkStatus.AppTooOld -> stringResource(R.string.status_detail_app_too_old, status.protocol)
    }

/** 彩色圆点加状态文字。 */
@Composable
fun StatusBadge(
    status: LinkStatus,
    modifier: Modifier = Modifier,
) {
    val color =
        when {
            status is LinkStatus.Online -> LocalStatusColors.current.online
            status.needsAttention -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(6.dp)
                .background(color, CircleShape),
        )
        Text(
            statusLabel(status),
            style = MaterialTheme.typography.labelMedium,
            color = if (status.needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
