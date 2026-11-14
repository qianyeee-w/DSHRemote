package io.github.qianyeeew.dshremote.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.ForkResult
import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.MessagePage
import io.github.qianyeeew.dshremote.data.api.ModelList
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.OutgoingImage
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PermissionPresetList
import io.github.qianyeeew.dshremote.data.api.PromptMode
import io.github.qianyeeew.dshremote.data.api.SessionPlan
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.SessionUsage
import io.github.qianyeeew.dshremote.data.api.Turn
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.chat.ChatTimeline
import io.github.qianyeeew.dshremote.data.chat.LocalMessage
import io.github.qianyeeew.dshremote.data.chat.Outbox
import io.github.qianyeeew.dshremote.data.chat.OutgoingStatus
import io.github.qianyeeew.dshremote.data.chat.deliveryStatus
import io.github.qianyeeew.dshremote.data.image.ImageSupport
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
import java.util.UUID

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

    /** 用量与上下文（落地方案 B4）。旧插件没有这个接口。 */
    suspend fun usage(): SessionUsage = SessionUsage()

    /** 计划（落地方案 B5）。旧插件没有这个接口。 */
    suspend fun plan(): SessionPlan = SessionPlan()

    /** 从第 [beforeTurn] 轮之前另开一个对话（落地方案 C1）。重试时传同一个 [requestId]。 */
    suspend fun fork(
        beforeTurn: Int,
        requestId: String,
    ): ForkResult = throw OfflineException()
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

    override suspend fun usage(): SessionUsage = api().usage(sessionId)

    override suspend fun plan(): SessionPlan = api().plan(sessionId)

    override suspend fun fork(
        beforeTurn: Int,
        requestId: String,
    ): ForkResult = api().fork(sessionId, requestId, beforeTurn)

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
    /** 用量与上下文（落地方案 B4）。还没读到、读不到或旧插件时为 null。 */
    val usage: SessionUsage? = null,
    /** 计划（落地方案 B5）：目标和这一轮的任务清单。还没读到、读不到或旧插件时为 null。 */
    val plan: SessionPlan? = null,
    /** 会话里的图片从哪台电脑、哪个会话读（落地方案 C2）。 */
    val hostId: String = "",
    val sessionId: String = "",
    /** 输入框里已经加上、还没发出的图片（落地方案 C2）。 */
    val images: List<OutgoingImage> = emptyList(),
    /** 还在压缩的图片有几张。 */
    val preparingImages: Int = 0,
    /** 现在能不能加图片。 */
    val imageSupport: ImageSupport = ImageSupport.CHECKING,
) {
    /** 还能再加几张图片。 */
    val imageSlots: Int get() = (OutgoingMessage.MAX_IMAGES - images.size - preparingImages).coerceAtLeast(0)

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

    /** 另开的对话建好了，但没有归到原来的项目（落地方案 C1）。 */
    FORK_NOT_IN_PROJECT,

    /** 当前模型看不了图片：消息没发出，文字和图片放回输入框（落地方案 C2）。 */
    IMAGES_NOT_SUPPORTED,

    /** DSH 说图片坏了：消息没发出，放回输入框。 */
    IMAGE_INVALID,

    /** 有的图片读不出来（不是图片、文件没了），没有加上。 */
    IMAGE_UNREADABLE,

    /** 旧插件不能发图片。 */
    IMAGES_OLD_PLUGIN,

    /** 还没确认能不能发图片（没读到模型列表）。 */
    IMAGES_CHECKING,
}

/** 另开对话的进度（落地方案 C1）。 */
sealed interface ForkState {
    data object Idle : ForkState

    data class Running(
        val turn: Int,
    ) : ForkState

    /** 没成功，可以重试：重试沿用同一个 requestId，插件去重，不会分出两个。 */
    data class Failed(
        val turn: Int,
    ) : ForkState

