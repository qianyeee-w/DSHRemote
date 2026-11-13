package io.github.qianyeeew.dshremote.data.chat

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.TextPart

enum class OutgoingStatus {
    SENDING,

    /** 插件已经接受。排队（queue）的消息可能要等当前任务结束才写进会话日志。 */
    SENT,

    /** 确定没送到（连不上、插件明确拒绝），可以重试（沿用同一个 requestId，插件据此去重）。 */
    FAILED,

    /**
     * 请求发出去了但没收到回应（超时、半路断开）：插件可能已经收到（落地方案 B3）。
     * **不自动重发**：插件只在 10 分钟内按 requestId 去重，重启后记录清空，所以只能由用户点「查看」或「重发」。
     */
    UNCERTAIN,
}

/**
 * 发消息失败时，这条消息算什么状态（落地方案 B3，用户确认的规则）：请求已经写出、但没拿到能看懂的回应的，
 * 算不确定；连接都没建立起来、或者插件明确回了错误的，算失败。
 */
fun deliveryStatus(error: Exception): OutgoingStatus =
    when (error) {
        is ApiException.Network -> if (error.maybeDelivered) OutgoingStatus.UNCERTAIN else OutgoingStatus.FAILED
        // 插件回了 2xx 但内容看不懂：多半已经受理
        is ApiException.BadResponse -> OutgoingStatus.UNCERTAIN
        // 插件明确回了错误、握手时指纹就不对、或者根本没有连接
        else -> OutgoingStatus.FAILED
    }

data class LocalMessage(
    val outgoing: OutgoingMessage,
    val status: OutgoingStatus,
)

/**
 * 发出去、但还没在会话日志里出现的消息（m2-android.md 3.4「发送消息」）。界面上显示在最后，
 * 状态为「发送中 / 已发送 / 失败，点此重试 / 不确定是否送达」。
 *
 * 插件推送回来的用户消息 id 和 requestId 无关，所以按文字匹配：时间线里每出现一条**之前没见过的**用户消息，
 * 就去掉第一条文字相同的本地消息（同样的文字发了两次时按先后顺序对应）。失败和不确定的消息也参与匹配：
 * 可能请求其实已经到达，只是响应丢了。
 */
data class Outbox(
    val items: List<LocalMessage> = emptyList(),
    /** 已经见过的用户消息 id。为 null 表示还没读到第一页历史。 */
    private val seenUserIds: Set<String>? = null,
) {
    fun add(message: OutgoingMessage): Outbox = copy(items = items + LocalMessage(message, OutgoingStatus.SENDING))

    fun mark(
        requestId: String,
        status: OutgoingStatus,
    ): Outbox = copy(items = items.map { if (it.outgoing.requestId == requestId) it.copy(status = status) else it })

    fun find(requestId: String): LocalMessage? = items.firstOrNull { it.outgoing.requestId == requestId }

    /** 时间线变化后调用。第一次读到历史时只记下已有的用户消息，不做匹配。 */
    fun reconcile(timeline: ChatTimeline): Outbox {
        if (!timeline.loaded) return this
        val users = timeline.messages.filter { it.role == MessageRole.USER }
        if (seenUserIds == null) return copy(seenUserIds = users.mapTo(HashSet()) { it.id })
        val fresh = users.filter { it.id !in seenUserIds }
        if (fresh.isEmpty()) return this
        var remaining = items
        for (message in fresh) {
            val text =
                message.parts
                    .filterIsInstance<TextPart>()
                    .joinToString("") { it.text }
                    .trim()
            val index = remaining.indexOfFirst { it.outgoing.text.trim() == text }
            if (index >= 0) remaining = remaining.filterIndexed { i, _ -> i != index }
        }
        return copy(items = remaining, seenUserIds = seenUserIds + fresh.map { it.id })
    }

    /** [toString] 不输出消息内容。 */
    override fun toString(): String = "Outbox(items=${items.size})"
}
