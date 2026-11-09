package io.github.qianyeeew.dshremote.service

import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.AttentionRef
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.LiveEvent
import io.github.qianyeeew.dshremote.data.live.OfflineReason
import io.github.qianyeeew.dshremote.data.live.ResolvedBy
import io.github.qianyeeew.dshremote.service.NotificationAction.CancelAttention
import io.github.qianyeeew.dshremote.service.NotificationAction.CancelTaskDone
import io.github.qianyeeew.dshremote.service.NotificationAction.PostAttention
import io.github.qianyeeew.dshremote.service.NotificationAction.PostTaskDone
import org.junit.Assert.assertEquals
import org.junit.Test

/** 通知规则（m2-android.md 3.6）：只通知一次、重连后不重复、撤回、正在看的会话不通知。 */
class NotificationRulesTest {
    private val rules = NotificationRules()
    private val online = LinkStatus.Online(HostAddress("192.168.1.5", 3190))
    private val a1 = attention("a1", "s1", 1)
    private val a2 = attention("a2", "s2", 2)

    @Test
    fun newAttentionIsNotifiedOnceEvenAfterReconnect() {
        assertEquals(listOf(PostAttention("h1", a1)), rules.onStates(states(a1), null))
        // 同样的状态再来一次、断线、重连后 hello 里还在：都不再通知
        assertEquals(emptyList<NotificationAction>(), rules.onStates(states(a1), null))
        assertEquals(emptyList<NotificationAction>(), rules.onStates(states(a1, status = LinkStatus.Connecting), null))
        assertEquals(listOf(PostAttention("h1", a2)), rules.onStates(states(a1, a2), null))
    }

    @Test
    fun itemsThatAppearedWhileDisconnectedAreNotifiedFromHello() {
        rules.onStates(states(status = LinkStatus.Offline(OfflineReason.DISCONNECTED, 1_000)), null)
        assertEquals(listOf(PostAttention("h1", a1), PostAttention("h1", a2)), rules.onStates(states(a2, a1), null))
    }

    @Test
    fun resolvedEventWithdrawsTheNotification() {
        rules.onStates(states(a1), null)
        assertEquals(
            listOf(CancelAttention("h1", "a1")),
            rules.onEvent(LiveEvent.AttentionResolved("h1", "a1", "s1", ResolvedBy.OTHER), null),
        )
        // 已经撤回了，再收到也不重复撤回
        assertEquals(emptyList<NotificationAction>(), rules.onEvent(LiveEvent.AttentionResolved("h1", "a1", "s1", ResolvedBy.OTHER), null))
        // 之后状态里也没有了，不会再通知
        assertEquals(emptyList<NotificationAction>(), rules.onStates(states(), null))
    }

    @Test
    fun goneFromOnlineStateIsWithdrawnButNotWhileDisconnected() {
        rules.onStates(states(a1, a2), null)
        // 断线期间的状态可能过时：不撤回
        assertEquals(emptyList<NotificationAction>(), rules.onStates(states(a2, status = LinkStatus.Connecting), null))
        // 重连后的 hello 里没有 a1：撤回
        assertEquals(listOf(CancelAttention("h1", "a1")), rules.onStates(states(a2), null))
    }

    @Test
    fun removedHostWithdrawsEverything() {
        rules.onStates(states(a1), null)
        rules.onEvent(LiveEvent.SessionIdle("h1", "s3", "标题"), null)
        assertEquals(listOf(CancelAttention("h1", "a1"), CancelTaskDone("h1", "s3")), rules.onStates(emptyMap(), null))
    }

    @Test
    fun visibleSessionIsNotNotifiedAndOpeningASessionWithdrawsItsNotifications() {
        val visible = VisibleSession("h1", "s1")
        // 正在看 s1：s1 的事项不通知，s2 的照常
        assertEquals(listOf(PostAttention("h1", a2)), rules.onStates(states(a1, a2), visible))
        // 离开后也不补发（已经在卡片上看到了）
        assertEquals(emptyList<NotificationAction>(), rules.onStates(states(a1, a2), null))

        // 打开 s2：撤回 s2 的事项通知
        assertEquals(listOf(CancelAttention("h1", "a2")), rules.onVisible(VisibleSession("h1", "s2"), states(a1, a2)))
    }

    @Test
    fun subagentAttentionListedInTheVisibleSessionCountsAsVisible() {
        val sub = attention("a3", "sub-1", 3)
        val summary =
            SessionSummary(
                "s1",
                status = SessionStatus.WAITING,
                attention = listOf(AttentionRef("a3", AttentionKind.APPROVAL)),
                updatedAt = 0,
            )
        val state = mapOf("h1" to HostLiveState(online, mapOf("s1" to summary), mapOf("a3" to sub)))
        assertEquals(emptyList<NotificationAction>(), rules.onStates(state, VisibleSession("h1", "s1")))
    }

    @Test
    fun taskDoneIsNotifiedUnlessWatchingAndWithdrawnWhenOpened() {
        assertEquals(listOf(PostTaskDone("h1", "s1", "修复登录")), rules.onEvent(LiveEvent.SessionIdle("h1", "s1", "修复登录"), null))
        assertEquals(emptyList<NotificationAction>(), rules.onEvent(LiveEvent.SessionIdle("h1", "s2", null), VisibleSession("h1", "s2")))
        assertEquals(listOf(CancelTaskDone("h1", "s1")), rules.onVisible(VisibleSession("h1", "s1"), emptyMap()))
        assertEquals(emptyList<NotificationAction>(), rules.onVisible(VisibleSession("h1", "s1"), emptyMap()))
        assertEquals(emptyList<NotificationAction>(), rules.onVisible(null, emptyMap()))
    }

    @Test
    fun hostsAreIndependent() {
        val both = states(a1) + ("h2" to HostLiveState(online, attention = mapOf("a1" to a1)))
        assertEquals(listOf(PostAttention("h1", a1), PostAttention("h2", a1)), rules.onStates(both, null))
        assertEquals(
            listOf(CancelAttention("h2", "a1")),
            rules.onEvent(LiveEvent.AttentionResolved("h2", "a1", "s1", ResolvedBy.OTHER), null),
        )
    }

    private fun states(
        vararg attention: Attention,
        status: LinkStatus = online,
    ) = mapOf("h1" to HostLiveState(status, attention = attention.associateBy { it.attentionId }))

    private fun attention(
        id: String,
        sessionId: String,
        createdAt: Long,
    ) = Attention(id, sessionId, AttentionKind.APPROVAL, approval = Approval("bash"), createdAt = createdAt)
}
