package io.github.qianyeeew.dshremote.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R

/** 输入时才显示模型和权限；选择器打开及已有草稿时保持展开。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatComposer(
    state: ChatState,
    actions: ChatActions,
    pickerOpen: Boolean = false,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var steer by rememberSaveable { mutableStateOf(true) }
    var focused by remember { mutableStateOf(false) }
    val expanded = focused || text.isNotBlank() || pickerOpen
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val imeVisible = WindowInsets.isImeVisible
    var hadKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, pickerOpen) {
        if (hadKeyboard && !imeVisible && !pickerOpen && text.isBlank()) focus.clearFocus()
        hadKeyboard = imeVisible
    }
    val hint = stringResource(if (state.online) R.string.chat_input_hint else R.string.chat_input_hint_offline)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (state.active && state.online) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (state.running && expanded) {
                    FilterChip(selected = steer, onClick = { steer = !steer }, label = { Text(stringResource(R.string.chat_mode_steer)) })
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = actions.onCancelRun, modifier = Modifier.testTag("chat-stop")) {
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.chat_stop), Modifier.padding(start = 4.dp))
                }
            }
        }
        if (state.running && expanded) {
            Text(
                stringResource(R.string.chat_mode_steer_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (focused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().animateContentSize(tween(160)).testTag("chat-composer"),
        ) {
            Column(Modifier.padding(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        enabled = state.canSend,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        minLines = if (expanded) 2 else 1,
                        maxLines = 6,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .onFocusChanged { focused = it.isFocused }
                                .padding(horizontal = 10.dp, vertical = 12.dp)
                                .testTag("chat-input")
                                .semantics { contentDescription = hint },
                        decorationBox = { field ->
                            Box {
                                if (text.isEmpty()) {
                                    Text(
                                        hint,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                field()
                            }
                        },
                    )
                    if (!expanded) {
                        SendButton(state.canSend && text.isNotBlank()) {
                            if (actions.onSend(text, steer)) {
                                text = ""
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            }
                        }
                    }
                }
                if (expanded) {
                    Row(
                        Modifier.fillMaxWidth().testTag("chat-composer-options"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val preset = permissionLabel(state.session?.permissionPreset)
                        val model = state.session?.model?.model ?: stringResource(R.string.design_choose_model)
                        ComposerOption(
                            preset,
                            stringResource(R.string.design_permission_value, preset),
                            state.online,
                            Modifier.weight(1f),
                            R.drawable.ic_dsh_shield,
                            actions.onOpenPresets,
                        )
                        ComposerOption(
                            model,
                            stringResource(R.string.design_model_value, model),
                            state.online,
                            Modifier.weight(1f),
                            onClick = actions.onOpenModels,
                        )
                        SendButton(state.canSend && text.isNotBlank()) {
                            if (actions.onSend(text, steer)) {
                                text = ""
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerOption(
    label: String,
    description: String,
    enabled: Boolean,
    modifier: Modifier,
    icon: Int? = null,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
    ) {
        icon?.let { Icon(painterResource(it), null, Modifier.size(15.dp)) }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(horizontal = 3.dp),
        )
        Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(13.dp))
    }
}

@Composable
private fun SendButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag("chat-send")) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_dsh_arrow_up),
                    stringResource(R.string.chat_send),
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
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
