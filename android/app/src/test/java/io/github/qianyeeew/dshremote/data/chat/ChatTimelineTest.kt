package io.github.qianyeeew.dshremote.data.chat

import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.MessagePage
import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.live.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 消息合并规则（m2-android.md 3.4、protocol.md 6.4）。 */
class ChatTimelineTest {
    @Test
    fun latestPageThenOlderPagesWithoutDuplicates() {
        var t = ChatTimeline().withLatest(page(listOf(msg("m3"), msg("m4")), hasMore = true, cursor = "c1"))
        assertTrue(t.loaded)
        assertEquals(listOf("m3", "m4"), t.ids())
        assertEquals("c1", t.cursor)

        // 更早的一页和已有的重叠一条：去重
        t = t.withOlder(page(listOf(msg("m1"), msg("m2"), msg("m3")), hasMore = false, cursor = null))
        assertEquals(listOf("m1", "m2", "m3", "m4"), t.ids())
        assertFalse(t.hasMore)
        assertNull(t.cursor)
    }

    @Test
    fun emptyPageWithHasMoreStillAllowsPaging() {
        // 插件过滤掉内部消息后，一页可能是空的，但还有更早的消息
        val t = ChatTimeline().withLatest(page(emptyList(), hasMore = true, cursor = "c"))
        assertTrue(t.messages.isEmpty())
        assertTrue(t.hasMore)
        assertEquals("c", t.cursor)
    }

    @Test
    fun upsertAcceptsOnlyIncreasingRevAndReplacesWholeMessage() {
        var t = loaded(msg("m1", "旧"))
        t = t.apply(upsert(msg("m1", "工具运行中"), rev = 2))
        assertEquals("工具运行中", t.text("m1"))
        t = t.apply(upsert(msg("m1", "过时的"), rev = 1))
        t = t.apply(upsert(msg("m1", "重复的"), rev = 2))
        assertEquals("工具运行中", t.text("m1"))
        t = t.apply(upsert(msg("m1", "完成"), rev = 3))
        assertEquals("完成", t.text("m1"))
        assertEquals(listOf("m1"), t.ids())
    }

    @Test
    fun newMessagesAreAppendedAndDraftsStayLast() {
        var t = loaded(msg("m1"))
        t = t.apply(upsert(msg("draft:1", "正在输", draft = true), rev = 1))
        t = t.apply(upsert(msg("u2", role = MessageRole.USER), rev = 1))
        assertEquals(listOf("m1", "u2"), t.ids())
        assertEquals(listOf("draft:1"), t.drafts.map { it.id })

        t = t.apply(upsert(msg("draft:1", "正在输出", draft = true), rev = 2))
        assertEquals("正在输出", t.drafts.single().text())

        // 正式消息替换草稿
        t = t.apply(upsert(msg("a3", "正在输出完了"), rev = 1, replaces = "draft:1"))
        assertEquals(listOf("m1", "u2", "a3"), t.ids())
        assertTrue(t.drafts.isEmpty())
        // 界面上沿用草稿的列表项
        assertEquals("draft:1", t.keyOf(t.messages.last()))
        assertEquals("m1", t.keyOf(t.messages.first()))
    }

    @Test
    fun removedDraftDisappearsAndItsIdCanStartOver() {
        var t = loaded()
        t = t.apply(upsert(msg("draft:1", "a", draft = true), rev = 5))
        t = t.apply(SessionEvent.Removed(SESSION, "draft:1"))
        assertTrue(t.drafts.isEmpty())
        // 插件删掉草稿后也删掉了 rev 记录：同一个 id 从 rev 1 重新开始
        t = t.apply(upsert(msg("draft:1", "b", draft = true), rev = 1))
        assertEquals("b", t.drafts.single().text())
    }