    /** 新会话建好了：界面进入它（[text] 放进它的输入框），然后调 [ChatViewModel.consumeFork]。 */
    data class Done(
        val sessionId: String,
        val text: String,
        val workspaceAttached: Boolean,
    ) : ForkState {
        /** 不输出消息内容。 */
        override fun toString(): String = "Done($sessionId, ${text.length} 个字符, $workspaceAttached)"
    }
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
    private val hostId = hostId
    private val sessionId = sessionId

    /** 模型或审批方式没设置成功、消息还没发时，放回输入框的文字。 */
    val initialDraft: String? =
        when (handoff) {
            is ChatHandoff.NotSent -> handoff.text.takeIf { it.isNotBlank() }
            is ChatHandoff.Forked -> handoff.text.takeIf { it.isNotBlank() }
            else -> null
        }

    /** 输入框里的图片（只在内存里，用户确认）和模型列表（判断能不能加图片）。 */
    private data class Composer(
        val images: List<OutgoingImage> = emptyList(),
        val preparing: Int = 0,
        val models: ModelList? = null,
    )

    private val composer = MutableStateFlow(Composer(images = (handoff as? ChatHandoff.NotSent)?.images.orEmpty()))
    private var modelsLoad: Job? = null

    private val _returnedText = MutableStateFlow<String?>(null)

    /** 插件拒绝了带图的消息，要放回输入框的文字（界面放回后调 [consumeReturnedText]）。 */
    val returnedText: StateFlow<String?> = _returnedText.asStateFlow()

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
    private var visibleCount = 0
    private var reload: Job? = null
    private var pickerLoad: Job? = null

    private data class Flags(
        val loadingOlder: Boolean = false,
        val loadFailed: Boolean = false,
    )

    private val names = combine(hostName, workspaces) { host, list -> host to list }

    /** 最近一次读到的用量。读失败时保留上一次的，不清空。 */
    private val usage = MutableStateFlow<SessionUsage?>(null)

