package io.github.qianyeeew.dshremote.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.SessionStatus
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
    val onCancelRun: () -> Unit = {},
    val onLoadOlder: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onOpenModels: () -> Unit = {},
    val onOpenPresets: () -> Unit = {},
    val onChooseModel: (ModelRef) -> Unit = {},
    val onChoosePreset: (String) -> Unit = {},
    val onClosePicker: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val attentionNotice by viewModel.attention.notice.collectAsStateWithLifecycle()
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
                onCancelRun = viewModel::cancelRun,
                onLoadOlder = viewModel::loadOlder,
                onRetryLoad = viewModel::retryLoad,
                onOpenModels = viewModel::openModels,
                onOpenPresets = viewModel::openPresets,
                onChooseModel = viewModel::chooseModel,
                onChoosePreset = viewModel::choosePreset,
                onClosePicker = viewModel::closePicker,
                onDismissNotice = viewModel::dismissNotice,
            ),
        attentionActions = AttentionCardActions.of(viewModel.attention),
        attentionNotice = attentionNotice,
        onDismissAttentionNotice = viewModel.attention::dismissNotice,
        initialDraft = viewModel.initialDraft,
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

    Scaffold(topBar = { ChatTopBar(state, actions) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        // 输入区这一栏的实际高度（已经扣掉键盘），面板最多占一半，其余都给消息列表
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val panelMax = maxHeight / 2
            Column(Modifier.fillMaxSize()) {
                if (!state.online) LinkBanner(state)
                // 等待处理、但事项还没到（或者不在这台手机能看到的范围）时，至少提示一下
                if (state.session?.status == SessionStatus.WAITING && state.attention.isEmpty()) WaitingBanner()
                CompositionLocalProvider(LocalUriHandler provides confirmingHandler) {
                    // Android 13 起系统会自己显示复制成功的提示，不再重复提示
                    val onCopied = {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
                    }
                    MessageList(state, actions, onCopied = { onCopied() }, modifier = Modifier.weight(1f))
                }
                // 不能和列表各用一个权重：面板用不完自己那一半时，剩下的空间会空着，输入框就不贴底了
                AttentionPanel(state, attentionActions, Modifier.heightIn(max = panelMax))
                ChatComposer(
                    state,
                    actions,
                    pickerOpen = picker != Picker.Closed,
                    initialText = initialDraft.orEmpty(),
                    attention = if (state.online) composerAttention(state.attention) else ComposerAttention.None,
                    onAnswer = attentionActions.onAnswerWithCustom,
                )
            }
        }
    }

    pendingLink?.let { link -> LinkDialog(link, onDismiss = { pendingLink = null }) }
    PickerDialog(picker, state, actions)
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
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val blocks = remember(state.items, state.turns) { chatBlocks(state.items, state.turns) }
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
            when (block) {
                is ChatBlock.User -> UserBubble(block.text, block.local) { block.local?.let { actions.onRetrySend(it.outgoing.requestId) } }
                is ChatBlock.Reply -> MarkdownText(block.text, onCopied)
                is ChatBlock.Steps -> StepsView(block)
                is ChatBlock.TurnEnd -> TurnEndLine(block.turn)
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

@Composable
private fun PickerDialog(
    picker: Picker,
    state: ChatState,
    actions: ChatActions,
) {
    if (picker == Picker.Closed) return
    AlertDialog(
        onDismissRequest = actions.onClosePicker,
        title = {
            Text(stringResource(if (picker is Picker.Presets) R.string.chat_picker_preset else R.string.chat_picker_model))
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when (picker) {
                    Picker.Closed -> Unit
                    Picker.Loading ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatusMarker(StatusMark.RUNNING)
                            Text(stringResource(R.string.chat_picker_loading))
                        }
                    is Picker.Models -> {
                        if (picker.list.models.isEmpty()) Text(stringResource(R.string.chat_picker_empty))
                        val current = state.session?.model
                        picker.list.models.forEach { option ->
                            val ref = ModelRef(option.provider, option.model)
                            PickerRow(
                                title = option.name,
                                subtitle = listOfNotNull(option.providerName, option.description).joinToString(" · "),
                                selected = ref == current,
                            ) { actions.onChooseModel(ref) }
                        }
                    }
                    is Picker.Presets -> {
                        if (picker.list.presets.isEmpty()) Text(stringResource(R.string.chat_picker_empty))
                        picker.list.presets.forEach { preset ->
                            val known = permissionHint(preset.id)
                            PickerRow(
                                title = if (known != null) permissionLabel(preset.id) else preset.name,
                                subtitle = known,
                                selected = preset.id == state.session?.permissionPreset,
                            ) {
                                actions.onChoosePreset(preset.id)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = actions.onClosePicker) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        colors =
            ListItemDefaults.colors(
                containerColor = if (selected) DesignTheme.colors.fill else Color.Transparent,
            ),
        modifier =
            Modifier.clickable {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            },
    )
}

private class ConfirmingUriHandler(
    private val onRequest: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) = onRequest(uri)
}

private fun noticeText(notice: ChatNotice): Int =
    when (notice) {
        ChatNotice.OFFLINE -> R.string.chat_notice_offline
        ChatNotice.CANCEL_FAILED -> R.string.chat_notice_cancel_failed
        ChatNotice.LOAD_OLDER_FAILED -> R.string.chat_notice_older_failed
        ChatNotice.CHANGE_FAILED -> R.string.chat_notice_change_failed
        ChatNotice.SETUP_FAILED -> R.string.chat_notice_setup_failed
    }

/** 离底部不到这么多像素也算停在底部。 */
private const val STICK_TOLERANCE_PX = 48

/** 离顶部还剩几项时开始取更早的一页。 */
private const val LOAD_OLDER_THRESHOLD = 3
