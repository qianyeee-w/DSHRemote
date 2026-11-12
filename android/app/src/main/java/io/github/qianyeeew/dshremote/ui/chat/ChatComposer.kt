package io.github.qianyeeew.dshremote.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.attention.ComposerAttention
import io.github.qianyeeew.dshremote.ui.common.designPanel

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
    pickerOpen: Boolean = false,
    attention: ComposerAttention = ComposerAttention.None,
    onAnswer: (Attention, questionId: String, text: String) -> Boolean = { _, _, _ -> false },
) {
    val colors = DesignTheme.colors
    var text by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val expanded = focused || pickerOpen
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val imeVisible = WindowInsets.isImeVisible
    var hadKeyboard by remember { mutableStateOf(false) }
    // 收起键盘、又没写字时退出编辑，输入框跟着收起
    LaunchedEffect(imeVisible, pickerOpen) {
        if (hadKeyboard && !imeVisible && !pickerOpen && text.isBlank()) focus.clearFocus()
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
            text = ""
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
                    value = text,
                    onValueChange = { text = it },
                    enabled = writable,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.text),
                    maxLines = if (expanded) 6 else 1,
                    modifier =
                        Modifier
                            .weight(1f)
                            .onFocusChanged { focused = it.isFocused }
                            .padding(end = 10.dp)
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
                if (!expanded) SendKey(mode, onSend = send, onStop = actions.onCancelRun)
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
                            actions.onOpenModels,
                            Modifier.weight(1f, fill = false),
                        )
                        ComposerChoice(
                            preset,
                            stringResource(R.string.design_permission_value, preset),
                            state.online,
                            actions.onOpenPresets,
                            Modifier.weight(1f, fill = false),
                        )
                    }
                    SendKey(mode, onSend = send, onStop = actions.onCancelRun)
                }
            }
        }
    }
}

/** 模型、审批方式：带小箭头的灰字，点了打开选择。 */
@Composable
private fun ComposerChoice(
    label: String,
    description: String,
    enabled: Boolean,
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
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary.copy(alpha = if (enabled) 1f else 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(painterResource(R.drawable.ic_dsh_chevron_down), null, Modifier.size(12.dp), tint = colors.textSecondary)
    }
}

/** 发送键：发送、不可点、停止三种（[SendKeyMode]）。看上去直径 30，可点区域 48。 */
@Composable
private fun SendKey(
    mode: SendKeyMode,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = DesignTheme.colors
    val description = stringResource(if (mode == SendKeyMode.STOP) R.string.chat_stop else R.string.chat_send)
    Box(
        Modifier
            .size(48.dp)
            .clickable(
                enabled = mode != SendKeyMode.IDLE,
                role = Role.Button,
                onClick = if (mode == SendKeyMode.STOP) onStop else onSend,
            ).semantics { contentDescription = description }
            .testTag(if (mode == SendKeyMode.STOP) "chat-stop" else "chat-send"),
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
