package io.github.qianyeeew.dshremote.ui.sessions

import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.Workspace

data class ProjectSessions(
    val workspaceId: String,
    val title: String?,
    val sessions: List<SessionSummary>,
)

data class GroupedSessions(
    val waiting: List<SessionSummary>,
    val projects: List<ProjectSessions>,
    val recent: List<SessionSummary>,
)

/**
 * 待处理会话有独立入口，项目折叠也不会隐藏它；项目内仍保留该会话。
 * 未读到工作区详情时沿用会话的 workspaceId 分组，不能把这些会话丢掉。
 */
fun groupSessions(
    sessions: List<SessionSummary>,
    workspaces: List<Workspace>,
): GroupedSessions {
    val ordered = sortSessions(sessions)
    val byProject = ordered.filter { it.workspaceId != null }.groupBy { requireNotNull(it.workspaceId) }
    val known = workspaces.associateBy { it.workspaceId }
    val ids = (known.keys + byProject.keys).distinct()
    val groups =
        ids
            .map { id ->
                val entries = byProject[id].orEmpty()
                val fallback =
                    entries.firstNotNullOfOrNull {
                        it.cwd
                            ?.trimEnd('/', '\\')
                            ?.substringAfterLast('/')
                            ?.substringAfterLast('\\')
                    }
                ProjectSessions(id, known[id]?.title?.takeIf(String::isNotBlank) ?: fallback?.takeIf(String::isNotBlank), entries)
            }.sortedWith(
                compareByDescending<ProjectSessions> {
                    it.sessions.maxOfOrNull(SessionSummary::updatedAt) ?: Long.MIN_VALUE
                }.thenBy { it.title.orEmpty() },
            )
    return GroupedSessions(
        waiting = ordered.filter { it.status == SessionStatus.WAITING || it.attention.isNotEmpty() },
        projects = groups,
        recent = ordered.filter { it.workspaceId == null },
    )
}
