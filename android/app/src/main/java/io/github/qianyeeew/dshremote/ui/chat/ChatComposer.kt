package io.github.qianyeeew.dshremote.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.attention.ComposerAttention
import io.github.qianyeeew.dshremote.ui.common.ChoiceRow
import io.github.qianyeeew.dshremote.ui.common.ComposerOption
import io.github.qianyeeew.dshremote.ui.common.ComposerOptionDot
import io.github.qianyeeew.dshremote.ui.common.ComposerOptionsRow
import io.github.qianyeeew.dshremote.ui.common.KeyboardPanel
import io.github.qianyeeew.dshremote.ui.common.StatusMark
import io.github.qianyeeew.dshremote.ui.common.StatusMarker
import io.github.qianyeeew.dshremote.ui.common.designPanel
import io.github.qianyeeew.dshremote.ui.common.onTap
import io.github.qianyeeew.dshremote.ui.common.presetTint
import io.github.qianyeeew.dshremote.ui.common.pressScale

/** 发送键此刻的作用（设计规格 6.3）。 */
enum class SendKeyMode {
    /** 有字：主文字色实心圆、白色向上箭头，点了发送。 */
    SEND,

    /** 没字、会话空闲（或者现在不能发送）：浅填充实心圆、灰色箭头，不可点。 */
    IDLE,

    /** 没字、会话在运行或在等你处理：主文字色实心圆、白色方块，点了停止。 */
    STOP,
}

/** [hasText]：输入框里有字或者有图片（有图片时可以不写字，落地方案 C2）。 */
fun sendKeyMode(
    state: ChatState,
    hasText: Boolean,
): SendKeyMode =
    when {
        hasText && state.canSend && state.preparingImages == 0 -> SendKeyMode.SEND
        !hasText && state.active && state.online -> SendKeyMode.STOP
        else -> SendKeyMode.IDLE
    }

