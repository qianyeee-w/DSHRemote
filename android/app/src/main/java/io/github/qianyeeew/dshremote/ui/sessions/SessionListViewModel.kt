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
import io.github.qianyeeew.dshremote.data.live.OfflineException
import io.github.qianyeeew.dshremote.ui.hosts.statusOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
) : ViewModel() {
    /** null 表示还在读取，或者主机已经不存在。 */
    val state: StateFlow<SessionListState?> =
        combine(hosts, states) { list, live ->
            val host = list.firstOrNull { it.hostId == hostId } ?: return@combine null
            val hostState = live[hostId]
            SessionListState(
                hostName = host.name,
                status = statusOf(host, hostState),
                sessions = sortSessions(hostState?.sessions?.values.orEmpty()),
                clockOffsetMillis = hostState?.clockOffsetMillis ?: 0,
                otherAttention = otherAttention(hostState),
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
