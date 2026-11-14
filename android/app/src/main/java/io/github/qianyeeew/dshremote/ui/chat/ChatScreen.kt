package io.github.qianyeeew.dshremote.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.image.ImageRef
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.attention.AttentionCard
import io.github.qianyeeew.dshremote.ui.attention.AttentionCardActions
import io.github.qianyeeew.dshremote.ui.attention.AttentionUi
import io.github.qianyeeew.dshremote.ui.attention.ComposerAttention
import io.github.qianyeeew.dshremote.ui.attention.Resolution
import io.github.qianyeeew.dshremote.ui.attention.composerAttention
import io.github.qianyeeew.dshremote.ui.common.DesignBackBar
import io.github.qianyeeew.dshremote.ui.common.PillButton
import io.github.qianyeeew.dshremote.ui.common.StatusMark
import io.github.qianyeeew.dshremote.ui.common.StatusMarker
import io.github.qianyeeew.dshremote.ui.common.sessionTitleText
import io.github.qianyeeew.dshremote.ui.hosts.needsAttention
import io.github.qianyeeew.dshremote.ui.hosts.statusDetail
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 页面上的操作。 */
class ChatActions(
    val onBack: () -> Unit = {},
    val onSend: (text: String) -> Boolean = { false },
    val onRetrySend: (requestId: String) -> Unit = {},
    /** 不确定是否送达时的「查看」。 */
    val onCheckSend: (requestId: String) -> Unit = {},
    /** 输入框里的字变了（存草稿）。 */
    val onDraftChange: (String) -> Unit = {},
    /** 用户要看用量：重新读一次。 */
    val onRefreshUsage: () -> Unit = {},
    /** 点了计划条：打开计划页。 */
    val onOpenPlan: () -> Unit = {},
    /** 确认另开对话：从第 turn 轮之前，text 是那条消息的文字（落地方案 C1）。 */
    val onFork: (turn: Int, text: String) -> Unit = { _, _ -> },
    /** 收起另开对话的确认面板。 */
    val onCloseFork: () -> Unit = {},
    val onCancelRun: () -> Unit = {},
    val onLoadOlder: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onOpenModels: () -> Unit = {},
    val onOpenPresets: () -> Unit = {},
    val onChooseModel: (ModelRef) -> Unit = {},
    val onChoosePreset: (String) -> Unit = {},
    val onClosePicker: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    /** 用户选了图片（落地方案 C2）：在后台压缩后加进输入框。 */
    val onAddImages: (List<Uri>) -> Unit = {},
    val onRemoveImage: (key: String) -> Unit = {},
    /** 点了不可用的加号：说明原因。 */
    val onImagesUnavailable: () -> Unit = {},
    /** 插件拒绝了带图的消息，文字已经放回输入框。 */
    val onReturnedTextShown: () -> Unit = {},
)

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenPlan: () -> Unit = {},
    /** 另开的对话建好了：进入它（text 放进它的输入框）。 */
    onForked: (ForkState.Done) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val attentionNotice by viewModel.attention.notice.collectAsStateWithLifecycle()
    val restoredDraft by viewModel.restoredDraft.collectAsStateWithLifecycle()
    val returnedText by viewModel.returnedText.collectAsStateWithLifecycle()
    val fork by viewModel.fork.collectAsStateWithLifecycle()
    val preparer = LocalImagePreparer.current
    LaunchedEffect(fork) {
        (fork as? ForkState.Done)?.let {
            onForked(it)
            viewModel.consumeFork()
        }
    }
    // 只在页面可见时订阅这个会话（插件按引用计数订阅，m2-android.md 3.3「订阅」）
    LifecycleStartEffect(viewModel) {
        viewModel.onVisible()
        onStopOrDispose { viewModel.onHidden() }
    }
    ChatContent(
        state = state,
        notice = notice,
        picker = picker,
        actions =
            ChatActions(
                onBack = onBack,
                onSend = viewModel::send,
                onRetrySend = viewModel::retrySend,
                onCheckSend = viewModel::checkSend,
                onDraftChange = viewModel::draftChanged,
                onRefreshUsage = viewModel::refreshUsage,
                onOpenPlan = onOpenPlan,
                onFork = viewModel::startFork,
                onCloseFork = viewModel::consumeFork,
                onCancelRun = viewModel::cancelRun,
                onLoadOlder = viewModel::loadOlder,
                onRetryLoad = viewModel::retryLoad,
                onOpenModels = viewModel::openModels,
                onOpenPresets = viewModel::openPresets,
                onChooseModel = viewModel::chooseModel,
                onChoosePreset = viewModel::choosePreset,
                onClosePicker = viewModel::closePicker,
                onDismissNotice = viewModel::dismissNotice,
                onAddImages = { uris -> viewModel.addImages(uris.map { uri -> suspend { preparer?.prepare(uri) } }) },
                onRemoveImage = viewModel::removeImage,
                onImagesUnavailable = viewModel::explainImages,
                onReturnedTextShown = viewModel::consumeReturnedText,
            ),
        attentionActions = AttentionCardActions.of(viewModel.attention),
        attentionNotice = attentionNotice,
        onDismissAttentionNotice = viewModel.attention::dismissNotice,
        initialDraft = viewModel.initialDraft,
        restoredDraft = restoredDraft,
        returnedText = returnedText,
        fork = fork,
    )
}

