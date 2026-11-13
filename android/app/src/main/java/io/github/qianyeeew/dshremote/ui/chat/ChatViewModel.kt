package io.github.qianyeeew.dshremote.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.MessagePage
import io.github.qianyeeew.dshremote.data.api.ModelList
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PermissionPresetList
import io.github.qianyeeew.dshremote.data.api.PromptMode
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.Turn
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.chat.ChatTimeline
import io.github.qianyeeew.dshremote.data.chat.LocalMessage
import io.github.qianyeeew.dshremote.data.chat.Outbox
import io.github.qianyeeew.dshremote.data.chat.OutgoingStatus
import io.github.qianyeeew.dshremote.data.chat.deliveryStatus
import io.github.qianyeeew.dshremote.data.live.ConnectionManager
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.LiveEvent
import io.github.qianyeeew.dshremote.data.live.OfflineException
import io.github.qianyeeew.dshremote.data.live.SessionEvent
import io.github.qianyeeew.dshremote.ui.attention.AttentionHandler
import io.github.qianyeeew.dshremote.ui.attention.AttentionResponder
import io.github.qianyeeew.dshremote.ui.attention.AttentionUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 会话详情用到的接口（ViewModel 测试中替换成假的实现）。不在线时抛出 [OfflineException]。 */
interface ChatBackend {
    /** 订阅这个会话的推送。每次订阅在连接上生效（包括重连后）都会先收到 [SessionEvent.Resync]。 */
    fun events(): Flow<SessionEvent>

    suspend fun messages(before: String?): MessagePage

    suspend fun prompt(message: OutgoingMessage)

    suspend fun cancel()

    suspend fun models(): ModelList

    suspend fun setModel(model: ModelRef)

    suspend fun permissionPresets(): PermissionPresetList

    suspend fun setPermissionPreset(preset: String)

    /** 电脑上的项目，用来在顶栏显示项目名。 */
    suspend fun workspaces(): List<Workspace>
}

/** 通过实时连接订阅，通过 [ConnectionManager.api] 调用接口。 */
class LiveChatBackend(
    private val connections: ConnectionManager,
    private val hostId: String,
    private val sessionId: String,
) : ChatBackend {
    override fun events(): Flow<SessionEvent> = flow { connections.connection(hostId)?.let { emitAll(it.subscribe(sessionId)) } }

    override suspend fun messages(before: String?): MessagePage = api().messages(sessionId, before, PAGE_SIZE)

    override suspend fun prompt(message: OutgoingMessage) = api().prompt(sessionId, message)

    override suspend fun cancel() = api().cancel(sessionId)

    override suspend fun models(): ModelList = api().models()

    override suspend fun setModel(model: ModelRef) {
        api().setModel(sessionId, model)
    }

    override suspend fun permissionPresets(): PermissionPresetList = api().permissionPresets()

    override suspend fun setPermissionPreset(preset: String) {
        api().setPermissionPreset(sessionId, preset)
    }

    override suspend fun workspaces(): List<Workspace> = api().workspaces()

    private fun api() = connections.api(hostId) ?: throw OfflineException()

    private companion object {
        /** 每页最多 30 条 DSH 消息：第一屏够用，往上翻时再取。 */
        const val PAGE_SIZE = 30
    }
}

/** 列表中的一项。[key] 在列表中唯一。 */
sealed interface ChatItem {
    val key: String

    data class Entry(
        override val key: String,
        val message: Message,
    ) : ChatItem

    /** 本地发出、还没出现在会话日志中的消息。 */
    data class Local(
        override val key: String,
        val local: LocalMessage,
    ) : ChatItem
}

