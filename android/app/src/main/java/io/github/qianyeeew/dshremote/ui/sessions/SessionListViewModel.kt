package io.github.qianyeeew.dshremote.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.PluginApi
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.api.isTokenInvalid
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.LiveEvent
import io.github.qianyeeew.dshremote.data.live.OfflineException
import io.github.qianyeeew.dshremote.ui.attention.AttentionHandler
import io.github.qianyeeew.dshremote.ui.attention.AttentionResponder
import io.github.qianyeeew.dshremote.ui.attention.AttentionUi
import io.github.qianyeeew.dshremote.ui.chat.pendingAttention
import io.github.qianyeeew.dshremote.ui.hosts.statusOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
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
)

/** 新建会话的进度。 */
sealed interface NewSession {
    data object Closed : NewSession

    data object Loading : NewSession

    /** 选择工作区。 */
    data class Choose(
        val workspaces: List<Workspace>,
    ) : NewSession

    data object Creating : NewSession

    data class Failed(
        val error: CreateError,
    ) : NewSession

    /** 已创建，界面打开这个会话后调用 [SessionListViewModel.dismissNewSession]。 */
    data class Created(
        val sessionId: String,
    ) : NewSession
}

enum class CreateError {
    /** 电脑不在线（或请求时连接断了）。 */
    OFFLINE,

    /** 插件在运行，但 DSH 暂时不可用。 */
    DSH_UNAVAILABLE,

    /** token 已失效，需要重新配对。 */
    NEEDS_PAIRING,

    FAILED,
}

/** 新建会话用到的接口（ViewModel 测试中替换成假的实现）。 */
interface SessionCreator {
    /** 不在线时抛出 [OfflineException]。 */
    suspend fun workspaces(): List<Workspace>

    /** 返回新会话的 id。[workspaceId] 为 null 时由 DSH 决定工作目录。 */
    suspend fun create(workspaceId: String?): String
}

/** 通过这台主机当前在线的连接调用插件（[io.github.qianyeeew.dshremote.data.live.ConnectionManager.api]）。 */
class PluginSessionCreator(
    private val api: () -> PluginApi?,
) : SessionCreator {
    override suspend fun workspaces(): List<Workspace> = (api() ?: throw OfflineException()).workspaces()

    override suspend fun create(workspaceId: String?): String = (api() ?: throw OfflineException()).createSession(workspaceId)
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
) : ViewModel() {
    val projects = ProjectFlow(viewModelScope, projectBackend)

    /** 卡片里直接处理审批和提问，和会话页同一套提交逻辑（设计规格 7.1）。 */
    val attention = AttentionHandler(viewModelScope, responder, resolvedEvents)

    /** 已登记的项目（显示项目名）。页面可见时刷新。 */
    val catalog = WorkspaceCatalog(viewModelScope, creator)

    fun chooseProjectFilter(filter: String?) {
        viewModelScope.launch { saveProjectFilter(filter) }
    }

    fun startProjectSession(workspace: Workspace) {
        if (projects.state.value?.workspace != workspace) return
        projects.dismiss()
        _newSession.value = NewSession.Choose(listOf(workspace))
        create(workspace.workspaceId)
    }

    /** null 表示还在读取，或者主机已经不存在。 */
    val state: StateFlow<SessionListState?> =
        combine(hosts, states, catalog.state, projectFilter, attention.ui) { list, live, workspaces, filter, attentionUi ->
            val host = list.firstOrNull { it.hostId == hostId } ?: return@combine null
            val hostState = live[hostId]
            val sessions = hostState?.sessions?.values.orEmpty()
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
                    homeSections(sessions, others, workspaces.workspaces, filter) { session ->
                        pendingAttention(hostState, session, session.sessionId).firstOrNull { !done(it) }
                    },
                workspaces = workspaces.workspaces,
                attentionUi = attentionUi,
                hostId = hostId,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val _newSession = MutableStateFlow<NewSession>(NewSession.Closed)
    val newSession: StateFlow<NewSession> = _newSession.asStateFlow()

    /** 打开新建会话的对话框：先读取工作区列表。 */
    fun startNewSession() {
        if (_newSession.value != NewSession.Closed && _newSession.value !is NewSession.Failed) return
        _newSession.value = NewSession.Loading
        viewModelScope.launch {
            _newSession.value =
                try {
                    NewSession.Choose(creator.workspaces())
                } catch (e: OfflineException) {
                    NewSession.Failed(errorOf(e))
                } catch (e: ApiException) {
                    NewSession.Failed(errorOf(e))
                }
        }
    }

    /** 选好了工作区（null 表示不指定）。 */
    fun create(workspaceId: String?) {
        if (_newSession.value !is NewSession.Choose) return
        _newSession.value = NewSession.Creating
        viewModelScope.launch {
            _newSession.value =
                try {
                    NewSession.Created(creator.create(workspaceId))
                } catch (e: OfflineException) {
                    NewSession.Failed(errorOf(e))
                } catch (e: ApiException) {
                    NewSession.Failed(errorOf(e))
                }
        }
    }

    /** 关闭对话框，或者已经打开了新建的会话。正在创建时不能关闭（避免重复创建）。 */
    fun dismissNewSession() {
        if (_newSession.value != NewSession.Creating) _newSession.value = NewSession.Closed
    }

    private fun errorOf(e: Exception): CreateError =
        when {
            e is OfflineException || e is ApiException.Network -> CreateError.OFFLINE
            e is ApiException && e.isTokenInvalid -> CreateError.NEEDS_PAIRING
            e is ApiException.Http && (e.code == ErrorCode.DSH_UNAVAILABLE || e.status == HTTP_UNAVAILABLE) -> CreateError.DSH_UNAVAILABLE
            else -> CreateError.FAILED
        }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val HTTP_UNAVAILABLE = 503
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