@Composable
fun ChatContent(
    state: ChatState,
    notice: ChatNotice?,
    picker: Picker,
    actions: ChatActions,
    attentionActions: AttentionCardActions = AttentionCardActions(),
    attentionNotice: Resolution? = null,
    onDismissAttentionNotice: () -> Unit = {},
    initialDraft: String? = null,
    restoredDraft: String? = null,
    returnedText: String? = null,
    fork: ForkState = ForkState.Idle,
) {
    val snackbar = remember { SnackbarHostState() }
    val elsewhere = stringResource(R.string.attention_elsewhere)
    LaunchedEffect(attentionNotice) {
        if (attentionNotice == Resolution.ELSEWHERE) {
            snackbar.showSnackbar(elsewhere)
            onDismissAttentionNotice()
        }
    }
    val scope = rememberCoroutineScope()
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val copied = stringResource(R.string.chat_copied)
    val noticeText = notice?.let { stringResource(noticeText(it)) }
    LaunchedEffect(notice) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            actions.onDismissNotice()
        }
    }
    // 点链接先确认（m2-android.md 3.4），Markdown 库通过 LocalUriHandler 打开链接
    val confirmingHandler = remember { ConfirmingUriHandler { pendingLink = it } }
    // 长按一条自己的消息（设计规格 7.13）：先是「复制」「另开对话」，选了另开就是确认面板
    var actionsFor by remember { mutableStateOf<ChatBlock.User?>(null) }
    var forking by remember { mutableStateOf<ChatBlock.User?>(null) }
    val target = forking ?: actionsFor
    val closeActions = {
        actionsFor = null
        if (forking != null) {
            forking = null
            actions.onCloseFork()
        }
    }
    BackHandler(enabled = target != null) { closeActions() }
    // 新会话建好了（ChatScreen 会进入它）：收起确认面板，返回时不再看到它
    LaunchedEffect(fork) { if (fork is ForkState.Done) forking = null }
    val clipboard = LocalClipboardManager.current

    Scaffold(topBar = { ChatTopBar(state, actions) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        // 输入区这一栏的实际高度（已经扣掉键盘），面板最多占一半，其余都给消息列表。
        // consumeWindowInsets：Scaffold 的内边距里已经有导航条，不让 imePadding 再算一次（否则输入框和键盘之间空一截）
        BoxWithConstraints(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            val panelMax = maxHeight / 2
            Column(Modifier.fillMaxSize()) {
                // 计划条（设计规格 6.9）：会话顶栏下面
                PlanStrip(state.plan, actions.onOpenPlan)
                if (!state.online) LinkBanner(state)
                // 等待处理、但事项还没到（或者不在这台手机能看到的范围）时，至少提示一下
                if (state.session?.status == SessionStatus.WAITING && state.attention.isEmpty()) WaitingBanner()
                CompositionLocalProvider(LocalUriHandler provides confirmingHandler) {
                    // Android 13 起系统会自己显示复制成功的提示，不再重复提示
                    val onCopied = {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
                    }
                    MessageList(
                        state,
                        actions,
                        onCopied = { onCopied() },
                        modifier = Modifier.weight(1f),
                        highlightKey = target?.key,
                        onLongPress = { actionsFor = it },
                        onDismissTarget = closeActions,
                    )
                }
                val choosing = actionsFor
                val confirming = forking
                when {
                    confirming != null ->
                        ForkConfirmPanel(
                            fork,
                            onCancel = closeActions,
                            onConfirm = { confirming.turn?.let { actions.onFork(it, confirming.text) } },
                        )
                    choosing != null ->
                        MessageActionsPanel(
                            canFork = choosing.turn != null && choosing.local == null,
                            online = state.online,
                            onCopy = {
                                clipboard.setText(AnnotatedString(choosing.text))
                                actionsFor = null
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
                            },
                            onFork = {
                                forking = choosing
                                actionsFor = null
                            },
                        )
                    else -> {
                        // 不能和列表各用一个权重：面板用不完自己那一半时，剩下的空间会空着，输入框就不贴底了
                        AttentionPanel(state, attentionActions, Modifier.heightIn(max = panelMax))
                        ChatComposer(
                            state,
                            actions,
                            picker = picker,
                            initialText = initialDraft.orEmpty(),
                            restoredDraft = restoredDraft,
                            returnedText = returnedText,
                            attention = if (state.online) composerAttention(state.attention) else ComposerAttention.None,
                            onAnswer = attentionActions.onAnswerWithCustom,
                        )
                    }
                }
            }
        }
    }

    pendingLink?.let { link -> LinkDialog(link, onDismiss = { pendingLink = null }) }
}

