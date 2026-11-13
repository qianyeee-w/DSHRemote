package io.github.qianyeeew.dshremote.data.chat

import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.MessagePage
import io.github.qianyeeew.dshremote.data.api.Turn
import io.github.qianyeeew.dshremote.data.live.SessionEvent

/**
 * 一个会话的消息时间线：历史分页加上实时推送（protocol.md 4「历史分页」、6.4）。不可变，每个操作返回新的时间线。
 *
 * 合并规则：
 * - 历史从最新一页开始，[withOlder] 在前面接上更早的一页；按消息 id 去重，是否还有更早的消息只看 [hasMore]。
 * - `message.upsert`：同一个 id 只接受更大的 `rev`，整条替换；新的消息接在最后。带 `replaces` 时删掉对应的草稿。
 * - `message.removed`：删掉这条消息（目前只用于草稿）。
 * - 草稿（`draft: true`）单独保存，始终显示在正式消息之后。
 * - 重新订阅（[SessionEvent.Resync]，包括重连）时：清掉草稿和 rev 记录（插件重启后 rev 从 1 重新开始，
 *   并且会立即推送当前的草稿），然后 [startReload] 到 [withLatest] 之间收到的推送先暂存，读到最新一页后再按顺序重放，
 *   免得较新的推送被较旧的历史页覆盖。
 */
data class ChatTimeline(
    /** 正式消息，按会话日志的顺序。 */
    val messages: List<Message> = emptyList(),
    /** 草稿，按出现的顺序。 */
    val drafts: List<Message> = emptyList(),
    /** 读过的最早一页之前是否还有消息。 */
    val hasMore: Boolean = false,
    /** 取更早一页用的 cursor（[hasMore] 为 false 时为 null）。 */
    val cursor: String? = null,
    /** 是否已经读到过最新一页。 */
    val loaded: Boolean = false,
    /** 轮次（protocol.md 5.4）：各页和推送里拿到的同一轮合在一起。旧插件时为空。 */
    val turns: Map<Int, Turn> = emptyMap(),
    /** 替换了草稿的正式消息 id → 草稿 id，见 [keyOf]。 */
    private val draftKeys: Map<String, String> = emptyMap(),
    /** 每条消息最后接受的 rev（只在一次订阅内有意义）。 */
    private val revs: Map<String, Long> = emptyMap(),
    /** 不为 null 表示正在重新读取最新一页，期间的推送暂存在这里。 */
    private val pending: List<SessionEvent>? = null,
) {
    val reloading: Boolean get() = pending != null

    /**
     * 界面列表中这条消息的 key。替换了草稿的正式消息沿用草稿的 key，界面上就是同一个列表项：
     * 已经渲染好的 Markdown 不会因为换了 id 而重新开始解析（否则流式输出结束时会闪一下）。
     */
    fun keyOf(message: Message): String = draftKeys[message.id] ?: message.id

    /** 收到 [SessionEvent.Resync]：开始重新读取最新一页。 */
    fun startReload(): ChatTimeline = copy(drafts = emptyList(), revs = emptyMap(), pending = emptyList())

    /** 重新读取失败：不再暂存，已暂存的推送照常应用（之后可以再试一次 [startReload]）。 */
    fun reloadFailed(): ChatTimeline = replay(copy(pending = null), pending.orEmpty())

    /**
     * 读到了最新一页。页中的第一条消息已经在时间线里时，保留它之前已经读过的更早的消息（以及原来的 cursor），
     * 否则（断开期间新消息太多，或者第一次读取）整体换成这一页。之后重放暂存的推送。
     */
    fun withLatest(page: MessagePage): ChatTimeline {
        val first = page.messages.firstOrNull()
        val anchor = first?.let { msg -> messages.indexOfFirst { it.id == msg.id } } ?: -1
        val base =
            if (loaded && anchor >= 0) {
                val pageIds = page.messages.mapTo(HashSet()) { it.id }
                copy(messages = messages.take(anchor).filterNot { it.id in pageIds } + page.messages, turns = mergeTurns(turns, page.turns))
            } else {
                copy(messages = page.messages, hasMore = page.hasMore, cursor = page.cursor, turns = mergeTurns(turns, page.turns))
            }
        return replay(base.copy(loaded = true, pending = null), pending.orEmpty())
    }

    /** 在前面接上更早的一页。 */
    fun withOlder(page: MessagePage): ChatTimeline {
        val known = messages.mapTo(HashSet()) { it.id }
        return copy(
            messages = page.messages.filterNot { it.id in known } + messages,
            hasMore = page.hasMore,
            cursor = page.cursor,
            turns = mergeTurns(turns, page.turns),
        )
    }

    /** 应用一条推送。正在重新读取时先暂存。[SessionEvent.Resync] 由调用方处理（[startReload]），这里忽略。 */
    fun apply(event: SessionEvent): ChatTimeline {
        if (pending != null && event !is SessionEvent.Resync) return copy(pending = pending + event)
        return when (event) {
            is SessionEvent.Resync -> this
            is SessionEvent.Upsert -> upsert(event)
            is SessionEvent.Removed -> remove(event.messageId)
            is SessionEvent.TurnUpdated -> copy(turns = mergeTurns(turns, listOf(event.turn)))
            // 计划不在时间线里，由会话页的视图模型单独保存
            is SessionEvent.PlanUpdated -> this
        }
    }

    private fun replay(
        timeline: ChatTimeline,
        events: List<SessionEvent>,
    ): ChatTimeline = events.fold(timeline) { acc, event -> acc.apply(event) }

    private fun upsert(event: SessionEvent.Upsert): ChatTimeline {
        val message = event.message
        val known = revs[message.id]
        if (known != null && event.rev <= known) return this
        var next = copy(revs = revs + (message.id to event.rev))
        if (message.draft) {
            next = next.copy(drafts = next.drafts.replaceOrAppend(message))
        } else {
            next = next.copy(messages = next.messages.replaceOrAppend(message))
            event.replaces?.let { draftId ->
                next = next.dropDraft(draftId)
                if (message.id !in next.draftKeys) next = next.copy(draftKeys = next.draftKeys + (message.id to draftId))
            }
        }
        return next
    }

    private fun remove(messageId: String): ChatTimeline =
        dropDraft(messageId).let { it.copy(messages = it.messages.filterNot { msg -> msg.id == messageId }) }

    /** 草稿被替换或删除后，插件也删掉了它的 rev 记录，同一个 id 以后可能从 rev 1 重新开始。 */
    private fun dropDraft(id: String): ChatTimeline = copy(drafts = drafts.filterNot { it.id == id }, revs = revs - id)

    /** [toString] 不输出消息内容（可能包含用户的隐私）。 */
    override fun toString(): String =
        "ChatTimeline(messages=${messages.size}, drafts=${drafts.size}, hasMore=$hasMore, loaded=$loaded, reloading=$reloading)"
}

private fun List<Message>.replaceOrAppend(message: Message): List<Message> {
    val index = indexOfFirst { it.id == message.id }
    return if (index < 0) this + message else toMutableList().also { it[index] = message }
}

private fun mergeTurns(
    current: Map<Int, Turn>,
    incoming: List<Turn>,
): Map<Int, Turn> {
    if (incoming.isEmpty()) return current
    val merged = current.toMutableMap()
    for (turn in incoming) merged[turn.turn] = merged[turn.turn]?.merge(turn) ?: turn
    return merged
}
