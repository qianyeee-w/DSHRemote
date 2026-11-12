package io.github.qianyeeew.dshremote.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.ui.common.BlockButton
import io.github.qianyeeew.dshremote.ui.common.DesignDivider
import io.github.qianyeeew.dshremote.ui.common.DesignPopup
import io.github.qianyeeew.dshremote.ui.common.DesignSwitch
import io.github.qianyeeew.dshremote.ui.common.POPUP_TAG
import io.github.qianyeeew.dshremote.ui.common.PillButton
import io.github.qianyeeew.dshremote.ui.common.PillPair
import io.github.qianyeeew.dshremote.ui.common.PillSize
import io.github.qianyeeew.dshremote.ui.common.RowChevron
import io.github.qianyeeew.dshremote.ui.common.RowSecondaryText
import io.github.qianyeeew.dshremote.ui.common.STATUS_RUNNING_TAG
import io.github.qianyeeew.dshremote.ui.common.STATUS_WAITING_TAG
import io.github.qianyeeew.dshremote.ui.common.SecondaryLine
import io.github.qianyeeew.dshremote.ui.common.StatusMark
import io.github.qianyeeew.dshremote.ui.common.StatusMarker
import io.github.qianyeeew.dshremote.ui.common.TwoLineRow
import io.github.qianyeeew.dshremote.ui.common.designPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 设计规格第 5、6 节的通用组件，以及新字体。 */
@RunWith(AndroidJUnit4::class)
class DesignComponentsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun statusMarkersHaveDesignSizesAndStaySilentNextToText() {
        compose.setContent {
            DshRemoteTheme {
                Column {
                    StatusMarker(StatusMark.WAITING)
                    StatusMarker(StatusMark.RUNNING)
                }
            }
        }
        compose.onNodeWithTag(STATUS_WAITING_TAG, useUnmergedTree = true).assertWidthIsEqualTo(8.dp).assertHeightIsEqualTo(8.dp)
        compose.onNodeWithTag(STATUS_RUNNING_TAG, useUnmergedTree = true).assertWidthIsEqualTo(10.dp).assertHeightIsEqualTo(10.dp)
        compose
            .onNodeWithTag(STATUS_WAITING_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun statusMarkerOnItsOwnIsAnnounced() {
        compose.setContent { DshRemoteTheme { StatusMarker(StatusMark.WAITING, description = "等你处理") } }
        compose.onNodeWithContentDescription("等你处理").assertIsDisplayed()
    }

    /** 看上去 32、34、44、48 高，和样稿一致。 */
    @Test
    fun buttonsHaveDesignHeights() {
        compose.setContent {
            DshRemoteTheme {
                Column {
                    PillButton("紧凑", {}, Modifier.testTag("compact"), size = PillSize.COMPACT)
                    PillButton("选项", {}, Modifier.testTag("regular"))
                    BlockButton("允许", {}, Modifier.fillMaxWidth().testTag("block"), primary = true)
                    BlockButton("重新配对", {}, Modifier.fillMaxWidth().testTag("tall"), primary = true, tall = true)
                }
            }
        }
        compose.onNodeWithTag("compact").assertHeightIsEqualTo(32.dp)
        compose.onNodeWithTag("regular").assertHeightIsEqualTo(34.dp)
        compose.onNodeWithTag("block").assertHeightIsEqualTo(44.dp)
        compose.onNodeWithTag("tall").assertHeightIsEqualTo(48.dp)
    }

    /** 看上去只有 32 高的胶囊，在它上下各 7dp 处点击仍然算点中：可点区域不小于 48dp。 */
    @Test
    fun compactPillStillHas48dpTouchTarget() {
        var clicks = 0
        compose.setContent {
            DshRemoteTheme {
                Box(Modifier.padding(40.dp)) {
                    PillButton("允许", { clicks++ }, Modifier.testTag("pill"), primary = true, size = PillSize.COMPACT)
                }
            }
        }
        val outside = with(compose.density) { 7.dp.toPx() }
        compose.onNodeWithTag("pill").performTouchInput { click(Offset(centerX, -outside)) }
        compose.onNodeWithTag("pill").performTouchInput { click(Offset(centerX, height + outside)) }
        assertEquals(2, clicks)
        // 远处不算
        compose.onNodeWithTag("pill").performTouchInput { click(Offset(centerX, height + outside * 4)) }
        assertEquals(2, clicks)
    }

    @Test
    fun disabledPillDoesNotClick() {
        var clicks = 0
        compose.setContent {
            DshRemoteTheme {
                Column {
                    PillButton("允许", { clicks++ }, Modifier.testTag("on"), primary = true)
                    PillButton("拒绝", { clicks += 10 }, Modifier.testTag("off"), enabled = false)
                }
            }
        }
        compose.onNodeWithTag("on").performClick()
        compose.onNodeWithTag("off").performClick()
        assertEquals(1, clicks)
    }

    /** 两个胶囊并排时主操作在右边（设计规格 6.2）。 */
    @Test
    fun primaryPillSitsOnTheRight() {
        var chosen = ""
        compose.setContent { DshRemoteTheme { PillPair("拒绝", { chosen = "deny" }, "允许", { chosen = "allow" }) } }
        val deny = compose.onNodeWithText("拒绝").getUnclippedBoundsInRoot()
        val allow = compose.onNodeWithText("允许").getUnclippedBoundsInRoot()
        assertTrue("允许 should be right of 拒绝", allow.left > deny.right)
        compose.onNodeWithText("允许").performClick()
        assertEquals("allow", chosen)
    }

    @Test
    fun switchTogglesAndKeepsTouchTarget() {
        var on by mutableStateOf(true)
        compose.setContent { DshRemoteTheme { DesignSwitch(on, { on = it }, "需要我时通知", Modifier.testTag("switch")) } }
        compose
            .onNodeWithTag("switch")
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOn()
            .performClick()
            .assertIsOff()
        assertEquals(false, on)
    }

    @Test
    fun twoLineRowShowsBothLinesAndClicks() {
        var opened = 0
        compose.setContent {
            DshRemoteTheme {
                Column {
                    TwoLineRow(
                        "工作电脑",
                        Modifier.testTag("row"),
                        secondary = { RowSecondaryText("已连接，正在使用") },
                        trailing = { RowChevron() },
                        onClick = { opened++ },
                    )
                    DesignDivider()
                }
            }
        }
        compose.onNodeWithText("已连接，正在使用").assertIsDisplayed()
        compose.onNodeWithTag("row").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, opened)
    }

