package io.github.qianyeeew.dshremote.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.SessionUsage
import io.github.qianyeeew.dshremote.ui.DesignTheme
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 用量与上下文（设计规格 6.3、6.7、7.11，落地方案 B4）。每个数字放在它起作用的地方：
// 上下文在发送键的外圈上，本轮用量在这一轮的结尾，长按发送键看整个会话。拿不到的项不显示，不当成 0。
// ---------------------------------------------------------------------------

/** 上下文用到这么多算「快满」：外圈变琥珀色，发送键左边写「上下文快满」。 */
const val CONTEXT_ALMOST_FULL = 0.8f

/** token 数的简写：「850」「1.2k」「59k」「1M」「1.5M」。 */
fun formatTokens(count: Long): String =
    when {
        count < 1_000 -> count.toString()
        count < 10_000 -> trimmed(count / 1_000.0) + "k"
        count < 1_000_000 -> (count / 1_000.0).roundToInt().toString() + "k"
        else -> trimmed(count / 1_000_000.0) + "M"
    }

/** 保留一位小数，末尾的 .0 去掉。 */
private fun trimmed(value: Double): String {
    val tenths = (value * 10).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}

/** 百分比，四舍五入到整数。 */
fun percent(ratio: Float): Int = (ratio * 100).roundToInt()

/** 上下文用了多少的一句人话（7.11）。 */
@StringRes
fun contextPhrase(ratio: Float): Int =
    when {
        ratio >= CONTEXT_ALMOST_FULL -> R.string.usage_context_full
        ratio >= 0.55f -> R.string.usage_context_high
        ratio >= 0.45f -> R.string.usage_context_half
        else -> R.string.usage_context_low
    }

/**
 * 发送键的外圈（6.3）：2.5 粗的细线，从顶部顺时针画到上下文用掉的比例，次要文字色；八成以上琥珀色。
 * [ratio] 为 null 且 [trackOnly] 时只画轨道（新任务还没有上下文）；两者都没有时不画。
 */
@Composable
fun Modifier.contextRing(
    ratio: Float?,
    trackOnly: Boolean = false,
): Modifier {
    if (ratio == null && !trackOnly) return this
    val colors = DesignTheme.colors
    val progress = if (ratio != null && ratio >= CONTEXT_ALMOST_FULL) colors.waitingDot else colors.ringProgress
    return drawBehind {
        val stroke = 2.5.dp.toPx()
        val radius = 19.dp.toPx() - stroke / 2
        val topLeft = Offset(center.x - radius, center.y - radius)
        val size = Size(radius * 2, radius * 2)
        drawArc(colors.ringTrack, 0f, 360f, false, topLeft, size, style = Stroke(stroke))
        if (ratio != null && ratio > 0f) drawArc(progress, -90f, 360f * ratio, false, topLeft, size, style = Stroke(stroke))
    }
}

/**
 * 长按发送键展开的用量（7.11）：上下文一句人话、用量条、「约 59k，上限 128k · 估算值」；分隔线；
 * 这次会话的输入和输出；缓存命中（分母为零时不显示）。拿不到的项不显示；全都拿不到时说一句。
 */
@Composable
fun UsageDetails(usage: SessionUsage?) {
    val colors = DesignTheme.colors
    val label = MaterialTheme.typography.bodySmall
    val context = usage?.context?.takeIf { it.tokens != null }
    val total = usage?.total
    Column(Modifier.fillMaxWidth().padding(end = 10.dp, top = 4.dp, bottom = 4.dp).testTag(USAGE_DETAILS_TAG)) {
        if (context == null && total == null) {
            Text(
                stringResource(R.string.usage_unavailable),
                style = label,
                color = colors.textSecondary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            return@Column
        }
        if (context != null) {
            val ratio = context.ratio
            UsageRow(stringResource(R.string.usage_context), ratio?.let { stringResource(contextPhrase(it)) })
            if (ratio != null) {
                Box(
                    Modifier
                        .padding(vertical = 8.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.ringTrack),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(ratio)
                            .background(if (ratio >= CONTEXT_ALMOST_FULL) colors.waitingDot else colors.ringProgress),
                    )
                }
            }
            val tokens = formatTokens(context.tokens ?: 0)
            Text(
                context.window?.let { stringResource(R.string.usage_context_amount, tokens, formatTokens(it)) }
                    ?: stringResource(R.string.usage_context_amount_no_window, tokens),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 6.dp).testTag("usage-context-amount"),
            )
        }
        if (total != null) {
            UsageRow(
                stringResource(R.string.usage_session),
                stringResource(R.string.usage_session_value, formatTokens(total.inputTokens), formatTokens(total.outputTokens)),
            )
            total.cacheHit?.let { UsageRow(stringResource(R.string.usage_cache), stringResource(R.string.usage_percent, percent(it))) }
        }
    }
}

@Composable
private fun UsageRow(
    title: String,
    value: String?,
) {
    val colors = DesignTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        if (value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = colors.text)
    }
}

const val USAGE_DETAILS_TAG = "usage-details"
