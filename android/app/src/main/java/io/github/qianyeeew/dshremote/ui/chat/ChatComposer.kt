package io.github.qianyeeew.dshremote.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.attention.ComposerAttention
import io.github.qianyeeew.dshremote.ui.common.ChoiceChevron
import io.github.qianyeeew.dshremote.ui.common.ChoiceList
import io.github.qianyeeew.dshremote.ui.common.ChoiceRow
import io.github.qianyeeew.dshremote.ui.common.DesignDivider
import io.github.qianyeeew.dshremote.ui.common.InlineChoices
import io.github.qianyeeew.dshremote.ui.common.StatusMark
import io.github.qianyeeew.dshremote.ui.common.StatusMarker
import io.github.qianyeeew.dshremote.ui.common.designPanel
import io.github.qianyeeew.dshremote.ui.common.onTap

/** 发送键此刻的作用（设计规格 6.3）。 */
enum class SendKeyMode {
    /** 有字：主文字色实心圆、白色向上箭头，点了发送。 */
    SEND,

    /** 没字、会话空闲（或者现在不能发送）：浅填充实心圆、灰色箭头，不可点。 */
    IDLE,

    /** 没字、会话在运行或在等你处理：主文字色实心圆、白色方块，点了停止。 */
    STOP,
}

fun sendKeyMode(
    state: ChatState,
    hasText: Boolean,
): SendKeyMode =
    when {
        hasText && state.canSend -> SendKeyMode.SEND
        !hasText && state.active && state.online -> SendKeyMode.STOP
        else -> SendKeyMode.IDLE
    }

/**
 * 输入框（设计规格 6.3）。收起（没有焦点）时一行：占位文字、发送键；展开（获得焦点）时描边变成主文字色，
 * 上面是输入的文字，下面一行是模型、审批方式、发送键。加号（图片）和发送键外圈（上下文用量）等落地方案 C2、B4。
 *
 * 有待处理事项时按 [attention]：审批在等时不能写（DSH 停着等审批，消息不会改变这次审批），只留停止；
 * 只有一个问题的提问时，写的就是自己的答案，经 [onAnswer] 提交；多个问题时在面板里逐题写。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatComposer(
    state: ChatState,
    actions: ChatActions,
    /** 输入框里展开的模型或审批方式的选项（设计规格 6.10）。 */
    picker: Picker = Picker.Closed,
    /** 打开页面时输入框里已有的文字（新任务的消息没发出去时放回来）。 */
    initialText: String = "",
    /** 从本地恢复的草稿（落地方案 B3）：读到时输入框还是空的才放进去。 */
    restoredDraft: String? = null,
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
    var focused by remember { mutableStateOf(false) }
    val pickerOpen = picker != Picker.Closed
    // 长按发送键展开的用量（7.11）。和模型、审批方式的选项一次只展开一个
    var usageOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pickerOpen) { if (pickerOpen) usageOpen = false }
    val expanded = focused || pickerOpen || usageOpen
    // 收起的动画期间还要画最后一次展开的选项
    var lastPicker by remember { mutableStateOf<Picker>(Picker.Closed) }
    if (pickerOpen) lastPicker = picker
    // 返回键先收起选项和用量，再按一次才收起输入框
    BackHandler(enabled = pickerOpen) { actions.onClosePicker() }
    BackHandler(enabled = usageOpen) { usageOpen = false }
    val toggleUsage = {
        if (pickerOpen) actions.onClosePicker()
        usageOpen = !usageOpen
        if (usageOpen) actions.onRefreshUsage()
    }
    val ratio = state.usage?.context?.ratio
    val almostFull = ratio != null && ratio >= CONTEXT_ALMOST_FULL
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val imeVisible = WindowInsets.isImeVisible
    var hadKeyboard by remember { mutableStateOf(false) }
    // 收起键盘、又没写字时退出编辑，输入框跟着收起
    LaunchedEffect(imeVisible, pickerOpen, usageOpen) {
        if (hadKeyboard && !imeVisible && !pickerOpen && !usageOpen && text.isBlank()) focus.clearFocus()
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
    val mode = sendKeyMode(state, text.isNotBlank() && writable)
    val shape = RoundedCornerShape(if (expanded) 22.dp else 26.dp)
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .designPanel(shape, focused = expanded)
                .animateContentSize(tween(160))
                .padding(start = 16.dp, end = 6.dp, top = if (expanded) 12.dp else 0.dp, bottom = if (expanded) 6.dp else 0.dp)
                .testTag("chat-composer"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.heightIn(min = if (expanded) 24.dp else 50.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            .onFocusChanged { focused = it.isFocused }
                            .onTap {
                                if (pickerOpen) actions.onClosePicker()
                                usageOpen = false
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
                if (!expanded) SendKey(mode, ratio, onSend = send, onStop = actions.onCancelRun, onLongPress = toggleUsage)
            }
            // 选项在最下面一行的正上方展开，输入框卡片向上长；不另开窗口，焦点和键盘都不变
            InlineChoices(visible = expanded && pickerOpen) {
                DesignDivider(Modifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp))
                PickerChoices(lastPicker, state, actions)
                DesignDivider(Modifier.padding(end = 10.dp, top = 4.dp))
            }
            InlineChoices(visible = expanded && usageOpen && !pickerOpen) {
                DesignDivider(Modifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp))
                UsageDetails(state.usage)
                DesignDivider(Modifier.padding(end = 10.dp, top = 4.dp))
            }
            if (expanded) {
                Row(
                    Modifier.fillMaxWidth().testTag("chat-composer-options"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val model = state.session?.model?.model ?: stringResource(R.string.design_choose_model)
                    val preset = permissionLabel(state.session?.permissionPreset)
                    // 两项各自按内容宽度，都很长时各占一半
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        ComposerChoice(
                            model,
                            stringResource(R.string.design_model_value, model),
                            state.online,
                            open = picker.kind == PickerKind.MODELS,
                            actions.onOpenModels,
                            Modifier.weight(1f, fill = false).testTag("chat-model"),
                        )
                        ComposerChoice(
                            preset,
                            stringResource(R.string.design_permission_value, preset),
                            state.online,
                            open = picker.kind == PickerKind.PRESETS,
                            actions.onOpenPresets,
                            Modifier.weight(1f, fill = false).testTag("chat-preset"),
                        )
                    }
                    if (almostFull) {
                        Text(
                            stringResource(R.string.usage_context_almost_full),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.waitingText,
                            maxLines = 1,
                            modifier = Modifier.testTag("chat-context-almost-full"),
                        )
                    }
                    SendKey(mode, ratio, onSend = send, onStop = actions.onCancelRun, onLongPress = toggleUsage)
                }
            }
        }
    }
}