/** 会话详情页的内容。 */
data class ChatState(
    /** 实时连接中的会话摘要；还没有或会话已被删除时为 null。 */
    val session: SessionSummary?,
    val link: LinkStatus,
    /** 按显示顺序（最早的在前）。 */
    val items: List<ChatItem>,
    val loaded: Boolean,
    val hasMore: Boolean,
    val loadingOlder: Boolean,
    /** 读取历史失败，可以重试。 */
    val loadFailed: Boolean,
    /** 这个会话的待处理事项（不含已经在本地确认结束的），按出现的先后。 */
    val attention: List<Attention> = emptyList(),
    val attentionUi: Map<String, AttentionUi> = emptyMap(),
    /** 电脑名（顶栏第二行）。 */
    val hostName: String? = null,
    /** 项目名（顶栏第二行）：电脑上登记的项目名，拿不到时用工作目录的最后一段。 */
    val projectName: String? = null,
    /** 读到和推送来的轮次（protocol.md 5.4），按编号。 */
    val turns: Map<Int, Turn> = emptyMap(),
    /** 电脑的时钟比手机快多少毫秒（[HostLiveState.clockOffsetMillis]），算本轮用时。 */
    val clockOffsetMillis: Long = 0,
) {
    val online: Boolean get() = link is LinkStatus.Online

    /** 正在运行：发消息插话。 */
    val running: Boolean get() = session?.status == SessionStatus.RUNNING

    /**
     * 结果待确认（落地方案 B1）：断线前还在运行，重新连上、拿到新状态之前不知道这一轮怎么样了。
     * 这时不显示运行状态行。
     */
    val unconfirmed: Boolean get() = running && !online

    /** 还在运行的那一轮（编号最大的）。 */
    val runningTurn: Turn? get() = turns.values.filter { it.running }.maxByOrNull { it.turn }

    /** 任务还没结束（运行中，或者在等待审批、回答）：显示「停止」。 */
    val active: Boolean get() = running || session?.status == SessionStatus.WAITING

    /** 可以发消息：在线并且已经读到历史（之前发的消息要靠历史来对应，见 [Outbox]）。 */
    val canSend: Boolean get() = online && loaded
}

/** 一次性的提示（显示后调用 [ChatViewModel.dismissNotice]）。 */
enum class ChatNotice {
    OFFLINE,
    CANCEL_FAILED,
    LOAD_OLDER_FAILED,
    CHANGE_FAILED,

    /** 新任务：会话已经创建，但模型或审批方式没设置成功，消息还没发。 */
    SETUP_FAILED,

    /** 「查看」之后会话里还没有这条消息。 */
    NOT_IN_SESSION,
}

/** 输入框里原地展开的模型或审批方式的选项（设计规格 6.10）。 */
sealed interface Picker {
    data object Closed : Picker

    /** 正在读取 [kind] 的选项。 */
    data class Loading(
        val kind: PickerKind,
    ) : Picker

    data class Models(
        val list: ModelList,
    ) : Picker

    data class Presets(
        val list: PermissionPresetList,
    ) : Picker
}

enum class PickerKind { MODELS, PRESETS }

/** 展开的是哪一种选项；收起时为 null。 */
val Picker.kind: PickerKind?
    get() =
        when (this) {
            Picker.Closed -> null
            is Picker.Loading -> kind
            is Picker.Models -> PickerKind.MODELS
            is Picker.Presets -> PickerKind.PRESETS
        }

/**
 * 会话详情（m2-android.md 3.4、T9）。界面可见期间（[onVisible] 到 [onHidden]）订阅这个会话的推送，
 * 消息按 [ChatTimeline] 的规则合并；发出的消息在 [Outbox] 中显示到它出现在会话日志里为止。
 * 所有状态只在主线程上修改。
 */
