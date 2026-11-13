package io.github.qianyeeew.dshremote.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.ModelList
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PermissionPresetList
import io.github.qianyeeew.dshremote.data.api.PluginApi
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.LiveEvent
import io.github.qianyeeew.dshremote.data.live.OfflineException
import io.github.qianyeeew.dshremote.data.seen.Reconnect
import io.github.qianyeeew.dshremote.data.seen.SeenRecord
import io.github.qianyeeew.dshremote.data.seen.isUnread
import io.github.qianyeeew.dshremote.data.seen.offlineResults
import io.github.qianyeeew.dshremote.ui.attention.AttentionHandler
import io.github.qianyeeew.dshremote.ui.attention.AttentionResponder
import io.github.qianyeeew.dshremote.ui.attention.AttentionUi
import io.github.qianyeeew.dshremote.ui.chat.pendingAttention
import io.github.qianyeeew.dshremote.ui.hosts.statusOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 会话列表页的内容。 */
data class SessionListState(
    val hostName: String,
    val status: LinkStatus,
    /** 已排序（[sortSessions]）。不在线时是最后一次连接时的内容，可能已经过时。 */
    val sessions: List<SessionSummary>,
    /** 电脑的时钟比手机快多少毫秒，见 [HostLiveState.clockOffsetMillis]。 */
    val clockOffsetMillis: Long = 0,
    /** 不属于列表中任何会话的待处理事项（例如子代理发起的），在列表上方单独显示。 */
    val otherAttention: List<Attention> = emptyList(),
    /** 首页的两段（设计规格 7.1）。默认按 [sessions] 和 [otherAttention] 算，不带项目名和事项。 */
    val sections: HomeSections = homeSections(sessions, otherAttention, emptyList(), null) { null },
    /** 已登记的项目，用来显示项目名。 */
    val workspaces: List<Workspace> = emptyList(),
    /** 卡片里处理事项的状态（提交中、失败）。 */
    val attentionUi: Map<String, AttentionUi> = emptyMap(),
    /** 这台电脑的 id（App 本地的），菜单里标出当前电脑用。 */
    val hostId: String = "",
    /** 断线一段时间后重新连上，离线期间有新结果时的提示（设计规格 7.1）。 */
    val reconnect: ReconnectLine? = null,
)

/** 「刚重新连上。离线的 [offlineMillis] 里有 [results] 条新结果。」 */
data class ReconnectLine(
    val offlineMillis: Long,
    val results: Int,
)

/** 新建会话和开始新任务用到的接口（ViewModel 测试中替换成假的实现）。都是 protocol.md 里已有的接口，不在线时抛出 [OfflineException]。 */
interface SessionCreator {
    suspend fun workspaces(): List<Workspace>

    /** 返回新会话的 id。[workspaceId] 为 null 时由 DSH 决定工作目录。 */
    suspend fun create(workspaceId: String?): String

    suspend fun models(): ModelList

    suspend fun presets(): PermissionPresetList

    suspend fun setModel(
        sessionId: String,
        model: ModelRef,
    )

    suspend fun setPreset(
        sessionId: String,
        preset: String,
    )

    /** 发消息。重试时传同一个 [OutgoingMessage]（requestId 不变，插件据此去重）。 */
    suspend fun prompt(
        sessionId: String,
        message: OutgoingMessage,
    )
}

/** 通过这台主机当前在线的连接调用插件（[io.github.qianyeeew.dshremote.data.live.ConnectionManager.api]）。 */
class PluginSessionCreator(
    private val api: () -> PluginApi?,
) : SessionCreator {
    override suspend fun workspaces(): List<Workspace> = connected().workspaces()

    override suspend fun create(workspaceId: String?): String = connected().createSession(workspaceId)

    override suspend fun models(): ModelList = connected().models()

    override suspend fun presets(): PermissionPresetList = connected().permissionPresets()

    override suspend fun setModel(
        sessionId: String,
        model: ModelRef,
    ) {
        connected().setModel(sessionId, model)
    }

    override suspend fun setPreset(
        sessionId: String,
        preset: String,
    ) {
        connected().setPermissionPreset(sessionId, preset)
    }

    override suspend fun prompt(
        sessionId: String,
        message: OutgoingMessage,
    ) = connected().prompt(sessionId, message)

    private fun connected() = api() ?: throw OfflineException()
}

/**
 * 一台主机的会话列表（m2-android.md T8）。会话来自这台主机的实时连接（`hello` 和之后的 `session.*` 事件），
 * 不单独请求 `/v1/sessions`。
 */
