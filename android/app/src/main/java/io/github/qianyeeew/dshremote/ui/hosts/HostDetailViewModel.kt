package io.github.qianyeeew.dshremote.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.host.HostNames
import io.github.qianyeeew.dshremote.data.host.HostRepository
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.pairing.Pairer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

data class HostDetail(
    val host: Host,
    val status: LinkStatus,
)

/** 删除主机的进度。 */
sealed interface Removal {
    data object None : Removal

    data object InProgress : Removal

    /** 已删除。[revokedOnComputer] 为 false 时提示用户到配对页上撤销这台手机。 */
    data class Done(
        val revokedOnComputer: Boolean,
    ) : Removal
}

/** 地址输入的检查结果。 */
enum class AddressError {
    INVALID,
    DUPLICATE,
    TOO_MANY,
}

/** 主机管理：重命名、编辑地址、删除、重试（重新配对由配对页面完成，m2-android.md T7）。 */
class HostDetailViewModel(
    private val hostId: String,
    hosts: Flow<List<Host>>,
    states: Flow<Map<String, HostLiveState>>,
    private val repository: HostRepository,
    private val pairer: Pairer,
    private val retry: (hostId: String) -> Unit,
) : ViewModel() {
    private var removing = false

    /** null 表示还在读取，或者主机已经不存在。 */
    val detail: StateFlow<HostDetail?> =
        combine(hosts, states) { list, live ->
            list.firstOrNull { it.hostId == hostId }?.let { HostDetail(it, statusOf(it, live[hostId])) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _removal = MutableStateFlow<Removal>(Removal.None)
    val removal: StateFlow<Removal> = _removal.asStateFlow()

    /** 名称清理后为空时返回 false，不保存。 */
    fun rename(name: String): Boolean {
        if (HostNames.clean(name).isEmpty()) return false
        update { repository.rename(hostId, name) }
        return true
    }

    /**
     * 添加（[index] 为 null）或修改第 [index] 个地址。输入格式为 `地址:端口`，IPv6 要加方括号。
     * 地址不是信任的依据，修改后不需要重新配对（m2-android.md 3.2）。
     */
    fun saveAddress(
        index: Int?,
        text: String,
    ): AddressError? {
        val host = detail.value?.host ?: return null
        val address = HostAddress.parse(text) ?: return AddressError.INVALID
        val others = host.addresses.filterIndexed { i, _ -> i != index }
        if (address in others) return AddressError.DUPLICATE
        if (index == null && host.addresses.size >= HostRepository.MAX_ADDRESSES) return AddressError.TOO_MANY
        val updated = if (index == null) host.addresses + address else host.addresses.mapIndexed { i, a -> if (i == index) address else a }
        update { repository.setAddresses(hostId, updated) }
        return null
    }

    /** 删除一个地址。至少保留一个，只剩一个时什么也不做。 */
    fun removeAddress(index: Int) {
        val host = detail.value?.host ?: return
        if (host.addresses.size <= 1 || index !in host.addresses.indices) return
        update { repository.setAddresses(hostId, host.addresses.filterIndexed { i, _ -> i != index }) }
    }

    fun retryNow() = retry(hostId)

    /** 删除这台主机：先尽量在电脑上撤销这台手机，再删除本地记录。 */
    fun remove() {
        val host = detail.value?.host ?: return
        if (removing) return
        removing = true
        _removal.value = Removal.InProgress
        viewModelScope.launch {
            _removal.value =
                try {
                    Removal.Done(pairer.unpair(host))
                } catch (_: IOException) {
                    // 本地记录删除失败（存储出错），可以再试一次
                    removing = false
                    Removal.None
                }
        }
    }

    /** 主机在操作过程中被删除（例如在另一个页面）时，修改会抛出 IllegalArgumentException，忽略即可。 */
    private fun update(change: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                change()
            } catch (_: IllegalArgumentException) {
            } catch (_: IOException) {
            }
        }
    }
}
