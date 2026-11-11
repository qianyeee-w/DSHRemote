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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
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
import io.github.qianyeeew.dshremote.ui.common.DesignRow
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
    onManageHosts: (() -> Unit)? = null,
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

    SettingsContent(
        notificationsEnabled = current?.notificationsEnabled,
        status = status,
        versionName = versionName,
        onBack = onBack,
        onNotifications = { enabled -> scope.launch { settings.setNotificationsEnabled(enabled) } },
        onNotificationSettings = {
            open(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        },
        onRequestPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onBatterySettings = { open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        onAppSettings = { open(context, appDetails(context)) },
        onManageHosts = onManageHosts,
    )
}

/** 纯界面层：便于在真机覆盖权限被拒绝、通知关闭等状态。 */
@Composable
fun SettingsContent(
    notificationsEnabled: Boolean?,
    status: DeviceStatus,
    versionName: String,
    onBack: () -> Unit,
    onNotifications: (Boolean) -> Unit,
    onNotificationSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    onBatterySettings: () -> Unit,
    onAppSettings: () -> Unit,
    onManageHosts: (() -> Unit)? = null,
) {
    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
        ) {
            if (onManageHosts != null) {
                SectionCaption(stringResource(R.string.design_connection_management))
                DesignRow(
                    title = stringResource(R.string.design_manage_hosts),
                    icon = R.drawable.ic_dsh_computer,
                    onClick = onManageHosts,
                    trailing = { RowChevron() },
                )
                Spacer(Modifier.height(24.dp))
            }
            SectionCaption(stringResource(R.string.design_notifications_section))
            DesignRow(
                title = stringResource(R.string.settings_notifications),
                icon = R.drawable.ic_dsh_bell,
                subtitle = stringResource(R.string.settings_notifications_detail),
                trailing = {
                    Switch(
                        checked = notificationsEnabled ?: true,
                        onCheckedChange = onNotifications,
                        enabled = notificationsEnabled != null,
                        modifier = Modifier.testTag("notifications-switch"),
                    )
                },
            )
            DesignRow(
                title = stringResource(R.string.design_notification_permission),
                icon = R.drawable.ic_dsh_bell,
                onClick = onNotificationSettings,
                onClickLabel = stringResource(R.string.settings_permission_open),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (status.canNotify) R.string.design_permission_granted else R.string.design_permission_needed),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        RowChevron()
                    }
                },
            )
            if (!status.canNotify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Text(
                    stringResource(R.string.settings_permission_missing),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedButton(onClick = onRequestPermission) { Text(stringResource(R.string.settings_permission_grant)) }
            }
            Spacer(Modifier.height(24.dp))
            SectionCaption(stringResource(R.string.settings_background))
            DesignRow(
                title = stringResource(R.string.design_battery_title),
                icon = R.drawable.ic_dsh_battery,
                subtitle =
                    stringResource(
                        if (status.ignoringBatteryOptimizations) R.string.settings_battery_ok else R.string.settings_battery_needed,
                    ),
                onClick = onBatterySettings,
                onClickLabel = stringResource(R.string.settings_battery_open),
                trailing = { RowChevron() },
            )
            DesignRow(
                title = stringResource(R.string.settings_app_details),
                icon = R.drawable.ic_dsh_settings,
                onClick = onAppSettings,
                trailing = { RowChevron() },
            )
            Text(
                stringResource(R.string.settings_vendor_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(36.dp))
            Text(
                stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun RowChevron() {
    Icon(painterResource(R.drawable.ic_dsh_chevron_right), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
