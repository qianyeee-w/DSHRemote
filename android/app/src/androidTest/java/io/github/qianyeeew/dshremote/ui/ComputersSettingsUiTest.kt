package io.github.qianyeeew.dshremote.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.OfflineReason
import io.github.qianyeeew.dshremote.ui.hosts.HostRow
import io.github.qianyeeew.dshremote.ui.hosts.NoHostsContent
import io.github.qianyeeew.dshremote.ui.settings.ComputersSettingsContent
import io.github.qianyeeew.dshremote.ui.settings.DeviceStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 「电脑与设置」（设计规格 7.8）和还没有配对电脑时的首页。 */
@RunWith(AndroidJUnit4::class)
class ComputersSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val address = HostAddress("192.168.1.12", 3290)

    private val hosts =
        listOf(
            HostRow("first", "工作电脑", address, LinkStatus.Online(address)),
            HostRow("second", "开发服务器", null, LinkStatus.Offline(OfflineReason.UNREACHABLE, 1_000)),
            HostRow("third", "实验室服务器", null, LinkStatus.FingerprintMismatch),
        )

    @Test
    fun computersShowTheirStatusAndOpenTheirPage() {
        var opened = ""
        val pairing = mutableListOf<Boolean>()
        render(onOpenHost = { opened = it }, onAddHost = { pairing += it })
        compose.onNodeWithText(text(R.string.host_line_current)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.host_menu_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.host_line_rejected)).assertIsDisplayed()
        compose.onNodeWithTag("computer:third").performClick()
        compose.onNodeWithTag("computers-add").performClick()
        compose.onNodeWithText(text(R.string.pair_method_manual)).performClick()
        compose.runOnIdle {
            assertEquals("third", opened)
            assertEquals(listOf(true), pairing)
        }
        save("computers-settings")
    }

    @Test
    fun notificationSwitchKeepAliveGuideAndPermission() {
        var enabled by mutableStateOf(true)
        var requested = 0
        var settingsOpened = 0
        var battery = 0
        render(
            enabled = { enabled },
            status = DeviceStatus(canNotify = false, ignoringBatteryOptimizations = false),
            onNotifications = { enabled = it },
            onRequestPermission = { requested++ },
            onNotificationSettings = { settingsOpened++ },
            onBatterySettings = { battery++ },
        )
        compose
            .onNodeWithTag("notifications-switch")
            .assertIsOn()
            .performClick()
            .assertIsOff()
        // 还没设置好：琥珀色字写差哪一步，点了进现有的引导
        compose.onNodeWithText(text(R.string.reminder_keep_alive_missing)).assertIsDisplayed()
        compose.onNodeWithTag("keep-alive").performClick()
        compose.onNodeWithText(text(R.string.settings_battery_open)).performClick()
        // 系统通知权限没开时同样提示
        compose.onNodeWithText(text(R.string.reminder_permission_missing)).assertIsDisplayed()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            compose.onNodeWithText(text(R.string.settings_permission_grant)).performClick()
        }
        compose.onNodeWithTag("notification-permission").performClick()
        compose.runOnIdle {
            assertEquals(1, battery)
            assertEquals(1, settingsOpened)
            assertEquals(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 1 else 0, requested)
        }
    }

    @Test
    fun everythingSetUpShowsNoWarnings() {
        render(status = DeviceStatus(canNotify = true, ignoringBatteryOptimizations = true))
        compose.onNodeWithText(text(R.string.reminder_keep_alive_ok)).assertIsDisplayed()
        compose.onNodeWithTag("notification-permission").assertDoesNotExist()
    }

    @Test
    fun noComputersOffersBothPairingMethods() {
        val pairing = mutableListOf<Boolean>()
        compose.setContent { DshRemoteTheme { NoHostsContent("test") { pairing += it } } }
        compose.onNodeWithTag("no-hosts-scan").performClick()
        compose.onNodeWithTag("no-hosts-manual").performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), pairing) }
    }

    private fun render(
        enabled: () -> Boolean = { true },
        status: DeviceStatus = DeviceStatus(canNotify = true, ignoringBatteryOptimizations = false),
        onOpenHost: (String) -> Unit = {},
        onAddHost: (Boolean) -> Unit = {},
        onNotifications: (Boolean) -> Unit = {},
        onNotificationSettings: () -> Unit = {},
        onRequestPermission: () -> Unit = {},
        onBatterySettings: () -> Unit = {},
    ) {
        compose.setContent {
            DshRemoteTheme {
                ComputersSettingsContent(
                    hosts = hosts,
                    currentHostId = "first",
                    notificationsEnabled = enabled(),
                    status = status,
                    versionName = "0.1.0-rc.1",
                    onBack = {},
                    onOpenHost = onOpenHost,
                    onAddHost = onAddHost,
                    onNotifications = onNotifications,
                    onNotificationSettings = onNotificationSettings,
                    onRequestPermission = onRequestPermission,
                    onBatterySettings = onBatterySettings,
                    onAppSettings = {},
                )
            }
        }
    }

    private fun text(id: Int) = context.getString(id)

    private fun save(name: String) {
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "visual-verification").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
