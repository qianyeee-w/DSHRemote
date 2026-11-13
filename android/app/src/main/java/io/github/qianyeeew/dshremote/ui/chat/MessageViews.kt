package io.github.qianyeeew.dshremote.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.PromptMode
import io.github.qianyeeew.dshremote.data.chat.LocalMessage
import io.github.qianyeeew.dshremote.data.chat.OutgoingStatus
import io.github.qianyeeew.dshremote.ui.CodeTextStyles
import io.github.qianyeeew.dshremote.ui.DesignTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 用户气泡（设计规格 6.4）：靠右，浅填充底，圆角 18，最宽 280。下方可以有一行 12 号灰字说明发送状态，正常送达后不显示。
 * 本地发出、还没出现在会话日志里的消息带 [local]。
 */
@Composable
fun UserBubble(
    text: String,
    local: LocalMessage?,
    onCheck: () -> Unit = {},
    onRetry: () -> Unit,
) {
    val colors = DesignTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                modifier =
                    Modifier
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.fill)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        if (local != null) SendStatus(local, onRetry, onCheck)
    }
}

/**
 * 发送状态（设计规格 6.4）：发送中；插话已受理但还没出现在会话里时「已送出，DSH 下一步会读到」；失败时「发送失败 · 重试」；
 * 请求发出但没收到回应时「不确定是否送达 · 查看 · 重发」。空闲时发出的消息受理后马上就会出现在会话里，不再提示。
 * 「重试」「重发」都只由用户点，App 不自动重发（落地方案 B3）。
 */
@Composable
private fun SendStatus(
    local: LocalMessage,
    onRetry: () -> Unit,
    onCheck: () -> Unit,
) {
    val colors = DesignTheme.colors
    val style = MaterialTheme.typography.labelSmall
    when (local.status) {
        OutgoingStatus.SENDING ->
            Text(
                stringResource(R.string.chat_local_sending),
                style = style,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        OutgoingStatus.SENT ->
            if (local.outgoing.mode == PromptMode.STEER) {
                Text(
                    stringResource(R.string.chat_local_steered),
                    style = style,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        OutgoingStatus.FAILED ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_local_failed), style = style, color = colors.danger)
                Text(" · ", style = style, color = colors.textSecondary)
                StatusAction(stringResource(R.string.chat_local_retry), "chat-retry", onRetry)
            }
        OutgoingStatus.UNCERTAIN ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_local_uncertain), style = style, color = colors.waitingText)
                Text(" · ", style = style, color = colors.textSecondary)
                StatusAction(stringResource(R.string.chat_local_check), "chat-check", onCheck)
                Text(" · ", style = style, color = colors.textSecondary)
                StatusAction(stringResource(R.string.chat_local_resend), "chat-resend", onRetry)
            }
    }
}

/** 状态行里可以点的字：下划线，可点区域高 48。 */
@Composable
private fun StatusAction(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline),
        color = DesignTheme.colors.text,
        modifier =
            Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 16.dp)
                .testTag(tag),
    )
}

/**
 * 回复正文（设计规格 6.5）：没有头像、名字和气泡，直接排在页面上，15/26。行内代码用浅填充底、圆角 5；
 * 代码块用 [DesignCodeBlock]。`retainState`：内容变化时，在新内容解析完之前继续显示旧的结果，流式输出时不会闪成空白。
 * 链接的点击由外层的 LocalUriHandler 处理（先确认再打开，见 ChatScreen）。
 */
@Composable
fun MarkdownText(
    text: String,
    onCopied: () -> Unit,
) {
    val colors = DesignTheme.colors
    val typography = MaterialTheme.typography
    val state = rememberMarkdownState(text, retainState = true)
    val components =
        remember(onCopied) {
            markdownComponents(
                codeFence = { model ->
                    MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, _ ->
                        DesignCodeBlock(code, language, onCopied, Modifier.padding(vertical = 4.dp))
                    }
                },
                codeBlock = { model ->
                    MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, _ ->
                        DesignCodeBlock(code, language, onCopied, Modifier.padding(vertical = 4.dp))
                    }
                },
            )
        }
    val body = typography.bodyLarge.copy(color = colors.text)
    SelectionContainer {
        Markdown(
            markdownState = state,
            colors =
                markdownColor(
                    text = colors.text,
                    codeBackground = colors.codeBackground,
                    inlineCodeBackground = colors.fill,
                    dividerColor = colors.divider,
                    tableBackground = colors.codeBackground,
                ),
            // 页面不用大标题：Markdown 的标题最大只到 17 号
            typography =
                markdownTypography(
                    h1 = typography.titleLarge,
                    h2 = typography.titleLarge.copy(fontSize = 16.sp),
                    h3 = body.copy(fontWeight = FontWeight.SemiBold),
                    h4 = body.copy(fontWeight = FontWeight.SemiBold),
                    h5 = body.copy(fontWeight = FontWeight.SemiBold),
                    h6 = body.copy(fontWeight = FontWeight.SemiBold),
                    text = body,
                    code = CodeTextStyles.block,
                    inlineCode = CodeTextStyles.inline.copy(background = colors.fill),
                    quote = body.copy(color = colors.textSecondary),
                    paragraph = body,
                    ordered = body,
                    bullet = body,
                    list = body,
                ),
            padding = markdownPadding(block = 9.dp),
            extendedSpans =
                markdownExtendedSpans {
                    remember {
                        ExtendedSpans(
                            RoundedCornerSpanPainter(
                                cornerRadius = 5.sp,
                                stroke = RoundedCornerSpanPainter.Stroke(color = colors.fill),
                                padding = RoundedCornerSpanPainter.TextPaddingValues(horizontal = 5.sp, vertical = 2.sp),
                            ),
                        )
                    }
                },
            components = components,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 其他工具的详情：沿用之前的参数和结果显示，等宽、可以横向滚动和选中。 */
@Composable
internal fun CodeSection(
    title: String,
    text: String,
) {
    val colors = DesignTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        SelectionContainer {
            Text(
                text,
                style = CodeTextStyles.diff,
                color = colors.text,
                softWrap = false,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.codeBackground)
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
            )
        }
    }
}

private val prettyJson = Json { prettyPrint = true }

internal fun prettyJson(input: JsonObject): String = prettyJson.encodeToString(JsonObject.serializer(), input)