/**
 * 输入框（设计规格 6.3）。没有焦点时一行：加号、占位文字、发送键；获得焦点时描边变成主文字色，可以写多行；
 * 加上的图片（7.6）在文字下面，加了图片时一直展开。加号选图片用系统的照片选择器（不需要存储权限）；
 * 看不了图时加号画成不可用，点了说明原因（落地方案 C2）。
 * 输入框下面常驻一行「模型 · 审批方式」，键盘弹出时收起。点了其中一项，键盘收起，原来键盘的位置换成
 * 选项面板（[KeyboardPanel]），输入框不动；长按发送键在同一块面板里看用量。
 *
 * 有待处理事项时按 [attention]：审批在等时不能写（DSH 停着等审批，消息不会改变这次审批），只留停止；
 * 只有一个问题的提问时，写的就是自己的答案，经 [onAnswer] 提交；多个问题时在面板里逐题写。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatComposer(
    state: ChatState,
    actions: ChatActions,
    /** 输入框下面面板里的模型或审批方式的选项。 */
    picker: Picker = Picker.Closed,
    /** 打开页面时输入框里已有的文字（新任务的消息没发出去时放回来）。 */
    initialText: String = "",
    /** 从本地恢复的草稿（落地方案 B3）：读到时输入框还是空的才放进去。 */
    restoredDraft: String? = null,
    /** 插件因为图片拒绝了消息，放回来的文字（落地方案 C2）：输入框里已经有字时放在前面。 */
    returnedText: String? = null,
    attention: ComposerAttention = ComposerAttention.None,
    onAnswer: (Attention, questionId: String, text: String) -> Boolean = { _, _, _ -> false },
) {
    val colors = DesignTheme.colors
    // 带光标位置：程序放进去的文字（放回的消息、恢复的草稿）光标在末尾，接着写不会插到最前面
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
    }
    val text = field.text
    LaunchedEffect(restoredDraft) {
        if (restoredDraft != null && field.text.isEmpty()) field = TextFieldValue(restoredDraft, TextRange(restoredDraft.length))
    }
    val edit = { value: TextFieldValue ->
        val changed = value.text != field.text
        field = value
        if (changed) actions.onDraftChange(value.text)
    }
    LaunchedEffect(returnedText) {
        if (returnedText != null) {
            val merged = if (field.text.isBlank()) returnedText else "$returnedText\n${field.text}"
            edit(TextFieldValue(merged, TextRange(merged.length)))
            actions.onReturnedTextShown()
        }
    }
    val pickImages =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(OutgoingMessage.MAX_IMAGES)) { uris ->
            if (uris.isNotEmpty()) actions.onAddImages(uris)
        }
    val hasImages = state.images.isNotEmpty() || state.preparingImages > 0
    var focused by remember { mutableStateOf(false) }
    val pickerOpen = picker != Picker.Closed
    // 长按发送键看用量（7.11），和模型、审批方式用同一块面板，一次只开一个
    var usageOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pickerOpen) { if (pickerOpen) usageOpen = false }
    val panelOpen = pickerOpen || usageOpen
    val expanded = focused || panelOpen || hasImages
    // 收起的动画期间还要画最后一次打开的选项
    var lastPicker by remember { mutableStateOf<Picker>(Picker.Closed) }
    if (pickerOpen) lastPicker = picker
    // 返回键先收起面板，再按一次才收起输入框
    BackHandler(enabled = pickerOpen) { actions.onClosePicker() }
    BackHandler(enabled = usageOpen) { usageOpen = false }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val inputFocus = remember { FocusRequester() }
    // 打开面板前正在打字：选完回到键盘
    var backToKeyboard by remember { mutableStateOf(false) }
    val openPanel = { open: () -> Unit ->
        if (!panelOpen) backToKeyboard = imeVisible
        keyboard?.hide()
        open()
    }
    val afterChoice = {
        if (backToKeyboard) {
            inputFocus.requestFocus()
            keyboard?.show()
        }
        backToKeyboard = false
    }
    val toggleUsage = {
        if (pickerOpen) actions.onClosePicker()
        if (!usageOpen) {
            if (!panelOpen) backToKeyboard = imeVisible
            keyboard?.hide()
            actions.onRefreshUsage()
        }
        usageOpen = !usageOpen
    }
    val ratio = state.usage?.context?.ratio
    val almostFull = ratio != null && ratio >= CONTEXT_ALMOST_FULL
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    var hadKeyboard by remember { mutableStateOf(false) }
    // 收起键盘、又没写字时退出编辑，输入框跟着收起
    LaunchedEffect(imeVisible, panelOpen) {
        if (hadKeyboard && !imeVisible && !panelOpen && text.isBlank()) focus.clearFocus()
        hadKeyboard = imeVisible
    }
    val hint =
        stringResource(
            when {
                !state.online -> R.string.chat_input_hint_offline
                attention is ComposerAttention.Approval -> R.string.chat_input_hint_approval
                attention is ComposerAttention.AnswerAbove -> R.string.chat_input_hint_answer_above
                attention is ComposerAttention.Answer -> R.string.chat_input_hint_answer
                state.running -> R.string.chat_input_hint_running
                else -> R.string.chat_input_hint
            },
        )
    val writable = state.canSend && attention !is ComposerAttention.Approval && attention !is ComposerAttention.AnswerAbove
    val send = {
        val accepted =
            when (attention) {
                is ComposerAttention.Answer -> onAnswer(attention.attention, attention.questionId, text)
                is ComposerAttention.Approval, is ComposerAttention.AnswerAbove -> false
                ComposerAttention.None -> actions.onSend(text)
            }
        if (accepted) {
            edit(TextFieldValue())
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        }
    }
    // 回答提问时写的是答案，不能带图片
    val showAddImage = attention == ComposerAttention.None
    val mode = sendKeyMode(state, (text.isNotBlank() || (state.images.isNotEmpty() && showAddImage)) && writable)
    val shape = RoundedCornerShape(if (expanded) 22.dp else 26.dp)
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                .designPanel(shape, focused = focused)
                .animateContentSize(tween(160))
                .testTag("chat-composer"),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .padding(
                        start = if (showAddImage) 3.dp else 16.dp,
                        end = 1.dp,
                        top = if (expanded) 1.dp else 0.dp,
                        bottom = if (expanded) 1.dp else 0.dp,
                    ),
                verticalAlignment = if (expanded) Alignment.Bottom else Alignment.CenterVertically,
            ) {
                if (showAddImage) {
                    AddImageButton(
                        available = writable && state.imageSupport.canAdd && state.imageSlots > 0,
                        onAdd = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onUnavailable = { if (state.imageSlots > 0) actions.onImagesUnavailable() },
                        modifier = Modifier.padding(bottom = if (expanded) 2.dp else 0.dp),
                    )
                }
                BasicTextField(
                    value = field,
                    onValueChange = edit,
                    enabled = writable,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.text),
                    maxLines = if (expanded) 6 else 1,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(vertical = if (expanded) 12.dp else 0.dp)
                            .focusRequester(inputFocus)
                            .onFocusChanged { focused = it.isFocused }
                            .onTap {
                                // 点输入框：键盘回来，面板让位
                                if (pickerOpen) actions.onClosePicker()
                                usageOpen = false
                                backToKeyboard = false
                                if (focused) keyboard?.show()
                            }.padding(end = 10.dp)
                            .testTag("chat-input")
                            .semantics { contentDescription = hint },
                    decorationBox = { field ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    hint,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            field()
                        }
                    },
                )
                SendKey(mode, ratio, onSend = send, onStop = actions.onCancelRun, onLongPress = toggleUsage)
            }
            if (showAddImage) {
                ComposerImageStrip(
                    state.images,
                    state.preparingImages,
                    actions.onRemoveImage,
                    Modifier.padding(start = 16.dp, bottom = 12.dp),
                )
            }
        }
        // 模型、审批方式：平时常驻在输入框下面；键盘弹出时收起，收起键盘后再出来
        AnimatedVisibility(
            visible = !imeVisible || panelOpen,
            enter = fadeIn(tween(OPTIONS_MILLIS)) + expandVertically(tween(OPTIONS_MILLIS)),
            exit = fadeOut(tween(OPTIONS_MILLIS)) + shrinkVertically(tween(OPTIONS_MILLIS)),
        ) {
            ComposerOptionsRow(Modifier.padding(top = 2.dp, bottom = 4.dp).testTag("chat-composer-options")) {
                val model = state.session?.model?.model ?: stringResource(R.string.design_choose_model)
                val presetId = state.session?.permissionPreset
                val preset = permissionLabel(presetId)
                ComposerOption(
                    model,
                    stringResource(R.string.design_model_value, model),
                    R.drawable.ic_dsh_bolt,
                    if (picker.kind == PickerKind.MODELS) colors.text else colors.textSecondary,
                    enabled = state.online,
                    open = picker.kind == PickerKind.MODELS,
                    onClick = { openPanel(actions.onOpenModels) },
                    modifier = Modifier.weight(1f, fill = false).testTag("chat-model"),
                )
                ComposerOptionDot()
                ComposerOption(
                    preset,
                    stringResource(R.string.design_permission_value, preset),
                    R.drawable.ic_dsh_shield,
                    presetTint(presetId),
                    enabled = state.online,
                    open = picker.kind == PickerKind.PRESETS,
                    onClick = { openPanel(actions.onOpenPresets) },
                    modifier = Modifier.weight(1f, fill = false).testTag("chat-preset"),
                )
                if (almostFull) {
                    Text(
                        stringResource(R.string.usage_context_almost_full),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.waitingText,
                        maxLines = 1,
                        modifier = Modifier.testTag("chat-context-almost-full"),
                    )
                }
            }
        }
        if (imeVisible && !panelOpen) Spacer(Modifier.height(8.dp))
        val title =
            stringResource(
                when {
                    usageOpen -> R.string.panel_usage
                    lastPicker.kind == PickerKind.MODELS -> R.string.panel_models
                    else -> R.string.panel_permissions
                },
            )
        KeyboardPanel(open = panelOpen, title = title) {
            if (usageOpen) {
                Box(Modifier.padding(horizontal = 12.dp)) { UsageDetails(state.usage) }
            } else {
                PickerChoices(lastPicker, state, actions, onChosen = afterChoice)
            }
        }
    }
}

