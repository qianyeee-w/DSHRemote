package io.github.qianyeeew.dshremote.ui.chat

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.api.ThinkingPart
import io.github.qianyeeew.dshremote.data.api.ToolPart
import io.github.qianyeeew.dshremote.data.api.ToolStatus
import io.github.qianyeeew.dshremote.data.chat.LocalMessage
import io.github.qianyeeew.dshremote.data.chat.OutgoingStatus
import io.github.qianyeeew.dshremote.ui.CodeFontFamily
import io.github.qianyeeew.dshremote.ui.LocalStatusColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 一条消息：用户消息是右侧的气泡，模型回复占满宽度。 */
@Composable
fun MessageView(
    message: Message,
    onCopied: () -> Unit,
) {
    if (message.role == MessageRole.USER) {
        UserBubble(message.parts.filterIsInstance<TextPart>().joinToString("") { it.text })
    } else {
        AssistantMessage(message, onCopied)
    }
}

@Composable
private fun UserBubble(
    text: String,
    footer: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 4.dp),
            modifier = Modifier.widthIn(max = 320.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        ) {
            SelectionContainer {
                Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        footer?.invoke()
    }
}

/** 本地发出、还没出现在会话日志中的消息。失败时点击重试。 */
@Composable
fun LocalMessageView(
    local: LocalMessage,
    onRetry: () -> Unit,
) {
    val failed = local.status == OutgoingStatus.FAILED
    UserBubble(
        text = local.outgoing.text,
        onClick = if (failed) onRetry else null,
        footer = {
            Text(
                stringResource(
                    when (local.status) {
                        OutgoingStatus.SENDING -> R.string.chat_local_sending
                        OutgoingStatus.SENT -> R.string.chat_local_sent
                        OutgoingStatus.FAILED -> R.string.chat_local_failed
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).let { if (failed) it.clickable(onClick = onRetry) else it },
            )
        },
    )
}

@Composable
private fun AssistantMessage(
    message: Message,
    onCopied: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message.parts.forEachIndexed { index, part ->
            when (part) {
                is TextPart -> MarkdownText(part.text)
                is ThinkingPart ->
                    Collapsible(
                        stringResource(R.string.chat_thinking),
                        live =
                            message.draft && index == message.parts.lastIndex,
                    ) {
                        SelectionContainer { Text(part.text, style = MaterialTheme.typography.bodyMedium) }
                    }
                is ToolPart -> ToolCard(part)
                else -> Unit
            }
        }
        if (message.draft) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.chat_drafting),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val text = message.parts.filterIsInstance<TextPart>().joinToString("\n\n") { it.text }
            if (text.isNotBlank()) CopyButton(text, onCopied)
        }
    }
}

@Composable
private fun CopyButton(
    text: String,
    onCopied: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    TextButton(onClick = {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text)))
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onCopied()
        }
    }) {
        Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.chat_copy), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Markdown（m2-android.md 3.4）。`retainState`：内容变化时，在新内容解析完之前继续显示旧的结果，
 * 流式输出时不会闪成空白。代码块带复制按钮，代码块和表格可以横向滚动（库自带）。
 * 链接的点击由外层的 LocalUriHandler 处理（先确认再打开，见 ChatScreen）。
 */
@Composable
fun MarkdownText(text: String) {
    val state = rememberMarkdownState(text, retainState = true)
    val components =
        remember {
            markdownComponents(
                codeFence = {
                    MarkdownHighlightedCodeFence(
                        content = it.content,
                        node = it.node,
                        style = it.typography.code,
                        showHeader = true,
                    )
                },
                codeBlock = {
                    MarkdownHighlightedCodeBlock(
                        content = it.content,
                        node = it.node,
                        style = it.typography.code,
                        showHeader = true,
                    )
                },
            )
        }
    SelectionContainer {
        Markdown(
            markdownState = state,
            colors = markdownColor(),
            // 库默认的标题用 display 字号，在手机的对话里太大了
            typography =
                markdownTypography(
                    text = MaterialTheme.typography.bodyLarge,
                    code = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFontFamily, fontSize = 12.5.sp, lineHeight = 22.sp),
                    h1 = MaterialTheme.typography.headlineSmall,
                    h2 = MaterialTheme.typography.titleLarge,
                    h3 = MaterialTheme.typography.titleMedium,
                    h4 = MaterialTheme.typography.titleSmall,
                    h5 = MaterialTheme.typography.titleSmall,
                    h6 = MaterialTheme.typography.titleSmall,
                ),
            components = components,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 默认折叠的区块（思考过程）。[live] 为 true 时标题旁显示进度。 */
@Composable
private fun Collapsible(
    title: String,
    live: Boolean,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(180), label = "thinking-arrow")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_expand_more),
                contentDescription = stringResource(R.string.chat_expand),
                modifier = Modifier.size(18.dp).rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (live) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(tween(180)) + fadeIn(tween(120)),
            exit =
                shrinkVertically(tween(160)) + fadeOut(tween(90)),
        ) {
            Box(Modifier.padding(start = 24.dp, top = 4.dp)) { content() }
        }
    }
}

/** 工具卡片：默认折叠，标题是状态和 `summary`；展开后显示参数和结果（m2-android.md 3.4）。 */
@Composable
private fun ToolCard(tool: ToolPart) {
    var open by rememberSaveable(tool.callId) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(180), label = "tool-arrow")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolStatusLabel(tool.status)
            Text(
                tool.summary.ifBlank { tool.name },
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (open) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painterResource(R.drawable.ic_expand_more),
                contentDescription = stringResource(R.string.chat_expand),
                modifier = Modifier.size(18.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(tween(180)) + fadeIn(tween(120)),
            exit =
                shrinkVertically(tween(160)) + fadeOut(tween(90)),
        ) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tool.input.isNotEmpty()) CodeSection(stringResource(R.string.chat_tool_input), prettyJson(tool.input))
                tool.output?.takeIf { it.isNotEmpty() }?.let { CodeSection(stringResource(R.string.chat_tool_output), it) }
                if (tool.truncated) {
                    Text(
                        stringResource(R.string.chat_tool_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolStatusLabel(status: ToolStatus) {
    val (text, color) =
        when (status) {
            ToolStatus.RUNNING -> stringResource(R.string.chat_tool_running) to LocalStatusColors.current.running
            ToolStatus.DONE -> stringResource(R.string.chat_tool_done) to MaterialTheme.colorScheme.onSurfaceVariant
            ToolStatus.ERROR -> stringResource(R.string.chat_tool_error) to MaterialTheme.colorScheme.error
            ToolStatus.REJECTED -> stringResource(R.string.chat_tool_rejected) to MaterialTheme.colorScheme.error
            ToolStatus.UNKNOWN -> stringResource(R.string.chat_tool_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
        }
    if (status == ToolStatus.RUNNING) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

/** 等宽字体、可以横向滚动和选中的文本块。 */
@Composable
internal fun CodeSection(
    title: String,
    text: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                text,
                fontFamily = CodeFontFamily,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 22.sp),
                softWrap = false,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
            )
        }
    }
}

private val prettyJson = Json { prettyPrint = true }

internal fun prettyJson(input: JsonObject): String = prettyJson.encodeToString(JsonObject.serializer(), input)
