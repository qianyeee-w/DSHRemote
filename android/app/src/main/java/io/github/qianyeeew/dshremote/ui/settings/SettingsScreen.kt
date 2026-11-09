package io.github.qianyeeew.dshremote.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.settings.SettingsRepository
import io.github.qianyeeew.dshremote.service.Notifier
import io.github.qianyeeew.dshremote.ui.common.BackTopBar
import kotlinx.coroutines.launch

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

/** 设置页（m2-android.md 3.6、3.7）：通知开关、通知权限、电池优化和厂商省电设置的引导。 */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    versionName: String,
    onBack: () -> Unit,
    onPermissionGranted: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var status by remember { mutableStateOf(DeviceStatus.of(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = DeviceStatus.of(context) }
    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status = DeviceStatus.of(context)
            if (granted) onPermissionGranted()
        }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_notifications), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_notifications_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = current?.notificationsEnabled ?: true,
                    onCheckedChange = { enabled -> scope.launch { settings.setNotificationsEnabled(enabled) } },
                    enabled = current != null,
                    modifier = Modifier.padding(start = 12.dp).testTag("notifications-switch"),
                )
            }
            if (!status.canNotify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Text(stringResource(R.string.settings_permission_missing), color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text(stringResource(R.string.settings_permission_grant))
                    }
                    // 用户拒绝过两次后系统不再弹窗，只能去通知设置里打开
                    OutlinedButton(onClick = {
                        open(
                            context,
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }) { Text(stringResource(R.string.settings_permission_open)) }
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.settings_background), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (status.ignoringBatteryOptimizations) R.string.settings_battery_ok else R.string.settings_battery_needed),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!status.ignoringBatteryOptimizations) {
                // 打开系统的电池优化列表（不需要额外权限），由用户自己选择
                OutlinedButton(onClick = { open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                    Text(stringResource(R.string.settings_battery_open))
                }
            }
            Text(stringResource(R.string.settings_vendor_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { open(context, appDetails(context)) }) { Text(stringResource(R.string.settings_app_details)) }

            HorizontalDivider()
            Text(
                stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
