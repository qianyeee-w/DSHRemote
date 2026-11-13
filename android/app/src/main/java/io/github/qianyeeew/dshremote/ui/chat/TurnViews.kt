package io.github.qianyeeew.dshremote.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Turn
import io.github.qianyeeew.dshremote.data.api.TurnResult
import io.github.qianyeeew.dshremote.ui.DesignTheme

/**
 * 一轮结束的说法（设计规格 6.7）。**不写「已完成」**：运行结束不等于需求完成。
 * 不认识的结果（[TurnResult.OTHER]、[TurnResult.UNKNOWN]、拿不到）都按「本轮结束」。
 */
@StringRes
fun turnEndLabel(result: TurnResult?): Int =
    when (result) {
        TurnResult.STOPPED -> R.string.turn_stopped
        TurnResult.FAILED -> R.string.turn_failed
        TurnResult.INTERRUPTED -> R.string.turn_interrupted
        TurnResult.COMPLETED, TurnResult.OTHER, TurnResult.UNKNOWN, null -> R.string.turn_ended
    }

/** 用时的几段：小时、分、秒。不到一分钟只有秒，一小时以上不写秒。 */
data class Duration(
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
)

fun durationOf(millis: Long): Duration {
    val total = millis.coerceAtLeast(0) / 1_000
    return Duration(total / 3_600, total % 3_600 / 60, total % 60)
}

/** 「50 秒」「4 分 50 秒」「1 小时 5 分」。 */
@Composable
fun durationText(millis: Long): String {
    val d = durationOf(millis)
    return when {
        d.hours > 0 -> stringResource(R.string.duration_hours, d.hours, d.minutes)
        d.minutes > 0 -> stringResource(R.string.duration_minutes, d.minutes, d.seconds)
        else -> stringResource(R.string.duration_seconds, d.seconds)
    }
}

/** 运行状态行右边的计时：「4:12」「1:04:12」。 */
fun elapsedClock(millis: Long): String {
    val d = durationOf(millis)
    return if (d.hours > 0) {
        "%d:%02d:%02d".format(d.hours, d.minutes, d.seconds)
    } else {
        "%d:%02d".format(d.minutes, d.seconds)
    }
}

/**
 * 一轮的结尾（6.7）：12.5 号灰字，`本轮结束 · 4 分 50 秒 · 61k token · 缓存命中 71%`。失败用红色并跟上原因。
 * 用时要有开始和结束时间才显示；token 数要有这一轮完整的用量（落地方案 B4），缓存命中要每次请求都报告了缓存、分母不为零。
 */
@Composable
fun TurnEndLine(
    turn: Turn,
    modifier: Modifier = Modifier,
) {
    val colors = DesignTheme.colors
    val label = stringResource(turnEndLabel(turn.result))
    val failed = turn.result == TurnResult.FAILED
    val reason = turn.error?.takeIf { failed && it.isNotBlank() }
    val start = turn.startedAt
    val end = turn.endedAt
    val duration = if (start != null && end != null) durationText(end - start) else null
    val separator = stringResource(R.string.separator_dot)
    val tokens = turn.usage?.let { stringResource(R.string.usage_tokens, formatTokens(it.totalTokens)) }
    val cacheHit = turn.usage?.cacheHit?.let { stringResource(R.string.usage_cache_hit, percent(it)) }
    val text =
        buildAnnotatedString {
            withStyle(SpanStyle(color = if (failed) colors.danger else colors.textSecondary)) {
                append(if (reason != null) stringResource(R.string.turn_failed_reason, reason) else label)
            }
            if (duration != null) {
                append(separator)
                append(duration)
            }
            if (tokens != null) {
                append(separator)
                append(tokens)
            }
            if (cacheHit != null) {
                append(separator)
                append(cacheHit)
            }
        }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = colors.textSecondary,
        maxLines = 3,
        modifier = modifier.fillMaxWidth().testTag("turn-end:${turn.turn}"),
    )
}