/** 面板里的内容：读取中、没有可选的、或者模型（下面一行灰字写来源）和审批方式（下面一行写说明）。 */
@Composable
private fun PickerChoices(
    picker: Picker,
    state: ChatState,
    actions: ChatActions,
    onChosen: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val choose = { action: () -> Unit ->
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        action()
        onChosen()
    }
    val rowPadding = PaddingValues(horizontal = 12.dp)
    when (picker) {
        Picker.Closed -> Unit
        is Picker.Loading -> PickerMessage(stringResource(R.string.chat_picker_loading), loading = true)
        is Picker.Models ->
            if (picker.list.models.isEmpty()) {
                PickerMessage(stringResource(R.string.chat_picker_empty), loading = false)
            } else {
                val current = state.session?.model
                picker.list.models.forEach { option ->
                    val ref = ModelRef(option.provider, option.model)
                    ChoiceRow(
                        option.name,
                        { choose { actions.onChooseModel(ref) } },
                        Modifier.testTag("chat-model:${option.provider}/${option.model}"),
                        subtitle = listOfNotNull(option.providerName, option.description).joinToString(" · "),
                        selected = ref == current,
                        contentPadding = rowPadding,
                    )
                }
            }
        is Picker.Presets ->
            if (picker.list.presets.isEmpty()) {
                PickerMessage(stringResource(R.string.chat_picker_empty), loading = false)
            } else {
                picker.list.presets.forEach { preset ->
                    val known = permissionHint(preset.id)
                    ChoiceRow(
                        if (known != null) permissionLabel(preset.id) else preset.name,
                        { choose { actions.onChoosePreset(preset.id) } },
                        Modifier.testTag("chat-preset:${preset.id}"),
                        subtitle = known,
                        selected = preset.id == state.session?.permissionPreset,
                        leadingIcon = R.drawable.ic_dsh_shield,
                        leadingTint = presetTint(preset.id),
                        contentPadding = rowPadding,
                    )
                }
            }
    }
}