    @Test
    fun replacedDraftIdCanStartOver() {
        var t = loaded()
        t = t.apply(upsert(msg("draft:1", "a", draft = true), rev = 3))
        t = t.apply(upsert(msg("m1", "a"), rev = 1, replaces = "draft:1"))
        t = t.apply(upsert(msg("draft:1", "下一轮", draft = true), rev = 1))
        assertEquals("下一轮", t.drafts.single().text())
    }

    @Test
    fun reloadBuffersPushesAndReplaysThemOverTheFetchedPage() {
        var t = loaded(msg("m1"), msg("m2", "旧"))
        t = t.apply(upsert(msg("draft:1", "x", draft = true), rev = 9))
        t = t.startReload()
        assertTrue(t.reloading)
        assertTrue("重新订阅时清掉草稿", t.drafts.isEmpty())

        // 读取期间收到的推送先暂存（rev 记录已清空，插件重启后 rev 从 1 开始）
        t = t.apply(upsert(msg("m2", "最新"), rev = 1))
        t = t.apply(upsert(msg("draft:1", "新草稿", draft = true), rev = 1))
        assertEquals("旧", t.text("m2"))

        // 读到的历史页比暂存的推送旧：重放后以推送为准
        t = t.withLatest(page(listOf(msg("m1"), msg("m2", "页里的"), msg("m3"))))
        assertFalse(t.reloading)
        assertEquals(listOf("m1", "m2", "m3"), t.ids())
        assertEquals("最新", t.text("m2"))
        assertEquals("新草稿", t.drafts.single().text())
    }

    @Test
    fun reloadKeepsOlderPagesWhenTheLatestPageOverlaps() {
        var t = ChatTimeline().withLatest(page(listOf(msg("m3"), msg("m4")), hasMore = true, cursor = "c-latest"))
        t = t.withOlder(page(listOf(msg("m1"), msg("m2")), hasMore = false, cursor = null))
        t = t.startReload().withLatest(page(listOf(msg("m4"), msg("m5")), hasMore = true, cursor = "c-new"))
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), t.ids())
        // 已经翻到底了，不能因为重新读取又「还有更多」
        assertFalse(t.hasMore)
    }

    @Test
    fun reloadReplacesEverythingWhenThereIsAGap() {
        var t = loaded(msg("m1"), msg("m2"))
        t = t.startReload().withLatest(page(listOf(msg("m8"), msg("m9")), hasMore = true, cursor = "c"))
        assertEquals(listOf("m8", "m9"), t.ids())
        assertTrue(t.hasMore)
        assertEquals("c", t.cursor)
    }

    @Test
    fun failedReloadAppliesBufferedPushes() {
        var t = loaded(msg("m1"))
        t = t.startReload().apply(upsert(msg("m2"), rev = 1)).reloadFailed()
        assertFalse(t.reloading)
        assertEquals(listOf("m1", "m2"), t.ids())
    }

    @Test
    fun toStringHasNoContent() {
        assertFalse(loaded(msg("m1", "秘密内容")).toString().contains("秘密内容"))
    }

    private fun loaded(vararg messages: Message) = ChatTimeline().withLatest(page(messages.toList()))

    private fun page(
        messages: List<Message>,
        hasMore: Boolean = false,
        cursor: String? = null,
    ) = MessagePage(messages, hasMore, cursor)

    private fun msg(
        id: String,
        text: String = id,
        role: MessageRole = MessageRole.ASSISTANT,
        draft: Boolean = false,
    ) = Message(id = id, role = role, parts = listOf(TextPart(text)), createdAt = 0, draft = draft)

    private fun upsert(
        message: Message,
        rev: Long,
        replaces: String? = null,
    ) = SessionEvent.Upsert(SESSION, rev, message, replaces)

    private fun ChatTimeline.ids() = messages.map { it.id }

    private fun ChatTimeline.text(id: String) = messages.single { it.id == id }.text()

    private fun Message.text() = parts.filterIsInstance<TextPart>().joinToString("") { it.text }

    private companion object {
        const val SESSION = "s1"
    }
}
