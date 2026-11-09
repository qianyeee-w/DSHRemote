package io.github.qianyeeew.dshremote.service

import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.LiveEvent
import io.github.qianyeeew.dshremote.data.live.RecentIds

/** 用户正在看的会话（会话详情页可见时）。 */
data class VisibleSession(
    val hostId: String,
    val sessionId: String,
)

/** 要对通知做的事。 */
sealed interface NotificationAction {
    data class PostAttention(
        val hostId: String,
        val attention: Attention,
    ) : NotificationAction

    data class CancelAttention(
        val hostId: String,
        val attentionId: String,
    ) : NotificationAction

    data class PostTaskDone(
        val hostId: String,
        val sessionId: String,
        val title: String?,
    ) : NotificationAction

    data class CancelTaskDone(
        val hostId: String,
        val sessionId: String,
    ) : NotificationAction
}

/**
 * 通知规则（m2-android.md 3.6），纯逻辑，不碰 Android 的通知 API。调用方保证串行调用。
 *
 * 「需要你处理」：
 * - 按实时状态决定：状态里出现了没通知过的事项就通知。这样断线期间出现、重连后才在 `hello` 里看到的事项也会通知。
 * - 同一个事项只通知一次：重连后 `hello` 里重复出现、用户划掉了通知，都不再通知。
 * - 撤回：收到 `attention.resolved`，或者在线状态里已经没有这个事项（`hello` 整体覆盖），或者主机被删除。
 *   断线期间状态可能过时，不据此撤回。
 * - 用户正在看这个事项所在的会话时不通知（卡片就在眼前），打开会话时撤回它的通知。
 *
 * 「任务完成」：收到 `session.idle` 时通知（插件已经做了 8 秒防抖），用户正在看这个会话时不通知；打开这个会话时撤回。
 */
class NotificationRules {
    private val seen = RecentIds(SEEN_CAPACITY)
    private val activeAttention = LinkedHashSet<Key>()
    private val activeTaskDone = LinkedHashSet<Key>()

    private data class Key(
        val hostId: String,
        val id: String,
    )

    fun onStates(
        states: Map<String, HostLiveState>,
        visible: VisibleSession?,
    ): List<NotificationAction> {
        val actions = mutableListOf<NotificationAction>()
        for ((hostId, state) in states) {
            for (attention in state.attention.values.sortedBy { it.createdAt }) {
                val key = Key(hostId, attention.attentionId)
                if (!seen.add("$hostId/${attention.attentionId}")) continue
                if (covers(visible, hostId, state, attention)) continue
                activeAttention += key
                actions += NotificationAction.PostAttention(hostId, attention)
            }
            if (state.status is LinkStatus.Online) {
                actions += cancelAttention { it.hostId == hostId && it.id !in state.attention }
            }
        }
        actions += cancelAttention { it.hostId !in states }
        actions += cancelTaskDone { it.hostId !in states }
        return actions
    }

    fun onEvent(
        event: LiveEvent,
        visible: VisibleSession?,
    ): List<NotificationAction> =
        when (event) {
            is LiveEvent.AttentionResolved -> cancelAttention { it == Key(event.hostId, event.attentionId) }
            is LiveEvent.SessionIdle -> {
                if (visible == VisibleSession(event.hostId, event.sessionId)) {
                    emptyList()
                } else {
                    activeTaskDone += Key(event.hostId, event.sessionId)
                    listOf(NotificationAction.PostTaskDone(event.hostId, event.sessionId, event.title))
                }
            }
            // 新的事项由 onStates 处理（实时连接先更新状态，再发事件）
            is LiveEvent.AttentionRequested -> emptyList()
        }

    /** 用户打开了一个会话（或者离开了会话详情页，[visible] 为 null）。 */
    fun onVisible(
        visible: VisibleSession?,
        states: Map<String, HostLiveState>,
    ): List<NotificationAction> {
        if (visible == null) return emptyList()
        val state = states[visible.hostId]
        return cancelTaskDone { it == Key(visible.hostId, visible.sessionId) } +
            cancelAttention { key ->
                val attention = state?.attention?.get(key.id)
                key.hostId == visible.hostId && attention != null && covers(visible, key.hostId, state, attention)
            }
    }

    /** 事项属于用户正在看的会话：`sessionId` 相同，或者列在这个会话摘要的 `attention` 中（子代理的事项）。 */
    private fun covers(
        visible: VisibleSession?,
        hostId: String,
        state: HostLiveState,
        attention: Attention,
    ): Boolean {
        if (visible == null || visible.hostId != hostId) return false
        if (attention.sessionId == visible.sessionId) return true
        return state.sessions[visible.sessionId]?.attention?.any { it.attentionId == attention.attentionId } == true
    }

    private fun cancelAttention(predicate: (Key) -> Boolean): List<NotificationAction> {
        val gone = activeAttention.filter(predicate)
        activeAttention -= gone.toSet()
        return gone.map { NotificationAction.CancelAttention(it.hostId, it.id) }
    }

    private fun cancelTaskDone(predicate: (Key) -> Boolean): List<NotificationAction> {
        val gone = activeTaskDone.filter(predicate)
        activeTaskDone -= gone.toSet()
        return gone.map { NotificationAction.CancelTaskDone(it.hostId, it.id) }
    }

    private companion object {
        const val SEEN_CAPACITY = 4_096
    }
}
