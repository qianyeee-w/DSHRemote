package io.github.qianyeeew.dshremote.ui.chat

import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.chat.OutgoingStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * 首页开始新任务时，会话已经创建、但后面的步骤没有完成，进入这个会话时要接着处理的事（落地方案 A5）。
 * 只在内存里，App 进程被杀后就没了（消息内容不落盘）。
 */
sealed interface ChatHandoff {
    /**
     * 第一条消息没发出去，或者不确定是否送到：在会话里显示成「发送失败」或「不确定是否送达」的气泡（[status]），
     * 「重试」「重发」沿用同一个 requestId（用户确认），插件据此去重，其实已经送到时不会发两次。
     */
    data class FailedFirstMessage(
        val message: OutgoingMessage,
        val status: OutgoingStatus = OutgoingStatus.FAILED,
    ) : ChatHandoff

    /** 模型或审批方式没有设置成功，消息还没发：文字放回输入框，并提示原因。 */
    data class NotSent(
        val text: String,
    ) : ChatHandoff {
        /** 不输出消息内容。 */
        override fun toString(): String = "NotSent(${text.length} 个字符)"
    }
}

/** 首页放进去、会话页取出来。每个会话只取一次。 */
class ChatHandoffs {
    private val pending = ConcurrentHashMap<Pair<String, String>, ChatHandoff>()

    fun put(
        hostId: String,
        sessionId: String,
        handoff: ChatHandoff,
    ) {
        pending[hostId to sessionId] = handoff
    }

    fun take(
        hostId: String,
        sessionId: String,
    ): ChatHandoff? = pending.remove(hostId to sessionId)
}
