package io.github.qianyeeew.dshremote.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.hosts.HostListContent
import io.github.qianyeeew.dshremote.ui.hosts.HostRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class HostsLayoutUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hostDetailsAndBothPairingMethodsRemainReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var opened = ""
        val pairing = mutableListOf<Boolean>()
        val address = HostAddress("192.168.1.12", 3290)
        compose.setContent {
            DshRemoteTheme {
                HostListContent(
                    rows =
                        listOf(
                            HostRow("first", "开发电脑", address, LinkStatus.Online(address)),
                            HostRow("second", "随身笔记本", null, LinkStatus.Stopped),
                        ),
                    versionName = "test",
                    onOpenHost = { opened = it },
                    onPair = { pairing += it },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("开发电脑").performClick()
        compose.onNodeWithText(context.getString(R.string.pair_method_scan)).performClick()
        compose.onNodeWithText(context.getString(R.string.pair_method_manual)).performClick()
        compose.runOnIdle {
            assertEquals("first", opened)
            assertEquals(listOf(false, true), pairing)
        }
        val directory = File(context.getExternalFilesDir(null), "visual-verification").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "hosts.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
