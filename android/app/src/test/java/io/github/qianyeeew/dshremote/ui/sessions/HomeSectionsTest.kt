package io.github.qianyeeew.dshremote.ui.sessions

import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.AttentionRef
import io.github.qianyeeew.dshremote.data.api.LastTurn
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.TurnResult
import io.github.qianyeeew.dshremote.data.api.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSectionsTest {
    private fun session(
        id: String,
        status: SessionStatus = SessionStatus.IDLE,
        updatedAt: Long = 0,
        workspaceId: String? = null,
        cwd: String? = null,
        attention: List<AttentionRef> = emptyList(),
        lastTurn: LastTurn? = null,
        lastReply: String? = null,
    ) = SessionSummary(
        id,
        title = id,
        workspaceId = workspaceId,
        cwd = cwd,
        status = status,
        attention = attention,
        updatedAt = updatedAt,
        lastTurn = lastTurn,
        lastReply = lastReply,
    )

    private fun approval(
        id: String,
        session: String,
        createdAt: Long = 0,
    ) = Attention(id, session, AttentionKind.APPROVAL, approval = Approval("bash", "r"), createdAt = createdAt)

    private val workspaces = listOf(Workspace("w1", "DSHRemote", "/code/dshremote"), Workspace("w2", "工具脚本", "/code/tools"))

    @Test
    fun waitingFirstThenOrphansThenRunningAndTheRestInRecent() {
        val sessions =
            listOf(
                session("idle-old", updatedAt = 1),
                session("running", SessionStatus.RUNNING, updatedAt = 9),
                session("waiting-old", SessionStatus.WAITING, updatedAt = 2),
                session("listed", SessionStatus.RUNNING, updatedAt = 3, attention = listOf(AttentionRef("x"))),
                session("idle-new", updatedAt = 8),
            )
        val orphan = approval("o", "child")
        val sections = homeSections(sessions, listOf(orphan), workspaces, null) { null }
        assertEquals(
            listOf("waiting:listed", "waiting:waiting-old", "orphan:o", "running:running"),
            sections.active.map { it.key },
        )
        // 同一件事不在两处出现
        assertEquals(listOf("idle-new", "idle-old"), sections.recent.map { it.sessionId })
    }

    @Test
    fun waitingRowsCarryTheirEarliestAttention() {
        val a = approval("a1", "s")
        val sections = homeSections(listOf(session("s", SessionStatus.WAITING)), emptyList(), workspaces, null) { a }
        assertEquals(ActiveItem.Waiting(session("s", SessionStatus.WAITING), a), sections.active.single())
    }

    @Test
    fun projectFilterOnlyNarrowsTheRecentList() {
        val sessions =
            listOf(
                session("a", updatedAt = 3, workspaceId = "w1"),
                session("b", updatedAt = 2, workspaceId = "w2"),
                session("c", updatedAt = 1, cwd = "/tmp/scratch"),
                session("d", SessionStatus.RUNNING, workspaceId = "w2"),
                session("e"),
                session("f", updatedAt = 0, workspaceId = "w1"),
            )
        val all = homeSections(sessions, emptyList(), workspaces, null) { null }
        // 会话数只算最近列表里的（运行中的 d 在卡片里，不算）
        assertEquals(
            listOf(ProjectOption("w1", "DSHRemote", 2), ProjectOption("cwd:/tmp/scratch", "scratch", 1), ProjectOption("w2", "工具脚本", 1)),
            all.projects,
        )
        val tools = homeSections(sessions, emptyList(), workspaces, "w2") { null }
        assertEquals("w2", tools.filter)
        assertEquals(listOf("b"), tools.recent.map { it.sessionId })
        // 卡片不受筛选影响
        assertEquals(listOf("running:d"), tools.active.map { it.key })
        val byDirectory = homeSections(sessions, emptyList(), workspaces, "cwd:/tmp/scratch") { null }
        assertEquals(listOf("c"), byDirectory.recent.map { it.sessionId })
    }

    /** 落地方案 B2：没看过的失败排在最前面，看过的失败照常按时间；「只看未读」和项目筛选同时生效。 */
    @Test
    fun unreadFailuresComeFirstAndUnreadOnlyNarrowsTheList() {
        val failed = LastTurn(TurnResult.FAILED, 1)
        val sessions =
            listOf(
                session("new", updatedAt = 5, workspaceId = "w1"),
                session("failed-read", updatedAt = 4, workspaceId = "w1", lastTurn = failed),
                session("plain-unread", updatedAt = 3, workspaceId = "w2"),
                session("failed-unread", updatedAt = 2, workspaceId = "w1", lastTurn = failed),
                session("failed-unread-2", updatedAt = 1, workspaceId = "w2", lastTurn = failed),
            )
        val unread = setOf("plain-unread", "failed-unread", "failed-unread-2")
        val all = homeSections(sessions, emptyList(), workspaces, null, unread = unread) { null }
        assertEquals(
            listOf("failed-unread", "failed-unread-2", "new", "failed-read", "plain-unread"),
            all.recent.map { it.sessionId },
        )
        assertEquals(3, all.unreadCount)
        assertEquals(unread, all.unread)

        val onlyUnread = homeSections(sessions, emptyList(), workspaces, null, unread = unread, unreadOnly = true) { null }
        assertEquals(true, onlyUnread.unreadOnly)
        assertEquals(listOf("failed-unread", "failed-unread-2", "plain-unread"), onlyUnread.recent.map { it.sessionId })

        // 项目筛选下只数这个项目的未读
        val w2 = homeSections(sessions, emptyList(), workspaces, "w2", unread = unread, unreadOnly = true) { null }
        assertEquals(2, w2.unreadCount)
        assertEquals(listOf("failed-unread-2", "plain-unread"), w2.recent.map { it.sessionId })

        // 没有未读时「只看未读」自动退回全部
        val none = homeSections(sessions, emptyList(), workspaces, null, unread = emptySet(), unreadOnly = true) { null }
        assertEquals(false, none.unreadOnly)
        assertEquals(5, none.recent.size)
    }

    @Test
    fun aFilterThatNoLongerExistsFallsBackToAllProjects() {
        val sections = homeSections(listOf(session("a", workspaceId = "w1")), emptyList(), workspaces, "gone") { null }
        assertNull(sections.filter)
        assertEquals(listOf("a"), sections.recent.map { it.sessionId })
    }

    @Test
    fun projectNameUsesTheRegisteredTitleOrTheDirectory() {
        val known = workspaces.associateBy { it.workspaceId }
        assertEquals("DSHRemote", projectNameOf(session("a", workspaceId = "w1", cwd = "/x/y"), known))
        assertEquals("y", projectNameOf(session("a", workspaceId = "unknown", cwd = "/x/y"), known))
        assertEquals("app", projectNameOf(session("a", cwd = "C:\\code\\app"), known))
        assertNull(projectNameOf(session("a"), known))
    }

    /** 用户确认：多台电脑时直接进上次用的那台；那台已经移除时用第一台。 */
    @Test
    fun homeShowsTheLastUsedComputer() {
        assertEquals("b", homeHost(listOf("a", "b"), "b"))
        assertEquals("a", homeHost(listOf("a", "b"), "removed"))
        assertEquals("a", homeHost(listOf("a", "b"), null))
        assertNull(homeHost(emptyList(), "a"))
    }

    @Test
    fun offlineRunningSessionsMoveToRecent() {
        val sessions =
            listOf(session("running", SessionStatus.RUNNING, updatedAt = 2), session("waiting", SessionStatus.WAITING, updatedAt = 1))
        val sections = homeSections(sessions, emptyList(), workspaces, null, online = false) { null }
        assertEquals(listOf("waiting:waiting"), sections.active.map { it.key })
        assertEquals(listOf("running"), sections.recent.map { it.sessionId })
    }

    @Test
    fun recentLineFollowsTheLastTurn() {
        fun line(
            result: TurnResult?,
            reply: String? = "改好了，\n  测试都过了",
            error: String? = null,
        ) = recentLine(session("s", lastTurn = result?.let { LastTurn(it, 1, error) }, lastReply = reply), online = true)

        assertEquals(RecentLine.Reply("改好了， 测试都过了"), line(TurnResult.COMPLETED))
        assertEquals(RecentLine.Reply("改好了， 测试都过了"), line(TurnResult.OTHER))
        // 正常结束但没有回复文字
        assertEquals(RecentLine.Status(R.string.turn_ended), line(TurnResult.COMPLETED, reply = null))
        assertEquals(RecentLine.Failed("rate limited"), line(TurnResult.FAILED, error = " rate\nlimited "))
        assertEquals(RecentLine.Failed(null), line(TurnResult.FAILED))
        assertEquals(RecentLine.Status(R.string.turn_stopped), line(TurnResult.STOPPED))
        assertEquals(RecentLine.Status(R.string.turn_interrupted), line(TurnResult.INTERRUPTED))
        // 旧插件：没有轮次数据，只显示项目名
        assertEquals(RecentLine.None, line(null, reply = null))
    }

    @Test
    fun runningWhenTheLinkDroppedIsUnconfirmed() {
        val running = session("s", SessionStatus.RUNNING, lastTurn = LastTurn(TurnResult.COMPLETED, 1), lastReply = "上一轮")
        assertEquals(RecentLine.Status(R.string.turn_unconfirmed), recentLine(running, online = false))
        // 断线时已经不在运行：照常显示最后的结果
        assertEquals(RecentLine.Reply("上一轮"), recentLine(running.copy(status = SessionStatus.IDLE), online = false))
    }
}
