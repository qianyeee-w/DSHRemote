package io.github.qianyeeew.dshremote.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.qianyeeew.dshremote.R

/**
 * 开头的一个 Markdown 块标记（M3 T5）：标题 `#`–`######`、引用 `>`、无序列表 `-` `*` `+`、任务框 `[ ]` `[x]`。
 * 和 CommonMark 一致，`#` 和列表符号后面必须是空白或结尾，所以 `#123`、`*强调*`、`---` 不算。
 */
private val LEADING_MARKER = Regex("""^(?:#{1,6}(?=\s|$)|>|[-*+](?=\s|$)|\[[ xX]](?=\s|$))\s*""")

/**
 * 会话标题的显示形式。DSH 用第一条消息生成标题（压成一行），消息以 Markdown 开头时标题会带上「# 」「> 」等符号。
 * 这里只去掉开头的标记，可以叠加（`> - [ ] 任务` → `任务`），正文中间的符号不动。只改显示，协议和插件原样转发。
 *
 * @return 去掉标记后的标题；原标题为空或只有标记时返回 null，由调用方显示「未命名会话」。
 */
fun sessionDisplayTitle(raw: String?): String? {
    var title = raw?.trim() ?: return null
    while (true) {
        val match = LEADING_MARKER.find(title) ?: break
        title = title.substring(match.range.last + 1)
    }
    return title.takeIf(String::isNotBlank)
}

/** 界面中显示的会话标题，没有标题时是「未命名会话」。 */
@Composable
fun sessionTitleText(raw: String?): String = sessionDisplayTitle(raw) ?: stringResource(R.string.sessions_untitled)