class ChatViewModel(
    hostId: String,
    sessionId: String,
    states: Flow<Map<String, HostLiveState>>,
    private val backend: ChatBackend,
    responder: AttentionResponder,
    resolvedEvents: Flow<LiveEvent.AttentionResolved>,
    hostName: Flow<String?> = flowOf(null),
    /** 首页开始新任务时没有完成的步骤（见 [ChatHandoff]）。 */
    handoff: ChatHandoff? = null,
    /** 这个会话存在本地的草稿（落地方案 B3）。 */
    storedDraft: Flow<String?> = flowOf(null),
    /** 存草稿。在 App 的作用域里执行，离开会话页后也会存完。 */
    private val saveDraft: (String) -> Unit = {},
) : ViewModel() {
    /** 模型或审批方式没设置成功、消息还没发时，放回输入框的文字。 */
    val initialDraft: String? = (handoff as? ChatHandoff.NotSent)?.text

    private val _restoredDraft = MutableStateFlow<String?>(null)

    /** 打开会话时从本地恢复的草稿（读到之后只给一次，输入框里已经有字时界面不覆盖）。 */
    val restoredDraft: StateFlow<String?> = _restoredDraft.asStateFlow()
    private var pendingDraft: Job? = null
    private var unsavedDraft: String? = null

    /** 这个会话的审批和提问（T10）。 */
    val attention = AttentionHandler(viewModelScope, responder, resolvedEvents)

    private val timeline = MutableStateFlow(ChatTimeline())
    private val outbox =
        MutableStateFlow(
            when (handoff) {
                // 第一条消息发送失败：显示成失败的气泡，重试沿用同一个 requestId。
                // 新会话里的用户消息都是这条，所以第一次读到历史时就按文字对应（可能其实已经送到了）
                is ChatHandoff.FailedFirstMessage ->
                    Outbox(items = listOf(LocalMessage(handoff.message, handoff.status)), seenUserIds = emptySet())
                else -> Outbox()
            },
        )
    private val flags = MutableStateFlow(Flags())

    init {
        if (initialDraft != null) {
            saveDraft(initialDraft)
        } else {
            viewModelScope.launch { _restoredDraft.value = storedDraft.first()?.takeIf { it.isNotBlank() } }
        }
    }

    private val workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    private var subscription: Job? = null
    private var reload: Job? = null
    private var pickerLoad: Job? = null

    private data class Flags(
        val loadingOlder: Boolean = false,
        val loadFailed: Boolean = false,
    )

    private val names = combine(hostName, workspaces) { host, list -> host to list }

    val state: StateFlow<ChatState> =
        combine(states, timeline, outbox, flags, attention.ui) { live, t, o, f, attentionUi ->
            val host = live[hostId]
            val session = host?.sessions?.get(sessionId)
            ChatState(
                session = session,
                link = host?.status ?: LinkStatus.Stopped,
                items = chatItems(t, o),
                loaded = t.loaded,
                hasMore = t.hasMore,
                loadingOlder = f.loadingOlder,
                loadFailed = f.loadFailed,
                attention = pendingAttention(host, session, sessionId).filter { attentionUi[it.attentionId]?.resolution == null },
                attentionUi = attentionUi,
                turns = t.turns,
                clockOffsetMillis = host?.clockOffsetMillis ?: 0,
            )
        }.combine(names) { base, (name, projects) ->
            base.copy(hostName = name, projectName = projectName(base.session, projects))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatState(null, LinkStatus.Stopped, emptyList(), false, false, false, false))

    init {
        // 出现在这个页面上的事项之后在别处被处理时，提示「已在其他地方处理」
        viewModelScope.launch { state.collect { s -> attention.shown(s.attention.map { it.attentionId }) } }
    }

    private val _notice = MutableStateFlow(if (handoff is ChatHandoff.NotSent) ChatNotice.SETUP_FAILED else null)
    val notice: StateFlow<ChatNotice?> = _notice.asStateFlow()

    private val _picker = MutableStateFlow<Picker>(Picker.Closed)
    val picker: StateFlow<Picker> = _picker.asStateFlow()

    /** 界面可见：订阅推送（插件每个会话按引用计数订阅，离开页面后取消）。 */
    fun onVisible() {
        loadWorkspaces()
        if (subscription?.isActive == true) return
        subscription =
            viewModelScope.launch {
                backend.events().collect { event ->
                    if (event is SessionEvent.Resync) reloadLatest() else updateTimeline { it.apply(event) }
                }
            }
    }

    fun onHidden() {
        subscription?.cancel()
        subscription = null
    }

    /** 读取历史失败后重试。 */
    fun retryLoad() {
        if (flags.value.loadFailed) reloadLatest()
    }

    /** 滚动到顶部：取更早的一页。 */
    fun loadOlder() {
        val t = timeline.value
        val cursor = t.cursor
        if (!t.loaded || !t.hasMore || cursor == null || t.reloading || flags.value.loadingOlder) return
        flags.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                val page = backend.messages(cursor)
                // 读取期间时间线被整体替换（重连后有断档）时，这一页已经接不上了
                updateTimeline { if (it.cursor == cursor) it.withOlder(page) else it }
            } catch (e: ApiException) {
                _notice.value = ChatNotice.LOAD_OLDER_FAILED
            } catch (e: OfflineException) {
                _notice.value = ChatNotice.OFFLINE
            } finally {
                flags.update { it.copy(loadingOlder = false) }
            }
        }
    }

    /**
     * 发消息。运行中一律插话（下一步就让模型看到），否则排队（空闲时就是马上开始）。用户确认过不提供「排队到本轮结束」的选择。
     * 返回 false 表示没有发送（内容为空、太长，或者现在不能发送）。
     */
    fun send(text: String): Boolean {
        val current = state.value
        if (text.isBlank() || text.length > OutgoingMessage.MAX_TEXT || !current.canSend) return false
        val mode = if (current.running) PromptMode.STEER else PromptMode.QUEUE
        val message = OutgoingMessage(text, mode)
        outbox.update { it.add(message) }
        deliver(message)
        return true
    }

    /**
     * 重试或重发：失败的「重试」和不确定的「重发」都只由用户点（落地方案 B3，**不自动重发**）。
     * 沿用同一个 requestId：10 分钟内插件据此去重，其实已经送到时不会发两次。
     */
    fun retrySend(requestId: String) {
        val local = outbox.value.find(requestId) ?: return
        if (local.status != OutgoingStatus.FAILED && local.status != OutgoingStatus.UNCERTAIN) return
        outbox.update { it.mark(requestId, OutgoingStatus.SENDING) }
        deliver(local.outgoing)
    }

    /**
     * 不确定是否送达时的「查看」：重新读取最新一页历史。消息其实已经到了，就按文字对应上，气泡下的那一行消失；
     * 还没看到时提示一声，由用户决定要不要重发。
     */
    fun checkSend(requestId: String) {
        if (outbox.value.find(requestId)?.status != OutgoingStatus.UNCERTAIN) return
        viewModelScope.launch {
            try {
                val page = backend.messages(null)
                updateTimeline { it.withLatest(page) }
                if (outbox.value.find(requestId) != null) _notice.value = ChatNotice.NOT_IN_SESSION
            } catch (_: ApiException) {
                _notice.value = ChatNotice.OFFLINE
            } catch (_: OfflineException) {
                _notice.value = ChatNotice.OFFLINE
            }
        }
    }

    /** 输入框里的字变了：停顿一下再存（落地方案 B3）。空白等于删掉草稿。 */
    fun draftChanged(text: String) {
        pendingDraft?.cancel()
        unsavedDraft = text
        pendingDraft =
            viewModelScope.launch {
                if (text.isNotBlank()) delay(DRAFT_SAVE_DELAY_MILLIS)
                flushDraft()
            }
    }

    private fun flushDraft() {
        unsavedDraft?.let(saveDraft)
        unsavedDraft = null
    }

    fun cancelRun() {
        viewModelScope.launch {
            try {
                backend.cancel()
            } catch (_: ApiException) {
                _notice.value = ChatNotice.CANCEL_FAILED
            } catch (_: OfflineException) {
                _notice.value = ChatNotice.OFFLINE
            }
        }
    }

    /** 点了模型：展开它的选项；已经展开时收起；展开着审批方式时换成模型。 */
    fun openModels() = openPicker(PickerKind.MODELS) { Picker.Models(backend.models()) }

    fun openPresets() = openPicker(PickerKind.PRESETS) { Picker.Presets(backend.permissionPresets()) }

    fun closePicker() {
        pickerLoad?.cancel()
        _picker.value = Picker.Closed
    }

    /** 切换模型。新的值通过 `session.updated` 反映到会话摘要上。 */
    fun chooseModel(model: ModelRef) = change { backend.setModel(model) }

    fun choosePreset(preset: String) = change { backend.setPermissionPreset(preset) }

    fun dismissNotice() {
        _notice.value = null
    }

    override fun onCleared() {
        onHidden()
        // 离开会话页时还没到存的时候：现在就存
        flushDraft()
    }

    /** 项目名只是顶栏的说明，读不到就算了（之后回到页面再试）。 */
    private fun loadWorkspaces() {
        if (workspaces.value.isNotEmpty()) return
        viewModelScope.launch {
            try {
                workspaces.value = backend.workspaces()
            } catch (_: ApiException) {
            } catch (_: OfflineException) {
            }
        }
    }

    private fun reloadLatest() {
        reload?.cancel()
        updateTimeline { it.startReload() }
        flags.update { it.copy(loadFailed = false) }
        reload =
            viewModelScope.launch {
                try {
                    val page = backend.messages(null)
                    updateTimeline { it.withLatest(page) }
                } catch (e: ApiException) {
                    failReload()
                } catch (e: OfflineException) {
                    failReload()
                }
            }
    }

    private fun failReload() {
        updateTimeline { it.reloadFailed() }
        flags.update { it.copy(loadFailed = true) }
    }

    /** 发一次，只发一次：失败或不确定时只改状态，从不在这里重发（落地方案 B3）。 */
    private fun deliver(message: OutgoingMessage) {
        viewModelScope.launch {
            val status =
                try {
                    backend.prompt(message)
                    OutgoingStatus.SENT
                } catch (e: ApiException) {
                    deliveryStatus(e)
                } catch (e: OfflineException) {
                    deliveryStatus(e)
                }
            outbox.update { box -> if (box.find(message.requestId) != null) box.mark(message.requestId, status) else box }
        }
    }

    private fun updateTimeline(change: (ChatTimeline) -> ChatTimeline) {
        val next = change(timeline.value)
        timeline.value = next
        outbox.update { it.reconcile(next) }
    }

    private fun openPicker(
        kind: PickerKind,
        load: suspend () -> Picker,
    ) {
        if (_picker.value.kind == kind) {
            closePicker()
            return
        }
        pickerLoad?.cancel()
        _picker.value = Picker.Loading(kind)
        pickerLoad =
            viewModelScope.launch {
                _picker.value =
                    try {
                        load()
                    } catch (_: ApiException) {
                        _notice.value = ChatNotice.CHANGE_FAILED
                        Picker.Closed
                    } catch (_: OfflineException) {
                        _notice.value = ChatNotice.OFFLINE
                        Picker.Closed
                    }
            }
    }

    private fun change(action: suspend () -> Unit) {
        closePicker()
        viewModelScope.launch {
            try {
                action()
            } catch (_: ApiException) {
                _notice.value = ChatNotice.CHANGE_FAILED
            } catch (_: OfflineException) {
                _notice.value = ChatNotice.OFFLINE
            }
        }
    }
}

