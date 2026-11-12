package io.github.qianyeeew.dshremote.ui.attention

import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 有待处理事项时，会话页底部的输入框怎么配合（设计规格 6.3、7.4、7.5）。 */
sealed interface ComposerAttention {
    /** 没有事项，照常发消息。 */
    data object None : ComposerAttention

    /**
     * 有审批在等。DSH 停在这里等审批，这时发的消息不会改变这次审批（用户确认过的行为），所以输入框不可用，
     * 只保留停止。
     */
    data class Approval(
        val attention: Attention,
    ) : ComposerAttention

    /** 只有一个问题的提问：输入框里写的就是自己的答案，作为 `custom` 提交。 */
    data class Answer(
        val attention: Attention,
        val questionId: String,
    ) : ComposerAttention

    /** 有多个问题的提问：每题下面有自己的输入框，底部输入框暂时不可用，免得不知道答的是哪一题。 */
    data class AnswerAbove(
        val attention: Attention,
    ) : ComposerAttention
}

/** 按最早的那个事项决定（面板也按这个顺序排）。 */
fun composerAttention(pending: List<Attention>): ComposerAttention {
    val first = pending.firstOrNull() ?: return ComposerAttention.None
    return when {
        first.kind == AttentionKind.APPROVAL -> ComposerAttention.Approval(first)
        first.kind == AttentionKind.QUESTION -> {
            val questions = first.question?.questions.orEmpty()
            when (questions.size) {
                0 -> ComposerAttention.None
                1 -> ComposerAttention.Answer(first, questions.single().id)
                else -> ComposerAttention.AnswerAbove(first)
            }
        }
        else -> ComposerAttention.None
    }
}

/** 审批面板第一行写的是什么事（设计规格 7.4）。 */
enum class ApprovalAction { RUN, EDIT, OTHER }

fun approvalAction(toolName: String): ApprovalAction =
    when (toolName) {
        "bash", "pwsh" -> ApprovalAction.RUN
        "edit", "write" -> ApprovalAction.EDIT
        else -> ApprovalAction.OTHER
    }

/**
 * 审批面板第二行：命令或文件。插件没能找到对应的工具调用（`input` 为 null，协议 5.3）或者参数里没有这一项时为 null，
 * 面板改为显示审批的 `reason`。
 */
fun approvalTarget(approval: Approval): String? {
    val input = approval.input ?: return null
    val key =
        when (approvalAction(approval.toolName)) {
            ApprovalAction.RUN -> "command"
            ApprovalAction.EDIT -> "file_path"
            ApprovalAction.OTHER -> return null
        }
    return (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
}

/**
 * 从通知点进一个事项时打开哪个会话：事项自己的会话，或者在会话摘要里列出了它的会话（子代理发起的事项挂在父会话上）。
 * 都不在会话列表里（例如还没连上、事项已经结束）时为 null，改用独立的处理页。
 */
fun attentionSession(
    host: HostLiveState?,
    attentionId: String,
): String? {
    if (host == null) return null
    val attention = host.attention[attentionId]
    if (attention != null && host.sessions.containsKey(attention.sessionId)) return attention.sessionId
    return host.sessions.values
        .firstOrNull { session -> session.attention.any { it.attentionId == attentionId } }
        ?.sessionId
}
