package io.github.qianyeeew.dshremote.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import io.github.qianyeeew.dshremote.service.Notifier

/** 手机本身的状态：通知权限、电池优化（从系统设置回来时重新读取）。 */
data class DeviceStatus(
    val canNotify: Boolean,
    val ignoringBatteryOptimizations: Boolean,
) {
    companion object {
        fun of(context: Context) =
            DeviceStatus(
                canNotify = Notifier.canPost(context),
                ignoringBatteryOptimizations =
                    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            )
    }
}

internal fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

/** 打开系统设置页。个别系统没有某个页面时，退回到 App 的系统设置。 */
internal fun open(
    context: Context,
    intent: Intent,
) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(appDetails(context))
    }
}
