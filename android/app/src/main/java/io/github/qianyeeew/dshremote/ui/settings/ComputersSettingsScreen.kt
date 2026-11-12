package io.github.qianyeeew.dshremote.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.settings.SettingsRepository
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.common.DesignBackBar
import io.github.qianyeeew.dshremote.ui.common.DesignDivider
import io.github.qianyeeew.dshremote.ui.common.DesignSwitch
import io.github.qianyeeew.dshremote.ui.common.PillButton
import io.github.qianyeeew.dshremote.ui.common.RowChevron
import io.github.qianyeeew.dshremote.ui.common.RowSecondaryText
import io.github.qianyeeew.dshremote.ui.common.TwoLineRow
import io.github.qianyeeew.dshremote.ui.hosts.HostRow
import io.github.qianyeeew.dshremote.ui.hosts.PairMethodDialog
import io.github.qianyeeew.dshremote.ui.hosts.color
import io.github.qianyeeew.dshremote.ui.hosts.lineStatus
import kotlinx.coroutines.launch

/** 「电脑与设置」（设计规格 7.8）：电脑列表和提醒设置合成的一页。 */
@Composable
fun ComputersSettingsScreen(
    settings: SettingsRepository,
    hosts: List<HostRow>,
    currentHostId: String?,
    versionName: String,
    onBack: () -> Unit,
    onOpenHost: (hostId: String) -> Unit,
    onAddHost: (manual: Boolean) -> Unit,
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
    ComputersSettingsContent(
        hosts = hosts,
        currentHostId = currentHostId,
        notificationsEnabled = current?.notificationsEnabled,
        status = status,
        versionName = versionName,
        onBack = onBack,
        onOpenHost = onOpenHost,
        onAddHost = onAddHost,
        onNotifications = { enabled -> scope.launch { settings.setNotificationsEnabled(enabled) } },
        onNotificationSettings = {
            open(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        },
        onRequestPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onBatterySettings = { open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        onAppSettings = { open(context, appDetails(context)) },
    )
}

/** 纯界面层，便于在真机上覆盖权限被拒绝、电池优化没关等状态。 */
@Composable
fun ComputersSettingsContent(
    hosts: List<HostRow>,
    currentHostId: String?,
    notificationsEnabled: Boolean?,
    status: DeviceStatus,
    versionName: String,
    onBack: () -> Unit,
    onOpenHost: (hostId: String) -> Unit,
    onAddHost: (manual: Boolean) -> Unit,
    onNotifications: (Boolean) -> Unit,
    onNotificationSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    onBatterySettings: () -> Unit,
    onAppSettings: () -> Unit,
) {
    val colors = DesignTheme.colors
    var choosingAdd by remember { mutableStateOf(false) }
    var keepAliveGuide by remember { mutableStateOf(false) }
    Scaffold(topBar = { DesignBackBar(onBack) }, containerColor = colors.page) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        ) {
            Caption(stringResource(R.string.computers_section))
            Column(Modifier.padding(top = 4.dp)) {
                hosts.forEachIndexed { index, host ->
                    if (index > 0) DesignDivider()
                    val line = lineStatus(host.status, host.hostId == currentHostId)
                    TwoLineRow(
                        host.name,
                        Modifier.testTag("computer:${host.hostId}"),
                        secondary = { RowSecondaryText(stringResource(line.text), color = line.tone.color()) },
                        trailing = { RowChevron() },
                        onClick = { onOpenHost(host.hostId) },
                    )
                }
                if (hosts.isNotEmpty()) DesignDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickableRow { choosingAdd = true }
                        .testTag("computers-add"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp), tint = colors.text)
                    Text(stringResource(R.string.hosts_add), style = MaterialTheme.typography.bodyMedium, color = colors.text)
                }
                DesignDivider()
            }

            Caption(stringResource(R.string.reminders_section), Modifier.padding(top = 36.dp))
            TwoLineRow(
                stringResource(R.string.reminder_notify),
                Modifier.padding(top = 4.dp),
                titleWeight = FontWeight.Normal,
                secondary = { RowSecondaryText(stringResource(R.string.reminder_notify_detail)) },
                trailing = {
                    DesignSwitch(
                        checked = notificationsEnabled ?: true,
                        onCheckedChange = onNotifications,
                        label = stringResource(R.string.reminder_notify),
                        enabled = notificationsEnabled != null,
                        modifier = Modifier.testTag("notifications-switch"),
                    )
                },
            )
            DesignDivider()
            // 还没设置好时用琥珀色字写差哪一步，点了进现有的引导
            TwoLineRow(
                stringResource(R.string.reminder_keep_alive),
                Modifier.testTag("keep-alive"),
                titleWeight = FontWeight.Normal,
                secondary = {
                    if (status.ignoringBatteryOptimizations) {
                        RowSecondaryText(stringResource(R.string.reminder_keep_alive_ok))
                    } else {
                        RowSecondaryText(stringResource(R.string.reminder_keep_alive_missing), color = colors.waitingText)
                    }
                },
                trailing = { RowChevron() },
                onClick = { keepAliveGuide = true },
            )
            DesignDivider()
            if (!status.canNotify) {
                TwoLineRow(
                    stringResource(R.string.reminder_permission),
                    Modifier.testTag("notification-permission"),
                    titleWeight = FontWeight.Normal,
                    secondary = { RowSecondaryText(stringResource(R.string.reminder_permission_missing), color = colors.waitingText) },
                    trailing = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PillButton(stringResource(R.string.settings_permission_grant), onRequestPermission, primary = true)
                        } else {
                            RowChevron()
                        }
                    },
                    onClick = onNotificationSettings,
                    onClickLabel = stringResource(R.string.settings_permission_open),
                )
                DesignDivider()
            }

            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                Text(
                    "${stringResource(R.string.app_name)} $versionName",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (choosingAdd) {
        PairMethodDialog(
            title = stringResource(R.string.pair_method_title),
            onChoose = { manual ->
                choosingAdd = false
                onAddHost(manual)
            },
            onDismiss = { choosingAdd = false },
        )
    }
    if (keepAliveGuide) {
        // 现有的引导：关闭电池优化、App 的系统设置（小米等需要在这里允许后台运行）
        AlertDialog(
            onDismissRequest = { keepAliveGuide = false },
            title = { Text(stringResource(R.string.reminder_keep_alive)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            if (status.ignoringBatteryOptimizations) R.string.settings_battery_ok else R.string.settings_battery_needed,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.settings_vendor_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(stringResource(R.string.settings_app_details), {
                            keepAliveGuide = false
                            onAppSettings()
                        })
                        PillButton(stringResource(R.string.settings_battery_open), {
                            keepAliveGuide = false
                            onBatterySettings()
                        }, primary = true)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { keepAliveGuide = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Caption(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = DesignTheme.colors.textSecondary, modifier = modifier)
}

@Composable
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = then(Modifier.clickable(role = Role.Button, onClick = onClick))
