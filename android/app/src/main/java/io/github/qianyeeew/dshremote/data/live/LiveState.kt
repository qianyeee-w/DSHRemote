package io.github.qianyeeew.dshremote.data.live

import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.SessionPlan
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.Turn
import io.github.qianyeeew.dshremote.data.api.TurnResult
import io.github.qianyeeew.dshremote.data.host.HostAddress
import kotlin.random.Random

/**
 * 一台主机的连接状态（m2-android.md 3.3「在线状态」）。
 *
 * [isTerminal] 为 true 的状态不会自动重连，要等用户操作（重新配对、修改地址、手动重试）。
 */
sealed interface LinkStatus {
    val isTerminal: Boolean get() = false

    /** 没有任何界面或前台服务需要连接（[ConnectionManager.hold] 的计数为 0）。 */
    data object Stopped : LinkStatus

    data object Connecting : LinkStatus

    /** 收到了 `hello`，会话和待处理事项是最新的。 */
    data class Online(
        val address: HostAddress,
    ) : LinkStatus

    /** 连不上或连接断开了，[retryInMillis] 后自动重试（网络变化时立即重试）。 */
    data class Offline(
        val reason: OfflineReason,
        val retryInMillis: Long,
    ) : LinkStatus

    /** 插件报告 DSH 版本不兼容。DSH 可能会被升级，所以按最长的退避间隔继续探测。 */
    data class DshIncompatible(
        val dshVersion: String?,
    ) : LinkStatus

    /** token 被撤销或无效，或者本机保存的凭据无法解密。需要重新配对，不再重连。 */
    data class NeedsPairing(
        val reason: PairingReason,
    ) : LinkStatus {
        override val isTerminal: Boolean get() = true
    }

    /**
     * 服务器证书的指纹和配对时保存的不一致。直接拒绝，不再重连，也不提供「仍然继续」（红线第 2 条）。
     * 用户要么重新配对，要么检查地址是否指向了别的电脑。
     */
    data object FingerprintMismatch : LinkStatus {
        override val isTerminal: Boolean get() = true
    }

    /** 插件的协议版本这个 App 不认识（protocol.md 第 8 节），需要升级 App。 */
    data class AppTooOld(
        val protocol: Int,
    ) : LinkStatus {
        override val isTerminal: Boolean get() = true
    }
}

enum class OfflineReason {
    /** 所有地址都连不上（电脑关机、不在同一网络、端口不通等）。 */
    UNREACHABLE,

    /** 插件在运行，但 DSH 暂时不可用（例如 DSH 正在重启）。 */
    DSH_UNAVAILABLE,

    /** 这台设备在电脑上的连接数达到上限（`429`），通常是切换网络后旧连接还没被清理。 */
    TOO_MANY_CONNECTIONS,

    /** 已经建立的连接断开了。 */
    DISCONNECTED,

    /** 插件的响应不符合协议（App 或插件的 bug，或者连到了别的服务）。 */
    PROTOCOL_ERROR,
}

enum class PairingReason {
    /** 握手返回 `401` 或连接以 `4401` 关闭：设备在电脑上被撤销了。 */
    TOKEN_REVOKED,

    /** 本机保存的 token 和指纹无法解密（见 HostRepository）。 */
    CREDENTIALS_LOST,
}

/**
 * 一台主机的实时状态。[sessions] 和 [attention] 在收到 `hello` 时被整体覆盖，之后按事件增量更新；
 * 断开后保留最后一次的内容（[status] 不是 [LinkStatus.Online] 时可能已经过时）。
 */
data class HostLiveState(
    val status: LinkStatus = LinkStatus.Stopped,
    val sessions: Map<String, SessionSummary> = emptyMap(),
    val attention: Map<String, Attention> = emptyMap(),
    /**
     * 电脑的时钟比手机快多少毫秒（连接时由 `/v1/info` 的 `Date` 头估算）。会话的 `updatedAt` 等时间来自电脑，
     * 显示「多久以前」时用它换算，见 [io.github.qianyeeew.dshremote.ui.common.serverNow]。
     */
    val clockOffsetMillis: Long = 0,
)

/** 实时流中需要通知或界面处理的事件（不包括已经反映在 [HostLiveState] 中的状态变化）。 */
sealed interface LiveEvent {
    val hostId: String

    data class SessionIdle(
        override val hostId: String,
        val sessionId: String,
        val title: String?,
        /** 刚结束那一轮的结果；旧插件没有。 */
        val result: TurnResult? = null,
    ) : LiveEvent

    data class AttentionRequested(
        override val hostId: String,
        val attention: Attention,
    ) : LiveEvent

    data class AttentionResolved(
        override val hostId: String,
        val attentionId: String,
        val sessionId: String,
        val by: ResolvedBy,
    ) : LiveEvent
}

/** 一个已订阅会话的消息事件（[HostConnection.subscribe]）。 */
sealed interface SessionEvent {
    val sessionId: String

    /**
     * 订阅在一条连接上生效了（首次订阅，或者重连后重新订阅）。收到后用 `/v1/sessions/{id}/messages`
     * 重新读取最新一页历史来补齐断线期间的消息（protocol.md 6.3）。
     */
    data class Resync(
        override val sessionId: String,
    ) : SessionEvent

    data class Upsert(
        override val sessionId: String,
        val rev: Long,
        val message: Message,
        val replaces: String?,
    ) : SessionEvent

    data class Removed(
        override val sessionId: String,
        val messageId: String,
    ) : SessionEvent

    /** 一轮开始或结束（protocol.md 6.1 turn.updated）。 */
    data class TurnUpdated(
        override val sessionId: String,
        val turn: Turn,
    ) : SessionEvent

    /** 目标或任务清单变了（protocol.md 6.1 plan.updated）。 */
    data class PlanUpdated(
        override val sessionId: String,
        val plan: SessionPlan,
    ) : SessionEvent
}

/**
 * 重连的退避间隔：第 n 次失败后等待 `min(max, initial × 2ⁿ)`，再乘以 [0.5, 1) 的随机系数，
 * 避免多台手机在电脑重启后同时重连。
 */
class Backoff(
    private val initialMillis: Long = 1_000,
    val maxMillis: Long = 30_000,
    private val random: Random = Random.Default,
) {
    private var failures = 0

    fun next(): Long {
        val base = (initialMillis shl failures.coerceAtMost(MAX_SHIFT)).coerceAtMost(maxMillis)
        failures++
        return (base * (0.5 + random.nextDouble() / 2)).toLong().coerceAtLeast(1)
    }

    fun reset() {
        failures = 0
    }

    private companion object {
        const val MAX_SHIFT = 20
    }
}

/** 最近见过的 eventId，用于去重。只保留最近 [capacity] 个，避免长时间运行时无限增长。 */
internal class RecentIds(
    private val capacity: Int = 1024,
) {
    private val ids = LinkedHashSet<String>()

    /** 第一次见到时返回 true。 */
    fun add(id: String): Boolean {
        if (!ids.add(id)) return false
        if (ids.size > capacity) ids.remove(ids.first())
        return true
    }
}

/** 这台主机现在不在线，无法调用接口（[ConnectionManager.api] 返回 null）。 */
class OfflineException : Exception("电脑不在线")
