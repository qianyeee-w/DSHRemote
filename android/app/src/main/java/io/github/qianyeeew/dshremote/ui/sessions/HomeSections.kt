package io.github.qianyeeew.dshremote.ui.sessions

import androidx.annotation.StringRes
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.TurnResult
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.ui.chat.fileName
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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

/** 首页的两段：进行中卡片和最近列表。 */
data class HomeSections(
    val active: List<ActiveItem>,
    val recent: List<SessionSummary>,
    /** 没看过结果的会话（落地方案 B2）：标题加粗，第二行用主文字色。 */
    val unread: Set<String> = emptySet(),
    /** 最近列表里的未读数，「最近」旁边的「N 条未读」。 */
    val unreadCount: Int = 0,
    /** 实际生效的「只看未读」：没有未读时自动退回全部。 */
    val unreadOnly: Boolean = false,
)

/** 会话属于哪个项目（分组用）。没有工作目录的会话不属于任何项目。 */
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
 * - 最近：其余会话按更新时间倒序，同一件事不在两处出现。分组见 [recentGroups]。
 *
 * [attentionOf] 给出一个会话最早的待处理事项（见 `pendingAttention`）。
 */
fun homeSections(
    sessions: Collection<SessionSummary>,
    otherAttention: List<Attention>,
    online: Boolean = true,
    unread: Set<String> = emptySet(),
    unreadOnly: Boolean = false,
    attentionOf: (SessionSummary) -> Attention?,
): HomeSections {
    val byTime = sessions.sortedWith(compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.sessionId })
    val waiting = byTime.filter { it.status == SessionStatus.WAITING || it.attention.isNotEmpty() }
    val running = if (online) byTime.filter { it.status == SessionStatus.RUNNING && it !in waiting } else emptyList()
    val activeIds = (waiting + running).mapTo(HashSet()) { it.sessionId }
    val rest = byTime.filter { it.sessionId !in activeIds }
    val unreadHere = rest.count { it.sessionId in unread }
    val onlyUnread = unreadOnly && unreadHere > 0
    // 没看过的失败排在最前面（用户确认：看过之后回到按时间排）
    val failedFirst = rest.sortedByDescending { it.sessionId in unread && it.lastTurn?.result == TurnResult.FAILED }
    return HomeSections(
        active =
            waiting.map { ActiveItem.Waiting(it, attentionOf(it)) } +
                otherAttention.sortedBy { it.createdAt }.map { ActiveItem.Orphan(it) } +
                running.map { ActiveItem.Running(it) },
        recent = if (onlyUnread) failedFirst.filter { it.sessionId in unread } else failedFirst,
        unread = unread.filterTo(HashSet()) { id -> rest.any { it.sessionId == id } },
        unreadCount = unreadHere,
        unreadOnly = onlyUnread,
    )
}

/** 首页最近列表怎么分组（「⋯」菜单里选，记在本地设置里）。 */
enum class HomeGrouping {
    /** 按项目（默认）：每个项目一组，组名右边的加号在这个项目里新建任务。 */
    PROJECT,

    /** 按日期：今天、昨天、七天内、更早。 */
    DATE,

    /** 按状态：失败、有新结果、已看过。 */
    STATUS,
    ;

    companion object {
        fun of(name: String?): HomeGrouping = entries.firstOrNull { it.name == name } ?: PROJECT
    }
}

/** 最近列表的一组。 */
data class RecentGroup(
    /** 折叠状态按它记。 */
    val key: String,
    /** 组名：项目名（按项目分组时），否则用 [titleRes]。 */
    val name: String?,
    @param:StringRes val titleRes: Int?,
    /** 组名右边的加号在这个项目里新建任务；没有登记的项目（只有工作目录）不能新建，为 null。 */
    val workspace: Workspace?,
    val sessions: List<SessionSummary>,
)

/**
 * 把最近列表分组。组里的顺序沿用 [recent]（没看过的失败在前，然后按时间）。
 * - 项目：按组里最近的更新时间排，没有项目的会话放在最后一组「未指定项目」。
 * - 日期：按 [now]（电脑的时钟）所在的那一天算今天、昨天、七天内、更早。
 * - 状态：失败、有新结果（没看过）、已看过。
 * 空的组不出现。
 */
fun recentGroups(
    recent: List<SessionSummary>,
    grouping: HomeGrouping,
    workspaces: List<Workspace>,
    unread: Set<String>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecentGroup> =
    when (grouping) {
        HomeGrouping.PROJECT -> {
            val known = workspaces.associateBy { it.workspaceId }
            val byProject = recent.groupBy { projectKey(it) }
            val (named, none) = byProject.entries.partition { it.key != null }
            named
                .sortedByDescending { (_, list) -> list.maxOf { it.updatedAt } }
                .map { (key, list) ->
                    RecentGroup(
                        key = "project:$key",
                        name = projectNameOf(list.first(), known) ?: key,
                        titleRes = null,
                        workspace = list.first().workspaceId?.let(known::get),
                        sessions = list,
                    )
                } +
                none.map { (_, list) -> RecentGroup("project:none", null, R.string.home_group_no_project, null, list) }
        }
        HomeGrouping.DATE -> {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val buckets =
                recent.groupBy { session ->
                    val day = Instant.ofEpochMilli(session.updatedAt).atZone(zone).toLocalDate()
                    val days = ChronoUnit.DAYS.between(day, today)
                    when {
                        days <= 0 -> R.string.home_group_today
                        days == 1L -> R.string.home_group_yesterday
                        days < WEEK_DAYS -> R.string.home_group_week
                        else -> R.string.home_group_earlier
                    }
                }
            listOf(R.string.home_group_today, R.string.home_group_yesterday, R.string.home_group_week, R.string.home_group_earlier)
                .mapNotNull { title -> buckets[title]?.let { RecentGroup("date:$title", null, title, null, it) } }
        }
        HomeGrouping.STATUS -> {
            val buckets =
                recent.groupBy { session ->
                    when {
                        session.lastTurn?.result == TurnResult.FAILED -> R.string.home_group_failed
                        session.sessionId in unread -> R.string.home_group_unread
                        else -> R.string.home_group_seen
                    }
                }
            listOf(R.string.home_group_failed, R.string.home_group_unread, R.string.home_group_seen)
                .mapNotNull { title -> buckets[title]?.let { RecentGroup("status:$title", null, title, null, it) } }
        }
    }

private const val WEEK_DAYS = 7L

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

    /** 有没发出去的草稿（落地方案 B3）：主文字色「草稿」，后面跟草稿开头。 */
    data class Draft(
        val text: String,
    ) : RecentLine

    /** 没有轮次数据（旧插件、还没运行过）：只显示项目名。 */
    data object None : RecentLine
}

/**
 * 最近列表一行的第二行。有草稿时先显示草稿（这是用户自己还没做完的事）。没连上电脑而会话断线前还在运行，
 * 是「结果待确认」（落地方案 B1：由 App 判断，重新连上、拿到新状态之后就换成真实结果）。
 */
fun recentLine(
    session: SessionSummary,
    online: Boolean,
    draft: String? = null,
): RecentLine {
    draft
        ?.replace(WHITESPACE, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return RecentLine.Draft(it) }
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