    /** 最近一次读到或推送来的计划。 */
    private val plan = MutableStateFlow<SessionPlan?>(null)

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
        }.combine(usage) { base, u -> base.copy(usage = u) }
            .combine(plan) { base, p -> base.copy(plan = p) }
            .combine(composer) { base, c ->
                base.copy(
                    hostId = hostId,
                    sessionId = sessionId,
                    images = c.images,
                    preparingImages = c.preparing,
                    imageSupport = ImageSupport.of(c.models, base.session?.model),
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatState(null, LinkStatus.Stopped, emptyList(), false, false, false, false))

    init {
        // 出现在这个页面上的事项之后在别处被处理时，提示「已在其他地方处理」
        viewModelScope.launch { state.collect { s -> attention.shown(s.attention.map { it.attentionId }) } }
    }

    private val _notice =
        MutableStateFlow(
            when (handoff) {
                is ChatHandoff.NotSent -> handoff.reason
                is ChatHandoff.Forked -> if (handoff.workspaceAttached) null else ChatNotice.FORK_NOT_IN_PROJECT
                else -> null
            },
        )

    private val _fork = MutableStateFlow<ForkState>(ForkState.Idle)

    /** 另开对话的进度。 */
    val fork: StateFlow<ForkState> = _fork.asStateFlow()

    /** 每一轮另开对话用的 requestId：失败后重试沿用同一个。 */
    private val forkRequests = HashMap<Int, String>()
    val notice: StateFlow<ChatNotice?> = _notice.asStateFlow()

    private val _picker = MutableStateFlow<Picker>(Picker.Closed)
    val picker: StateFlow<Picker> = _picker.asStateFlow()

    /** 界面可见：订阅推送（插件每个会话按引用计数订阅，离开页面后取消）。 */
    fun onVisible() {
        visibleCount++
        loadWorkspaces()
        refreshUsage()
        loadModels()
        if (subscription?.isActive == true) return
        subscription =
            viewModelScope.launch {
                backend.events().collect { event ->
                    when (event) {
                        is SessionEvent.Resync -> {
                            reloadLatest()
                            // 重连后插件可能换了版本，没读到过模型列表时也再读一次
                            loadModels(force = true)
                            // 断线期间可能错过了 plan.updated
                            refreshPlan()
                        }
                        is SessionEvent.PlanUpdated -> plan.value = event.plan
                        else -> updateTimeline { it.apply(event) }
                    }
                    // 一轮结束后用量和上下文都变了（不轮询，protocol.md「用量」）
                    if (event is SessionEvent.TurnUpdated && event.turn.endedAt != null) refreshUsage()
                }
            }
    }

    /**
     * 界面不可见。会话页和计划页共用这个视图模型，各自开始时调 [onVisible]、停止时调 [onHidden]；
     * 从会话页进计划页时计划页先开始、会话页后停止，所以按次数算，都不可见了才取消订阅。
     */
    fun onHidden() {
        visibleCount = (visibleCount - 1).coerceAtLeast(0)
        if (visibleCount > 0) return
        subscription?.cancel()
        subscription = null
    }

    /** 读计划（打开会话、重连后）。读不到时保留上一次的。 */
    private fun refreshPlan() {
        viewModelScope.launch {
            try {
                plan.value = backend.plan()
            } catch (_: ApiException) {
            } catch (_: OfflineException) {
            }
        }
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
        val images = current.images
        if ((text.isBlank() && images.isEmpty()) || text.length > OutgoingMessage.MAX_TEXT || !current.canSend) return false
        // 图片还在压缩时等一等；加上图片之后模型换成了只读文字的（或者插件换成了旧的），先说明，不发
        if (current.preparingImages > 0) return false
        if (images.isNotEmpty() && !current.imageSupport.canAdd) {
            _notice.value = imageNotice(current.imageSupport)
            return false
        }
        val mode = if (current.running) PromptMode.STEER else PromptMode.QUEUE
        val message = OutgoingMessage(text, mode, images)
        composer.update { it.copy(images = emptyList()) }
        outbox.update { it.add(message) }
        deliver(message)
        return true
    }

    /**
     * 点了加号但现在不能加图片：说明原因（设计规格 6.3「看不了图时加号不可用并说明原因」）。
     * 还没读到模型列表时顺便再读一次。
     */
    fun explainImages() {
        val support = state.value.imageSupport
        if (support == ImageSupport.CHECKING) loadModels(force = true)
        _notice.value = imageNotice(support)
    }

    /** 加上用户选的图片（在后台压缩）；超过张数的部分不加。读不出来的图片提示一声。 */
    fun addImages(sources: List<suspend () -> OutgoingImage?>) {
        val take = sources.take(state.value.imageSlots)
        if (take.isEmpty() || !state.value.imageSupport.canAdd) return
        composer.update { it.copy(preparing = it.preparing + take.size) }
        viewModelScope.launch {
            var unreadable = false
            for (source in take) {
                val image =
                    try {
                        source()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                if (image == null) unreadable = true
                composer.update { c ->
                    c.copy(
                        preparing = c.preparing - 1,
                        images = if (image != null && c.images.size < OutgoingMessage.MAX_IMAGES) c.images + image else c.images,
                    )
                }
            }
            if (unreadable) _notice.value = ChatNotice.IMAGE_UNREADABLE
        }
    }

    fun removeImage(key: String) = composer.update { c -> c.copy(images = c.images.filterNot { it.key == key }) }

    fun consumeReturnedText() {
        _returnedText.value = null
    }

    /** 读模型列表，判断能不能加图片。已经读到时不再读，除非 [force]。读不到时保持「还没确认」。 */
    private fun loadModels(force: Boolean = false) {
        if ((composer.value.models != null && !force) || modelsLoad?.isActive == true) return
        modelsLoad =
            viewModelScope.launch {
                try {
                    val list = backend.models()
                    composer.update { it.copy(models = list) }
                } catch (_: ApiException) {
                } catch (_: OfflineException) {
                }
            }
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

    /** 读用量（打开会话、一轮结束、用户长按发送键时）。读不到时保留上一次的，不显示成 0。 */
    fun refreshUsage() {
        viewModelScope.launch {
            try {
                usage.value = backend.usage()
            } catch (_: ApiException) {
            } catch (_: OfflineException) {
            }
        }
    }

    /**
     * 从第 [turn] 轮之前另开一个对话，新对话带着这条消息之前的内容（设计规格 7.13）。[text] 是这条消息的文字，
     * 成功后放进新对话的输入框（用户确认）。只发一次；失败后由用户点重试。
     */
    fun startFork(
        turn: Int,
        text: String,
    ) {
        if (_fork.value is ForkState.Running || turn < 1) return
        val requestId = forkRequests.getOrPut(turn) { UUID.randomUUID().toString() }
        _fork.value = ForkState.Running(turn)
        viewModelScope.launch {
            _fork.value =
                try {
                    val result = backend.fork(turn, requestId)
                    forkRequests.remove(turn)
                    ForkState.Done(result.sessionId, text, result.workspaceAttached)
                } catch (_: ApiException) {
                    ForkState.Failed(turn)
                } catch (_: OfflineException) {
                    ForkState.Failed(turn)
                }
        }
    }

    /** 收起确认面板，或者界面已经进入了新会话。进行中时不收起。 */
    fun consumeFork() {
        if (_fork.value !is ForkState.Running) _fork.value = ForkState.Idle
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
    fun openModels() =
        openPicker(PickerKind.MODELS) {
            val list = backend.models()
            composer.update { it.copy(models = list) }
            Picker.Models(list)
        }

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
        visibleCount = 0
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
                    if (imageRejection(e) != null) {
                        giveBack(message, e)
                        return@launch
                    }
                    deliveryStatus(e)
                } catch (e: OfflineException) {
                    deliveryStatus(e)
                }
            outbox.update { box -> if (box.find(message.requestId) != null) box.mark(message.requestId, status) else box }
        }
    }

    /**
     * 插件因为图片拒绝了这条消息（模型看不了图、图片坏了）：消息肯定没发出，气泡去掉，文字和图片放回输入框并说明原因。
     * 模型看不了图时记下来，加号随之不可用。
     */
    private fun giveBack(
        message: OutgoingMessage,
        error: ApiException,
    ) {
        val notice = imageRejection(error) ?: return
        if (outbox.value.find(message.requestId) == null) return
        outbox.update { it.remove(message.requestId) }
        if (message.text.isNotBlank()) _returnedText.value = message.text
        composer.update { c ->
            // 会话还没说用哪个模型时，用的是默认模型
            val model = state.value.session?.model ?: c.models?.default
            c.copy(
                images = (message.images + c.images).distinctBy { it.key }.take(OutgoingMessage.MAX_IMAGES),
                models = if (notice == ChatNotice.IMAGES_NOT_SUPPORTED && model != null) c.models?.textOnly(model) else c.models,
            )
        }
        _notice.value = notice
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

/** 插件因为图片拒绝了消息时对应的提示；不是这两种错误时为 null。 */
internal fun imageRejection(error: Exception): ChatNotice? =
    when ((error as? ApiException.Http)?.code) {
        ErrorCode.IMAGES_NOT_SUPPORTED -> ChatNotice.IMAGES_NOT_SUPPORTED
        ErrorCode.IMAGE_INVALID -> ChatNotice.IMAGE_INVALID
        else -> null
    }

/** 现在不能加图片的原因。 */
internal fun imageNotice(support: ImageSupport): ChatNotice =
    when (support) {
        ImageSupport.OLD_PLUGIN -> ChatNotice.IMAGES_OLD_PLUGIN
        ImageSupport.TEXT_ONLY_MODEL -> ChatNotice.IMAGES_NOT_SUPPORTED
        else -> ChatNotice.IMAGES_CHECKING
    }
