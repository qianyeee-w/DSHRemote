package io.github.qianyeeew.dshremote.ui.sessions

import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.AttentionRef
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
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
    ) = SessionSummary(id, title = id, workspaceId = workspaceId, cwd = cwd, status = status, attention = attention, updatedAt = updatedAt)

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
            )
        val all = homeSections(sessions, emptyList(), workspaces, null) { null }
        assertEquals(
            listOf(ProjectOption("w1", "DSHRemote"), ProjectOption("cwd:/tmp/scratch", "scratch"), ProjectOption("w2", "工具脚本")),
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
}
