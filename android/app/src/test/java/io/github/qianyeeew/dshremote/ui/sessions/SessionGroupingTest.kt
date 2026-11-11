package io.github.qianyeeew.dshremote.ui.sessions

import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGroupingTest {
    @Test
    fun waitingSessionRemainsReachableOutsideItsProject() {
        val session = SessionSummary("s", workspaceId = "p", status = SessionStatus.WAITING, updatedAt = 1)
        val groups = groupSessions(listOf(session), listOf(Workspace("p", "项目", "/project")))
        assertEquals(listOf(session), groups.waiting)
        assertEquals(listOf(session), groups.projects.single().sessions)
        assertEquals(emptyList<SessionSummary>(), groups.recent)
    }

    @Test
    fun missingWorkspaceDetailsDoNotHideSessions() {
        val session = SessionSummary("s", workspaceId = "missing", cwd = "E:\\Project\\DSHRemote", updatedAt = 1)
        val groups = groupSessions(listOf(session), emptyList())
        assertEquals("DSHRemote", groups.projects.single().title)
        assertEquals(listOf(session), groups.projects.single().sessions)
    }

    @Test
    fun emptyProjectsAndUngroupedRecentSessionsRemainVisible() {
        val older = SessionSummary("old", updatedAt = 1)
        val newer = SessionSummary("new", updatedAt = 2)
        val groups = groupSessions(listOf(older, newer), listOf(Workspace("empty", "空项目", "/empty")))
        assertEquals(listOf(newer, older), groups.recent)
        assertEquals("empty", groups.projects.single().workspaceId)
        assertEquals(emptyList<SessionSummary>(), groups.projects.single().sessions)
    }
}