/** 选项区的内容：读取中、没有可选的、或者模型（下面一行灰字写来源）和审批方式（下面一行写说明）。 */
@Composable
private fun PickerChoices(
    picker: Picker,
    state: ChatState,
    actions: ChatActions,
) {
    val haptic = LocalHapticFeedback.current
    val choose = { action: () -> Unit ->
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        action()
    }
    when (picker) {
        Picker.Closed -> Unit
        is Picker.Loading -> PickerMessage(stringResource(R.string.chat_picker_loading), loading = true)
        is Picker.Models ->
            if (picker.list.models.isEmpty()) {
                PickerMessage(stringResource(R.string.chat_picker_empty), loading = false)
            } else {
                ChoiceList(withSubtitles = true) {
                    val current = state.session?.model
                    picker.list.models.forEach { option ->
                        val ref = ModelRef(option.provider, option.model)
                        ChoiceRow(
                            option.name,
                            { choose { actions.onChooseModel(ref) } },
                            Modifier.testTag("chat-model:${option.provider}/${option.model}"),
                            subtitle = listOfNotNull(option.providerName, option.description).joinToString(" · "),
                            selected = ref == current,
                            contentPadding = PaddingValues(end = 10.dp),
                        )
                    }
                }
            }
        is Picker.Presets ->
            if (picker.list.presets.isEmpty()) {
                PickerMessage(stringResource(R.string.chat_picker_empty), loading = false)
            } else {
                ChoiceList(withSubtitles = true) {
                    picker.list.presets.forEach { preset ->
                        val known = permissionHint(preset.id)
                        ChoiceRow(
                            if (known != null) permissionLabel(preset.id) else preset.name,
                            { choose { actions.onChoosePreset(preset.id) } },
                            Modifier.testTag("chat-preset:${preset.id}"),
                            subtitle = known,
                            selected = preset.id == state.session?.permissionPreset,
                            contentPadding = PaddingValues(end = 10.dp),
                        )
                    }
                }
            }
    }
}

/** 选项区里的一句话：正在读取（带转圈标记），或者没有可选的。 */
@Composable
private fun PickerMessage(
    text: String,
    loading: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat-picker-message"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (loading) StatusMarker(StatusMark.RUNNING)
        Text(text, style = MaterialTheme.typography.bodySmall, color = DesignTheme.colors.textSecondary)
    }
}

/** 模型、审批方式：带小箭头的灰字，点了在上方展开选项；展开时文字换成主文字色、字重 500，小箭头朝上。 */
@Composable
private fun ComposerChoice(
    label: String,
    description: String,
    enabled: Boolean,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = DesignTheme.colors
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (open) FontWeight.Medium else FontWeight.Normal),
            color = (if (open) colors.text else colors.textSecondary).copy(alpha = if (enabled) 1f else 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        ChoiceChevron(open, Modifier.size(12.dp), tint = if (open) colors.text else colors.textSecondary)
    }
}

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
    Box(
        Modifier
            .size(48.dp)
            .contextRing(contextRatio)
            .combinedClickable(
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