class SessionListViewModel(
    private val hostId: String,
    hosts: Flow<List<Host>>,
    states: Flow<Map<String, HostLiveState>>,
    private val creator: SessionCreator,
    projectBackend: ProjectBackend,
    responder: AttentionResponder = AttentionResponder { _, _ -> throw OfflineException() },
    resolvedEvents: Flow<LiveEvent.AttentionResolved> = emptyFlow(),
    /** 最近列表的项目筛选（存在本地设置里，见 [io.github.qianyeeew.dshremote.data.settings.SettingsRepository.projectFilter]）。 */
    projectFilter: Flow<String?> = flowOf(null),
    private val saveProjectFilter: suspend (String?) -> Unit = {},
    /** 新任务上一次用的项目（[NO_PROJECT] 是不指定）。 */
    lastProject: Flow<String?> = flowOf(null),
    saveLastProject: suspend (String) -> Unit = {},
    /** 这台电脑上哪些结果看过（[io.github.qianyeeew.dshremote.data.seen.ReadTracker.records]）。 */
    seen: Flow<SeenRecord?> = flowOf(null),
    /** 这台电脑的重连提示（[io.github.qianyeeew.dshremote.data.seen.ReadTracker.reconnects]）。 */
    reconnect: Flow<Reconnect?> = flowOf(null),
    private val onDismissReconnect: () -> Unit = {},
) : ViewModel() {
    val projects = ProjectFlow(viewModelScope, projectBackend)

    /** 卡片里直接处理审批和提问，和会话页同一套提交逻辑（设计规格 7.1）。 */
    val attention = AttentionHandler(viewModelScope, responder, resolvedEvents)

    /** 已登记的项目（显示项目名）。页面可见时刷新。 */
    val catalog = WorkspaceCatalog(viewModelScope, creator::workspaces)

    fun chooseProjectFilter(filter: String?) {
        viewModelScope.launch { saveProjectFilter(filter) }
    }

    /** 「N 条未读」：只看未读，再点一次看全部。只在这次打开期间有效，不记住。 */
    private val unreadOnly = MutableStateFlow(false)

    fun toggleUnreadOnly() {
        unreadOnly.update { !it }
    }

    /** 用户滑动了列表：重连提示消失。 */
    fun dismissReconnect() = onDismissReconnect()

    /** 首页底部原地展开的新任务（设计规格 7.6）。 */
    val newTask = NewTaskFlow(viewModelScope, creator, saveLastProject)

    /** 新任务默认的项目：上一次用的，见 [defaultProject]。 */
    val defaultProject: StateFlow<ProjectPick> =
        combine(catalog.state, lastProject) { workspaces, last -> defaultProject(workspaces.workspaces, last) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProjectPick.None)

    /** null 表示还在读取，或者主机已经不存在。 */
    val state: StateFlow<SessionListState?> =
        combine(
            hosts,
            states,
            combine(catalog.state, projectFilter, attention.ui, ::Triple),
            combine(seen, reconnect, unreadOnly, ::Triple),
        ) { list, live, (workspaces, filter, attentionUi), (record, offline, onlyUnread) ->
            val host = list.firstOrNull { it.hostId == hostId } ?: return@combine null
            val hostState = live[hostId]
            val sessions = hostState?.sessions?.values.orEmpty()
            val unread = sessions.filter { record.isUnread(it) }.mapTo(HashSet()) { it.sessionId }
            val clockOffset = hostState?.clockOffsetMillis ?: 0
            // 在这台手机上已经处理完的事项马上从卡片里去掉，不等插件推送
            val done = { a: Attention -> attentionUi[a.attentionId]?.resolution != null }
            val others = otherAttention(hostState).filterNot(done)
            SessionListState(
                hostName = host.name,
                status = statusOf(host, hostState),
                sessions = sortSessions(sessions),
                clockOffsetMillis = hostState?.clockOffsetMillis ?: 0,
                otherAttention = others,
                sections =
                    homeSections(
                        sessions,
                        others,
                        workspaces.workspaces,
                        filter,
                        online = hostState?.status is LinkStatus.Online,
                        unread = unread,
                        unreadOnly = onlyUnread,
                    ) { session ->
                        pendingAttention(hostState, session, session.sessionId).firstOrNull { !done(it) }
                    },
                workspaces = workspaces.workspaces,
                attentionUi = attentionUi,
                hostId = hostId,
                reconnect =
                    offline?.let { r ->
                        offlineResults(sessions, record, r, clockOffset).takeIf { it > 0 }?.let { ReconnectLine(r.offlineMillis, it) }
                    },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /**
     * 项目创建或导入好了：在新任务里选上它（新建会话等用户写好任务再开始，不再立即创建）。
     * 刷新项目目录，让新项目出现在列表里。
     */
    fun useProject(workspace: Workspace) {
        if (projects.state.value?.workspace != workspace) return
        projects.dismiss()
        newTask.chooseProject(ProjectPick.Existing(workspace))
        catalog.refresh()
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** 不属于任何会话的待处理事项：`sessionId` 不在会话列表中，也没有出现在任何会话摘要的 `attention` 里。 */
internal fun otherAttention(state: HostLiveState?): List<Attention> {
    if (state == null) return emptyList()
    val listed = state.sessions.values.flatMapTo(HashSet()) { s -> s.attention.map { it.attentionId } }
    return state.attention.values
        .filter { it.sessionId !in state.sessions && it.attentionId !in listed }
        .sortedBy { it.createdAt }
}

/** protocol.md 5.1：按「waiting → running → idle」排序，同一状态内按 `updatedAt` 倒序。不认识的状态排在最后。 */
fun sortSessions(sessions: Collection<SessionSummary>): List<SessionSummary> =
    sessions.sortedWith(compareBy<SessionSummary> { rank(it.status) }.thenByDescending { it.updatedAt }.thenBy { it.sessionId })

private fun rank(status: SessionStatus): Int =
    when (status) {
        SessionStatus.WAITING -> 0
        SessionStatus.RUNNING -> 1
        SessionStatus.IDLE -> 2
        SessionStatus.UNKNOWN -> 3
    }

/** 卡片里这一行对应的事项（运行中的会话没有）。 */
internal fun attentionIdOf(item: ActiveItem): String? =
    when (item) {
        is ActiveItem.Waiting -> item.attention?.attentionId
        is ActiveItem.Orphan -> item.attention.attentionId
        is ActiveItem.Running -> null
    }