/** 顶栏（设计规格 7.2）：返回、会话名，下面一行灰字「电脑名 · 项目名 · 审批方式」。没有「更多」按钮。 */
@Composable
private fun ChatTopBar(
    state: ChatState,
    actions: ChatActions,
) {
    val subtitle =
        listOfNotNull(
            state.hostName,
            state.projectName,
            state.session?.permissionPreset?.let { permissionLabel(it) },
        ).joinToString(" · ")
    DesignBackBar(actions.onBack, sessionTitleText(state.session?.title), subtitle)
}

/** 最新的消息在底部（reverseLayout），滚到顶部附近时取更早的一页。 */
@Composable
private fun MessageList(
    state: ChatState,
    actions: ChatActions,
    onCopied: () -> Unit,
    modifier: Modifier,
    /** 正在对它操作的那条消息：加描边，其余内容变淡，点列表任何地方收起（设计规格 7.13）。 */
    highlightKey: String? = null,
    onLongPress: (ChatBlock.User) -> Unit = {},
    onDismissTarget: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val blocks =
        remember(
            state.items,
            state.turns,
            state.hostId,
            state.sessionId,
        ) { chatBlocks(state.items, state.turns, state.hostId, state.sessionId) }
    // 点了气泡里的图片：全屏看
    var viewing by remember { mutableStateOf<ImageRef?>(null) }
    viewing?.let { ImageViewer(it, onDismiss = { viewing = null }) }
    val reversed = remember(blocks) { blocks.asReversed() }
    // 停在底部时跟随新消息。「是否停在底部」只在用户自己滚动之后更新：新消息连续到达时，
    // 列表会把原来的那一项留在原位，不能拿那一刻的位置来判断
    var stickToBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                stickToBottom = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < STICK_TOLERANCE_PX
            }
        }
    }
    val newestKey = reversed.firstOrNull()?.key
    LaunchedEffect(newestKey, reversed.size) {
        if (newestKey != null && stickToBottom) {
            // 等这一帧排版完成（新的一项已经在列表里）再滚动
            withFrameNanos { }
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val last =
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - LOAD_OLDER_THRESHOLD
        }.distinctUntilChanged()
            .collect { nearTop -> if (nearTop) actions.onLoadOlder() }
    }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier =
            modifier.fillMaxWidth().testTag("chat-message-list").pointerInput(focusManager) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    focusManager.clearFocus()
                }
            },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Bottom),
    ) {
        if (state.unconfirmed) {
            item(key = "unconfirmed-line") { UnconfirmedLine() }
        } else if (state.running) {
            item(key = "running-line") {
                RunningLine(
                    currentStep(blocks),
                    replying = blocks.lastOrNull() is ChatBlock.Reply,
                    startedAt = state.runningTurn?.startedAt,
                    clockOffsetMillis = state.clockOffsetMillis,
                )
            }
        }
        items(reversed, key = { it.key }, contentType = { it::class }) { block ->
            val faded = highlightKey != null && block.key != highlightKey
            Box(Modifier.alpha(if (faded) FADED_ALPHA else 1f)) {
                when (block) {
                    is ChatBlock.User ->
                        UserBubble(
                            block.text,
                            block.local,
                            images = block.images,
                            onOpenImage = { viewing = it },
                            onCheck = { block.local?.let { actions.onCheckSend(it.outgoing.requestId) } },
                            onLongPress = { onLongPress(block) },
                            highlighted = block.key == highlightKey,
                        ) { block.local?.let { actions.onRetrySend(it.outgoing.requestId) } }
                    is ChatBlock.Reply -> MarkdownText(block.text, onCopied)
                    is ChatBlock.Steps -> StepsView(block)
                    is ChatBlock.TurnEnd -> TurnEndLine(block.turn)
                }
                // 变淡的内容点了只是收起，不触发它自己的操作
                if (faded) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable(interactionSource = null, indication = null, onClick = onDismissTarget),
                    )
                }
            }
        }
        item(key = "history-header") { HistoryHeader(state, actions) }
    }
}

