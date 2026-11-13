package io.github.qianyeeew.dshremote.data.seen

import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.service.VisibleSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 在进程里一直跟着实时连接，维护「看过」的记录（落地方案 B2）：
 *
 * - 第一次连上一台电脑时记下起点，之前结束的结果都算看过。
 * - 正在看的会话（会话页可见）：它最近一轮结束了就算看过，页面开着时新结束的一轮也算。
 * - 在线期间每隔 [heartbeatMillis] 记一次「上次在线」，断开时也记一次。App 被杀掉后，下次连上时据此算出离线了多久。
 * - 离线至少 [thresholdMillis] 后重新连上：给首页一个重连提示（[reconnects]），用户进入这台电脑的任何会话、
 *   或者首页调用 [dismiss] 后消失。离线期间没有新结果时首页不显示（用户确认）。
 *
 * 通知不受影响：运行结束的通知只在实时收到 `session.idle` 时发，离线期间结束的结果重连后不补发（[io.github.qianyeeew.dshremote.service.NotificationRules]）。
 */
class ReadTracker(
    private val scope: CoroutineScope,
    private val store: SeenRecords,
    private val states: StateFlow<Map<String, HostLiveState>>,
    private val visible: StateFlow<VisibleSession?>,
    private val now: () -> Long = System::currentTimeMillis,
    private val thresholdMillis: Long = RECONNECT_THRESHOLD_MILLIS,
    private val heartbeatMillis: Long = HEARTBEAT_MILLIS,
) {
    /** 每台电脑的「看过」记录。 */
    val records: StateFlow<Map<String, SeenRecord>> = store.records.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _reconnects = MutableStateFlow<Map<String, Reconnect>>(emptyMap())

    /** 刚重新连上、还没消失的重连提示。 */
    val reconnects: StateFlow<Map<String, Reconnect>> = _reconnects.asStateFlow()

    /** 只在 [onChange] 里读写（同一个协程里串行）。 */
    private val online = HashSet<String>()

    fun start() {
        scope.launch { combine(states, visible, ::Pair).collect { (s, v) -> onChange(s, v) } }
        scope.launch {
            while (true) {
                delay(heartbeatMillis)
                val t = now()
                states.value
                    .filterValues { it.status is LinkStatus.Online }
                    .keys
                    .forEach { hostId -> store.update(hostId) { it?.copy(lastOnlineAt = t) } }
            }
        }
    }

    fun dismiss(hostId: String) {
        _reconnects.update { it - hostId }
    }

    internal suspend fun onChange(
        states: Map<String, HostLiveState>,
        visible: VisibleSession?,
    ) {
        for ((hostId, state) in states) {
            val isOnline = state.status is LinkStatus.Online
            if (isOnline && online.add(hostId)) cameOnline(hostId, state)
            if (!isOnline && online.remove(hostId)) {
                val t = now()
                store.update(hostId) { it?.copy(lastOnlineAt = t) }
            }
        }
        online.retainAll(states.keys)
        _reconnects.update { map -> map.filterKeys { it in states } }
        if (visible != null) {
            dismiss(visible.hostId)
            markSeen(visible, states[visible.hostId])
        }
    }

    private suspend fun cameOnline(
        hostId: String,
        state: HostLiveState,
    ) {
        val t = now()
        var reconnect: Reconnect? = null
        store.update(hostId) { record ->
            if (record == null) {
                SeenRecord(since = t + state.clockOffsetMillis, lastOnlineAt = t)
            } else {
                val last = record.lastOnlineAt
                reconnect = if (last != null && t - last >= thresholdMillis) Reconnect(last, t - last) else null
                // 电脑上已经删掉的会话不再记
                record.copy(lastOnlineAt = t, sessions = record.sessions.filterKeys { it in state.sessions })
            }
        }
        reconnect?.let { r -> _reconnects.update { it + (hostId to r) } }
    }

    private suspend fun markSeen(
        visible: VisibleSession,
        state: HostLiveState?,
    ) {
        val ended =
            state
                ?.sessions
                ?.get(visible.sessionId)
                ?.lastTurn
                ?.endedAt ?: return
        val known = records.value[visible.hostId]?.sessions?.get(visible.sessionId)
        if (known != null && known >= ended) return
        store.update(visible.hostId) { record ->
            when {
                record == null -> null
                (record.sessions[visible.sessionId] ?: Long.MIN_VALUE) >= ended -> record
                else -> record.copy(sessions = record.sessions + (visible.sessionId to ended))
            }
        }
    }

    companion object {
        /** 离线 5 分钟以上重新连上才提示（用户确认）。 */
        const val RECONNECT_THRESHOLD_MILLIS = 5 * 60_000L

        const val HEARTBEAT_MILLIS = 60_000L
    }
}
