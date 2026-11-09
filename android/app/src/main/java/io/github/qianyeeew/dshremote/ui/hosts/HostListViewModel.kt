package io.github.qianyeeew.dshremote.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.PairingReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 主机列表中的一行。 */
data class HostRow(
    val hostId: String,
    val name: String,
    /** 在线时是正在使用的地址，否则是最近一次连接成功的地址或第一个地址。 */
    val address: HostAddress?,
    val status: LinkStatus,
)

/** 首页：已配对的主机和各自的在线状态（m2-android.md 3.3「在线状态」）。 */
class HostListViewModel(
    hosts: Flow<List<Host>>,
    states: Flow<Map<String, HostLiveState>>,
) : ViewModel() {
    /** null 表示还在读取主机列表。 */
    val rows: StateFlow<List<HostRow>?> =
        combine(hosts, states) { list, live ->
            list.map { host ->
                val status = statusOf(host, live[host.hostId])
                HostRow(
                    hostId = host.hostId,
                    name = host.name,
                    address = (status as? LinkStatus.Online)?.address ?: host.lastGoodAddress ?: host.addresses.firstOrNull(),
                    status = status,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * 界面上显示的状态。凭据无法解密时，不论连接是否在运行，都显示「需要重新配对」。
 */
fun statusOf(
    host: Host,
    live: HostLiveState?,
): LinkStatus =
    if (host.credentials == null) {
        LinkStatus.NeedsPairing(PairingReason.CREDENTIALS_LOST)
    } else {
        live?.status ?: LinkStatus.Stopped
    }
