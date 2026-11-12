package io.github.qianyeeew.dshremote.ui.chat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.ui.CodeTextStyles
import io.github.qianyeeew.dshremote.ui.DesignColors
import io.github.qianyeeew.dshremote.ui.DesignTheme
import kotlinx.coroutines.launch

/**
 * 回复里的代码块（设计规格 6.5）：代码底、圆角 14；顶部一行高 44，左边是语言名（灰色等宽小字，不知道就留空），
 * 右边是复制按钮。代码不折行，可以横向滑动，右边缘一段渐隐提示后面还有内容。
 */
@Composable
fun DesignCodeBlock(
    code: String,
    language: String?,
    onCopied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DesignTheme.colors
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val text = code.trimEnd('\n')
    val highlighted = remember(text, language, colors) { highlight(text, language, colors) }
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.codeBackground)
            .testTag("code-block"),
    ) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                language?.trim().orEmpty(),
                style = CodeTextStyles.diff,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", text)))
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onCopied()
                }
            }) {
                Icon(
                    painterResource(R.drawable.ic_copy),
                    stringResource(R.string.chat_code_copy),
                    Modifier.size(18.dp),
                    tint = colors.textSecondary,
                )
            }
        }
        Box(Modifier.fillMaxWidth()) {
            Text(
                highlighted,
                style = CodeTextStyles.block,
                color = colors.text,
                softWrap = false,
                modifier = Modifier.horizontalScroll(scroll).padding(start = 14.dp, end = 32.dp, bottom = 14.dp),
            )
            if (scroll.canScrollForward) {
                Box(Modifier.matchParentSize()) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .width(32.dp)
                            .fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(colors.codeBackground.copy(alpha = 0f), colors.codeBackground))),
                    )
                }
            }
        }
    }
}

/** 三种区别：关键字加粗，字符串用字符串色，注释用次要文字色。 */
internal fun highlight(
    code: String,
    language: String?,
    colors: DesignColors,
): AnnotatedString =
    buildAnnotatedString {
        append(code)
        for (token in codeTokens(code, language)) {
            val style =
                when (token.kind) {
                    TokenKind.KEYWORD -> SpanStyle(fontWeight = FontWeight.SemiBold)
                    TokenKind.STRING -> SpanStyle(color = colors.codeString)
                    TokenKind.COMMENT -> SpanStyle(color = colors.textSecondary)
                }
            addStyle(style, token.start, token.end)
        }
    }
