package io.github.qianyeeew.dshremote.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.github.qianyeeew.dshremote.ui.attention.Resolution
import io.github.qianyeeew.dshremote.ui.common.DesignTopBar
import io.github.qianyeeew.dshremote.ui.common.RoundIconButton
import io.github.qianyeeew.dshremote.ui.common.sessionTitleText
import io.github.qianyeeew.dshremote.ui.hosts.StatusBadge
import io.github.qianyeeew.dshremote.ui.hosts.statusDetail
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 页面上的操作。 */
class ChatActions(
    val onBack: () -> Unit = {},
    val onSend: (text: String, steer: Boolean) -> Boolean = { _, _ -> false },
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
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
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
            // 和消息列表按同样的权重分剩余空间，并且不强制占满：事项最多占一半，少时只占需要的高度。
            // 按剩余空间而不是窗口高度计算，打开键盘时输入框不会被挤扁
            AttentionPanel(state, attentionActions, Modifier.weight(1f, fill = false))
            ChatComposer(state, actions, pickerOpen = picker != Picker.Closed)
        }
    }

    pendingLink?.let { link -> LinkDialog(link, onDismiss = { pendingLink = null }) }
    PickerDialog(picker, state, actions)
}

@Composable
private fun ChatTopBar(
    state: ChatState,
    actions: ChatActions,
) {
    var menu by remember { mutableStateOf(false) }
    DesignTopBar(
        title = sessionTitleText(state.session?.title),
        navigation = { RoundIconButton(R.drawable.ic_arrow_back, stringResource(R.string.action_back), actions.onBack) },
        subtitle = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                state.session?.status?.let { status ->
                    Text(
                        stringResource(
                            when (status) {
                                SessionStatus.RUNNING -> R.string.session_status_running
                                SessionStatus.WAITING -> R.string.session_status_waiting
                                SessionStatus.IDLE -> R.string.session_status_idle
                                SessionStatus.UNKNOWN -> R.string.session_status_unknown
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (status ==
                                SessionStatus.WAITING
                            ) {
                                DesignTheme.colors.waitingText
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                if (state.session?.permissionPreset == "danger-full-access") {
                    Text(
                        permissionLabel(state.session.permissionPreset),
                        style = MaterialTheme.typography.labelSmall,
                        color = DesignTheme.colors.waitingText,
                    )
                }
            }
        },
        actions = {
            Box {
                RoundIconButton(R.drawable.ic_more_vert, stringResource(R.string.action_more), { menu = true }, enabled = state.online)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                state.session
                                    ?.model
                                    ?.model
                                    ?.let { stringResource(R.string.chat_menu_model_current, it) }
                                    ?: stringResource(R.string.chat_menu_model),
                            )
                        },
                        onClick = {
                            menu = false
                            actions.onOpenModels()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                state.session?.permissionPreset?.let {
                                    stringResource(
                                        R.string.chat_menu_preset_current,
                                        permissionLabel(it),
                                    )
                                }
                                    ?: stringResource(R.string.chat_menu_preset),
                            )
                        },
                        onClick = {
                            menu = false
                            actions.onOpenPresets()
                        },
                    )
                }
            }
        },
    )
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
    val reversed = remember(state.items) { state.items.asReversed() }
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
    ) {
        items(reversed, key = { it.key }, contentType = { it::class }) { item ->
            when (item) {
                is ChatItem.Entry -> MessageView(item.message, onCopied)
                is ChatItem.Local -> LocalMessageView(item.local) { actions.onRetrySend(item.local.outgoing.requestId) }
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
                    Text(stringResource(R.string.chat_load_failed), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = actions.onRetryLoad) { Text(stringResource(R.string.action_retry)) }
                }
            !state.loaded ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(stringResource(R.string.chat_loading))
                }
            state.hasMore || state.loadingOlder -> CircularProgressIndicator()
            state.items.isEmpty() ->
                Text(
                    stringResource(R.string.chat_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
        }
    }
}

@Composable
private fun LinkBanner(state: ChatState) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusBadge(state.link)
            Text(statusDetail(state.link), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 这个会话的审批和提问，显示在输入框上方。事项多或者很长时可以滚动。 */
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.attention.forEach { attention ->
            AttentionCard(attention, state.attentionUi[attention.attentionId] ?: AttentionUi(), actions)
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
            Text(stringResource(if (picker is Picker.Presets) R.string.chat_menu_preset else R.string.chat_menu_model))
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when (picker) {
                    Picker.Closed -> Unit
                    Picker.Loading ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator()
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
                            PickerRow(title = preset.name, subtitle = preset.id, selected = preset.id == state.session?.permissionPreset) {
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
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        colors =
            ListItemDefaults.colors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
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
    }

/** 离底部不到这么多像素也算停在底部。 */
private const val STICK_TOLERANCE_PX = 48

/** 离顶部还剩几项时开始取更早的一页。 */
private const val LOAD_OLDER_THRESHOLD = 3