/** 面板里的一句话：正在读取（带转圈标记），或者没有可选的。 */
@Composable
private fun PickerMessage(
    text: String,
    loading: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp)
            .testTag("chat-picker-message"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (loading) StatusMarker(StatusMark.RUNNING)
        Text(text, style = MaterialTheme.typography.bodySmall, color = DesignTheme.colors.textSecondary)
    }
}

/** 配置行显示和收起的时长。 */
private const val OPTIONS_MILLIS = 160

/**
 * 发送键：发送、不可点、停止三种（[SendKeyMode]）。看上去直径 30，可点区域 48。
 * 外圈表示上下文用了多少（[contextRing]）；长按展开用量（7.11），不能发送时也可以长按。
 */
@Composable
private fun SendKey(
    mode: SendKeyMode,
    contextRatio: Float?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = DesignTheme.colors
    val haptic = LocalHapticFeedback.current
    val description = stringResource(if (mode == SendKeyMode.STOP) R.string.chat_stop else R.string.chat_send)
    val idle = mode == SendKeyMode.IDLE
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .pressScale(press)
            .contextRing(contextRatio)
            .combinedClickable(
                interactionSource = press,
                indication = null,
                role = Role.Button,
                onLongClickLabel = stringResource(R.string.usage_show),
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
                onClick = {
                    when (mode) {
                        SendKeyMode.STOP -> onStop()
                        SendKeyMode.SEND -> onSend()
                        SendKeyMode.IDLE -> Unit
                    }
                },
            ).semantics {
                contentDescription = description
                if (idle) disabled()
            }.testTag(if (mode == SendKeyMode.STOP) "chat-stop" else "chat-send"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(30.dp).background(if (mode == SendKeyMode.IDLE) colors.fill else colors.text, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (mode == SendKeyMode.STOP) R.drawable.ic_dsh_stop else R.drawable.ic_dsh_arrow_up),
                contentDescription = null,
                modifier = Modifier.size(if (mode == SendKeyMode.STOP) 24.dp else 18.dp),
                tint = if (mode == SendKeyMode.IDLE) colors.textSecondary else colors.panel,
            )
        }
    }
}

@Composable
internal fun permissionLabel(preset: String?): String =
    when (preset) {
        null -> stringResource(R.string.design_default_permission)
        "read-only" -> stringResource(R.string.design_permission_read_only)
        "workspace-write" -> stringResource(R.string.design_permission_workspace)
        "danger-full-access" -> stringResource(R.string.design_permission_full)
        else -> preset
    }

/** 选择列表里审批方式名字下面的一句说明。DSH 返回的其他预设没有说明。 */
@Composable
internal fun permissionHint(preset: String): String? =
    when (preset) {
        "read-only" -> stringResource(R.string.design_permission_read_only_hint)
        "workspace-write" -> stringResource(R.string.design_permission_workspace_hint)
        "danger-full-access" -> stringResource(R.string.design_permission_full_hint)
        else -> null
    }