/** 顶栏的项目名：会话所属的已登记项目；没有登记的用工作目录的最后一段。 */
internal fun projectName(
    session: SessionSummary?,
    workspaces: List<Workspace>,
): String? {
    if (session == null) return null
    session.workspaceId?.let { id ->
        workspaces
            .firstOrNull { it.workspaceId == id }
            ?.title
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    return session.cwd?.takeIf { it.isNotBlank() }?.let(::fileName)
}

/**
 * 这个会话的待处理事项：`sessionId` 是这个会话的，以及会话摘要的 `attention` 中列出的
 * （子代理发起的事项 `sessionId` 可能是子代理的 id，protocol.md 5.3）。
 */
internal fun pendingAttention(
    host: HostLiveState?,
    session: SessionSummary?,
    sessionId: String,
): List<Attention> {
    val listed = session?.attention?.mapTo(HashSet()) { it.attentionId }.orEmpty()
    return host
        ?.attention
        ?.values
        ?.filter { it.sessionId == sessionId || it.attentionId in listed }
        ?.sortedBy { it.createdAt }
        .orEmpty()
}

/** 正式消息、草稿、本地消息依次排列。key 重复时（例如草稿的 id 被重新使用）退回消息自己的 id。 */
internal fun chatItems(
    timeline: ChatTimeline,
    outbox: Outbox,
): List<ChatItem> {
    val used = HashSet<String>()
    val items = ArrayList<ChatItem>(timeline.messages.size + timeline.drafts.size + outbox.items.size)
    for (message in timeline.messages + timeline.drafts) {
        val preferred = timeline.keyOf(message)
        val key = if (used.add(preferred)) preferred else "${message.id}#${used.size}".also(used::add)
        items += ChatItem.Entry(key, message)
    }
    outbox.items.forEach { items += ChatItem.Local("local:${it.outgoing.requestId}", it) }
    return items
}

/** 输入框停顿这么久再存草稿。 */
private const val DRAFT_SAVE_DELAY_MILLIS = 400L