    /** 浮层贴着触发控件出现，返回键收起。 */
    @Test
    fun popupShowsAndDismisses() {
        var shown by mutableStateOf(true)
        compose.setContent {
            DshRemoteTheme {
                Box(Modifier.padding(24.dp)) {
                    Text("工作电脑")
                    if (shown) {
                        DesignPopup(onDismiss = { shown = false }) {
                            Text("电脑与设置", Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag(POPUP_TAG).assertIsDisplayed()
        compose.onNodeWithText("电脑与设置").assertIsDisplayed()
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(false, shown)
        compose.onNodeWithTag(POPUP_TAG).assertDoesNotExist()
    }

    /**
     * 界面字体是随包的 IBM Plex Sans SC，三个字重各自独立；并且是进程级缓存：
     * 另建一个解析器拿到的仍是同一个 Typeface，不会重新加载约 8.5 MB 的字库。
     */
    @Test
    fun interfaceFontIsBundledPlexAndLoadedOnce() {
        val first = createFontFamilyResolver(context)
        val second = createFontFamilyResolver(context)
        val regular = first.resolve(InterfaceFontFamily, FontWeight.Normal).value as Typeface
        assertSame(regular, second.resolve(InterfaceFontFamily, FontWeight.Normal).value)
        assertSame(regular, ResourcesCompat.getFont(context, R.font.ibm_plex_sans_sc_regular))
        val semibold = first.resolve(InterfaceFontFamily, FontWeight.SemiBold).value as Typeface
        assertSame(semibold, ResourcesCompat.getFont(context, R.font.ibm_plex_sans_sc_semibold))
        assertNotSame(regular, semibold)
        val paint = Paint().apply { typeface = regular }
        assertTrue(paint.hasGlyph("中"))
        assertTrue(paint.hasGlyph("A"))
        val code = first.resolve(CodeFontFamily, FontWeight.SemiBold).value as Typeface
        assertSame(code, ResourcesCompat.getFont(context, R.font.jetbrains_mono_semibold))
    }

    /** 给人看的样张：浅色和深色各一张，存在 App 的外部文件目录。 */
    @Test
    fun saveComponentSheets() {
        var dark by mutableStateOf(false)
        compose.setContent { DshRemoteTheme(dark = dark) { Surface(color = DesignTheme.colors.page) { ComponentSheet() } } }
        save("components-light")
        dark = true
        save("components-dark")
    }

    @Composable
    private fun ComponentSheet() {
        val colors = DesignTheme.colors
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.page)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("工作电脑", style = MaterialTheme.typography.titleLarge)
            Column(
                Modifier.fillMaxWidth().designPanel(RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                TwoLineRow(
                    "整理通知去重",
                    leading = { StatusMarker(StatusMark.WAITING) },
                    secondary = { RowSecondaryText("想运行 npm test", color = colors.waitingText, kind = SecondaryLine.LIST) },
                    trailing = { PillPair("拒绝", {}, "允许", {}) },
                )
                DesignDivider()
                TwoLineRow(
                    "补测试并跑通",
                    leading = { StatusMarker(StatusMark.RUNNING) },
                    secondary = { RowSecondaryText("运行 ./gradlew test", kind = SecondaryLine.LIST) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("保留原时间", {})
                PillButton("用导入时间", {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BlockButton("拒绝", {}, Modifier.weight(1f))
                BlockButton("允许", {}, Modifier.weight(1f), primary = true)
            }
            TwoLineRow("需要我时通知", secondary = { RowSecondaryText("审批、提问、任务结束") }, titleWeight = FontWeight.Normal, trailing = {
                DesignSwitch(true, {}, "需要我时通知")
            })
            DesignDivider()
            TwoLineRow("锁屏后保持连接", secondary = {
                RowSecondaryText("还差一步：关闭电池优化", color = colors.waitingText)
            }, titleWeight = FontWeight.Normal, trailing = {
                DesignSwitch(false, {}, "锁屏后保持连接")
            })
            DesignDivider()
            Text("找到原因了。同一个审批会从两条路径进来，去重用的键不一样。", style = MaterialTheme.typography.bodyLarge)
            Text(
                "val key = \"host:\$id\"",
                style = CodeTextStyles.block,
                modifier = Modifier.fillMaxWidth().background(colors.codeBackground, RoundedCornerShape(14.dp)).padding(14.dp),
            )
            Text("失败：推送被拒绝", color = colors.danger, style = MaterialTheme.typography.bodySmall)
        }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "visual-verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
