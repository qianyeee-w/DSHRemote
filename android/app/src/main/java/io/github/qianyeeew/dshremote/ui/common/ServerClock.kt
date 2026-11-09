package io.github.qianyeeew.dshremote.ui.common

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.qianyeeew.dshremote.R
import kotlin.math.abs

/** 小于这个差距时认为两边的时钟一致（`Date` 头只精确到秒，再加上网络延迟）。 */
private const val IGNORED_OFFSET_MILLIS = 60_000L

/**
 * 按电脑的时钟算出的「现在」。会话的时间来自电脑，手机和电脑的时钟可能差很多（时区设置错误、没有自动对时），
 * 直接和手机的时间比会显示成「8 小时后」之类。
 */
fun serverNow(
    localNow: Long,
    clockOffsetMillis: Long,
): Long = if (abs(clockOffsetMillis) < IGNORED_OFFSET_MILLIS) localNow else localNow + clockOffsetMillis

/** 「多久以前」：[time] 和 [now] 都按电脑的时钟。不到一分钟（包括比 [now] 还晚的估算误差）显示「刚刚」。 */
@Composable
fun relativeTime(
    time: Long,
    now: Long,
): String =
    if (now - time < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.time_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
