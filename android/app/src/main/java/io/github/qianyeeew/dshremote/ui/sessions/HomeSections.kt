package io.github.qianyeeew.dshremote.ui.sessions

import androidx.annotation.StringRes
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.TurnResult
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.ui.chat.fileName

/** 首页「进行中」卡片里的一行（设计规格 7.1）。 */
sealed interface ActiveItem {
    val key: String

    /** 等你处理的会话。[attention] 是它最早的事项；事项还没到时为 null，只显示「等你处理」。 */
    data class Waiting(
        val session: SessionSummary,
        val attention: Attention?,
    ) : ActiveItem {
        override val key: String get() = "waiting:${session.sessionId}"
    }

    /** 不属于任何会话的事项（子代理发起的），标题用事项本身的说明。 */
    data class Orphan(
        val attention: Attention,
    ) : ActiveItem {
        override val key: String get() = "orphan:${attention.attentionId}"
    }

    data class Running(
        val session: SessionSummary,
    ) : ActiveItem {
        override val key: String get() = "running:${session.sessionId}"
    }
}

/** 项目筛选里的一项。[key] 是已登记项目的 id；没登记的会话按工作目录归到 `cwd:` 开头的项。 */
data class ProjectOption(
    val key: String,
    val name: String,
)

/** 首页的两段：进行中卡片和最近列表。 */
data class HomeSections(
    val active: List<ActiveItem>,
    val recent: List<SessionSummary>,
    /** 最近列表里出现过的项目，按名字排序。 */
    val projects: List<ProjectOption>,
    /** 实际生效的筛选：选中的项目已经不在列表里时退回 null（全部项目）。 */
    val filter: String?,
)

/** 会话属于哪个项目（筛选用）。没有工作目录的会话不属于任何项目。 */
fun projectKey(session: SessionSummary): String? = session.workspaceId ?: session.cwd?.takeIf { it.isNotBlank() }?.let { "cwd:$it" }

/** 会话的项目名：已登记项目的名字，否则是工作目录的最后一段。 */
fun projectNameOf(
    session: SessionSummary,
    workspaces: Map<String, Workspace>,
): String? =
    session.workspaceId?.let { workspaces[it]?.title?.takeIf(String::isNotBlank) }
        ?: session.cwd?.takeIf { it.isNotBlank() }?.let(::fileName)

/**
 * 分段（设计规格 7.1）：
 * - 进行中：等你处理的会话在前（按更新时间倒序），然后是不属于任何会话的事项（按出现的先后），最后是运行中的会话。
 *   这张卡片回答「有没有事等我」，不受项目筛选影响。没连上电脑时（[online] 为 false）不知道运行中的会话现在怎么样，
 *   放回最近列表，第二行写「结果待确认」（见 [recentLine]）。
 * - 最近：其余会话按更新时间倒序，同一件事不在两处出现。按 [filter] 只看一个项目。
 *
 * [attentionOf] 给出一个会话最早的待处理事项（见 `pendingAttention`）。
 */
fun homeSections(
    sessions: Collection<SessionSummary>,
    otherAttention: List<Attention>,
    workspaces: List<Workspace>,
    filter: String?,
    online: Boolean = true,
    attentionOf: (SessionSummary) -> Attention?,
): HomeSections {
    val byTime = sessions.sortedWith(compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.sessionId })
    val waiting = byTime.filter { it.status == SessionStatus.WAITING || it.attention.isNotEmpty() }
    val running = if (online) byTime.filter { it.status == SessionStatus.RUNNING && it !in waiting } else emptyList()
    val activeIds = (waiting + running).mapTo(HashSet()) { it.sessionId }
    val rest = byTime.filter { it.sessionId !in activeIds }

    val known = workspaces.associateBy { it.workspaceId }
    val projects =
        rest
            .mapNotNull { session -> projectKey(session)?.let { key -> projectNameOf(session, known)?.let { ProjectOption(key, it) } } }
            .distinctBy { it.key }
            .sortedWith(compareBy<ProjectOption> { it.name.lowercase() }.thenBy { it.key })
    val effective = filter?.takeIf { key -> projects.any { it.key == key } }
    return HomeSections(
        active =
            waiting.map { ActiveItem.Waiting(it, attentionOf(it)) } +
                otherAttention.sortedBy { it.createdAt }.map { ActiveItem.Orphan(it) } +
                running.map { ActiveItem.Running(it) },
        recent = if (effective == null) rest else rest.filter { projectKey(it) == effective },
        projects = projects,
        filter = effective,
    )
}

/** 最近列表每行第二行左边：这个会话最后的结果（设计规格 7.1 的表）。 */
sealed interface RecentLine {
    /** 正常结束：DSH 最后一句话的开头。 */
    data class Reply(
        val text: String,
    ) : RecentLine

    /** 一句状态：已停止、已中断、结果待确认，或者正常结束但没有回复文字时的「本轮结束」。 */
    data class Status(
        @param:StringRes val text: Int,
    ) : RecentLine

    /** 红色「失败」，后面跟原因（拿得到时）。 */
    data class Failed(
        val reason: String?,
    ) : RecentLine

    /** 没有轮次数据（旧插件、还没运行过）：只显示项目名。 */
    data object None : RecentLine
}

/**
 * 最近列表一行的第二行。没连上电脑而会话断线前还在运行，是「结果待确认」（落地方案 B1：由 App 判断，
 * 重新连上、拿到新状态之后就换成真实结果）。
 */
fun recentLine(
    session: SessionSummary,
    online: Boolean,
): RecentLine {
    if (!online && session.status == SessionStatus.RUNNING) return RecentLine.Status(R.string.turn_unconfirmed)
    val reply =
        session.lastReply
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    return when (session.lastTurn?.result) {
        TurnResult.FAILED ->
            RecentLine.Failed(
                session.lastTurn.error
                    ?.replace(WHITESPACE, " ")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
            )
        TurnResult.STOPPED -> RecentLine.Status(R.string.turn_stopped)
        TurnResult.INTERRUPTED -> RecentLine.Status(R.string.turn_interrupted)
        null -> reply?.let { RecentLine.Reply(it) } ?: RecentLine.None
        TurnResult.COMPLETED, TurnResult.OTHER, TurnResult.UNKNOWN ->
            reply?.let { RecentLine.Reply(it) }
                ?: RecentLine.Status(R.string.turn_ended)
    }
}

private val WHITESPACE = Regex("\\s+")

/**
 * 首页显示哪台电脑（用户确认：多台时直接进上次用的那台）：上次用的那台还在就用它，否则第一台。没有电脑时为 null。
 */
fun homeHost(
    hostIds: List<String>,
    lastHostId: String?,
): String? = lastHostId?.takeIf { it in hostIds } ?: hostIds.firstOrNull()