/** 列表最上方：读取中、读取失败、还有更早的消息时的进度，或者空会话的提示。 */
@Composable
private fun HistoryHeader(
    state: ChatState,
    actions: ChatActions,
) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        when {
            state.loadFailed && !state.loaded ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.chat_load_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = DesignTheme.colors.danger,
                    )
                    PillButton(stringResource(R.string.action_retry), actions.onRetryLoad)
                }
            !state.loaded ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusMarker(StatusMark.RUNNING)
                    Text(
                        stringResource(R.string.chat_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = DesignTheme.colors.textSecondary,
                    )
                }
            state.hasMore || state.loadingOlder -> StatusMarker(StatusMark.RUNNING)
            state.items.isEmpty() ->
                Text(
                    stringResource(R.string.chat_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = DesignTheme.colors.textSecondary,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
        }
    }
}

/** 连接有问题时，顶栏下面一行字说明（状态正常时不显示，设计原则 2）。 */
@Composable
private fun LinkBanner(state: ChatState) {
    Text(
        statusDetail(state.link),
        style = MaterialTheme.typography.bodySmall,
        color = if (state.link.needsAttention) DesignTheme.colors.danger else DesignTheme.colors.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("chat-link-banner"),
    )
}

/**
 * 这个会话的审批和提问，压在输入框上方（设计规格 6.8），面板上面仍然能看到 DSH 刚说的话。
 * 事项多或者很长时可以滚动。在别处处理掉的事项由视图模型过滤掉，面板随之收起。
 */
@Composable
private fun AttentionPanel(
    state: ChatState,
    actions: AttentionCardActions,
    modifier: Modifier,
) {
    if (state.attention.isEmpty() || !state.online) return
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp)
            .testTag("attention-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.attention.forEach { attention ->
            AttentionCard(attention, state.attentionUi[attention.attentionId] ?: AttentionUi(), actions, cwd = state.session?.cwd)
        }
    }
}

/** 会话在等待处理，但还没有可以显示的事项。 */
@Composable
private fun WaitingBanner() {
    Surface(color = DesignTheme.colors.panel, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.chat_waiting),
            color = DesignTheme.colors.waitingText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** 确认后才用系统浏览器打开，只接受 http 和 https。 */
@Composable
private fun LinkDialog(
    link: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uri = remember(link) { link.toUri() }
    val supported = uri.scheme?.lowercase() in setOf("http", "https")
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(link, style = MaterialTheme.typography.bodyMedium)
                if (!supported) Text(stringResource(R.string.chat_link_unsupported), color = MaterialTheme.colorScheme.error)
                if (failed) Text(stringResource(R.string.chat_link_no_browser), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (supported) {
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                        onDismiss()
                    } catch (_: ActivityNotFoundException) {
                        failed = true
                    }
                }) { Text(stringResource(R.string.chat_link_open)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private class ConfirmingUriHandler(
    private val onRequest: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) = onRequest(uri)
}

internal fun noticeText(notice: ChatNotice): Int =
    when (notice) {
        ChatNotice.OFFLINE -> R.string.chat_notice_offline
        ChatNotice.CANCEL_FAILED -> R.string.chat_notice_cancel_failed
        ChatNotice.LOAD_OLDER_FAILED -> R.string.chat_notice_older_failed
        ChatNotice.CHANGE_FAILED -> R.string.chat_notice_change_failed
        ChatNotice.SETUP_FAILED -> R.string.chat_notice_setup_failed
        ChatNotice.NOT_IN_SESSION -> R.string.chat_notice_not_in_session
        ChatNotice.FORK_NOT_IN_PROJECT -> R.string.chat_notice_fork_not_in_project
        ChatNotice.IMAGES_NOT_SUPPORTED -> R.string.chat_notice_images_not_supported
        ChatNotice.IMAGE_INVALID -> R.string.chat_notice_image_invalid
        ChatNotice.IMAGE_UNREADABLE -> R.string.chat_notice_image_unreadable
        ChatNotice.IMAGES_OLD_PLUGIN -> R.string.chat_notice_images_old_plugin
        ChatNotice.IMAGES_CHECKING -> R.string.chat_notice_images_checking
    }

/** 对一条消息操作时，其余内容的透明度（设计规格 7.13「其余内容变淡」）。 */
private const val FADED_ALPHA = 0.35f

/** 离底部不到这么多像素也算停在底部。 */
private const val STICK_TOLERANCE_PX = 48

/** 离顶部还剩几项时开始取更早的一页。 */
private const val LOAD_OLDER_THRESHOLD = 3
