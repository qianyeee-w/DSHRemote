package io.github.qianyeeew.dshremote.data.chat

import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.TextPart

enum class OutgoingStatus {
    SENDING,

    /** 插件已经接受。排队（queue）的消息可能要等当前任务结束才写进会话日志。 */
    SENT,

    /** 发送失败，可以重试（沿用同一个 requestId，插件据此去重）。 */
    FAILED,
}

data class LocalMessage(
    val outgoing: OutgoingMessage,
    val status: OutgoingStatus,
)

/**
 * 发出去、但还没在会话日志里出现的消息（m2-android.md 3.4「发送消息」）。界面上显示在最后，
 * 状态为「发送中 / 已发送 / 失败，点此重试」。
 *
 * 插件推送回来的用户消息 id 和 requestId 无关，所以按文字匹配：时间线里每出现一条**之前没见过的**用户消息，
 * 就去掉第一条文字相同的本地消息（同样的文字发了两次时按先后顺序对应）。失败的消息也参与匹配：
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
