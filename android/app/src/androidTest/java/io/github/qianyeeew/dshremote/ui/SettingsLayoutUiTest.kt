package io.github.qianyeeew.dshremote.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
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
import io.github.qianyeeew.dshremote.ui.settings.DeviceStatus
import io.github.qianyeeew.dshremote.ui.settings.SettingsContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsLayoutUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun notificationSwitchAndDeniedPermissionKeepTheirActions() {
        var enabled by mutableStateOf(true)
        var requested = 0
        var settingsOpened = 0
        compose.setContent {
            DshRemoteTheme {
                SettingsContent(
                    notificationsEnabled = enabled,
                    status = DeviceStatus(false, true),
                    versionName = "test",
                    onBack = {},
                    onNotifications = { enabled = it },
                    onNotificationSettings = { settingsOpened++ },
                    onRequestPermission = { requested++ },
                    onBatterySettings = {},
                    onAppSettings = {},
                )
            }
        }
        compose
            .onNodeWithTag("notifications-switch")
            .assertIsOn()
            .performClick()
            .assertIsOff()
        compose.onNodeWithText(context.getString(R.string.settings_permission_grant)).performClick()
        compose.onNodeWithText(context.getString(R.string.design_notification_permission)).performClick()
        val directory = File(context.getExternalFilesDir(null), "visual-verification").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "settings.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.runOnIdle {
            assertEquals(1, requested)
            assertEquals(1, settingsOpened)
        }
    }
}
