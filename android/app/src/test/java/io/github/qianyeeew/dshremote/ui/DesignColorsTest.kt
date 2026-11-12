package io.github.qianyeeew.dshremote.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设计规格（docs/design/ui-redesign/README.md）第 3 节的颜色。 */
class DesignColorsTest {
    @Test
    fun lightColorsMatchTheSpec() {
        val c = LightDesignColors
        val expected =
            mapOf(
                "page" to (c.page to 0xFAFAF8),
                "panel" to (c.panel to 0xFFFFFF),
                "panelBorder" to (c.panelBorder to 0xE3E2DD),
                "focusBorder" to (c.focusBorder to 0x16181C),
                "fill" to (c.fill to 0xF1F0EC),
                "codeBackground" to (c.codeBackground to 0xF4F3EF),
                "divider" to (c.divider to 0xE9E8E3),
                "text" to (c.text to 0x16181C),
                "textSecondary" to (c.textSecondary to 0x5F646C),
                "waitingDot" to (c.waitingDot to 0xC77700),
                "waitingText" to (c.waitingText to 0x8A5200),
                "danger" to (c.danger to 0xB3261E),
                "spinnerTrack" to (c.spinnerTrack to 0xD5D4CE),
                "ringTrack" to (c.ringTrack to 0xE3E2DD),
                "ringProgress" to (c.ringProgress to 0x5F646C),
                "diffAdded" to (c.diffAdded to 0xE2ECE1),
                "diffRemoved" to (c.diffRemoved to 0xF3E3DF),
                "codeString" to (c.codeString to 0x46607A),
                "imagePlaceholder" to (c.imagePlaceholder to 0xECEBE6),
            )
        expected.forEach { (name, pair) -> assertEquals(name, Color(0xFF000000 or pair.second.toLong()), pair.first) }
    }

    @Test
    fun darkColorsMatchTheSuggestedValues() {
        val c = DarkDesignColors
        val expected =
            mapOf(
                "page" to (c.page to 0x131416),
                "panel" to (c.panel to 0x1B1D20),
                "panelBorder" to (c.panelBorder to 0x34373C),
                "fill" to (c.fill to 0x24272B),
                "codeBackground" to (c.codeBackground to 0x1E2023),
                "divider" to (c.divider to 0x2A2D31),
                "text" to (c.text to 0xECEDEF),
                "textSecondary" to (c.textSecondary to 0xA0A5AD),
                "waitingDot" to (c.waitingDot to 0xE0A040),
                "waitingText" to (c.waitingText to 0xE9C58F),
                "danger" to (c.danger to 0xFFB4AB),
                "diffAdded" to (c.diffAdded to 0x1F2E22),
                "diffRemoved" to (c.diffRemoved to 0x3A2523),
                "codeString" to (c.codeString to 0x9DB4CC),
            )
        expected.forEach { (name, pair) -> assertEquals(name, Color(0xFF000000 or pair.second.toLong()), pair.first) }
    }

    /** 文字在它会出现的每一种底色上都要达到 4.5:1。新加的灰色文字不能比次要文字更浅。 */
    @Test
    fun textIsReadableOnEveryBackgroundInBothThemes() {
        for ((theme, c) in listOf("light" to LightDesignColors, "dark" to DarkDesignColors)) {
            val backgrounds =
                mapOf(
                    "page" to c.page,
                    "panel" to c.panel,
                    "fill" to c.fill,
                    "codeBackground" to c.codeBackground,
                    "diffAdded" to c.diffAdded,
                    "diffRemoved" to c.diffRemoved,
                )
            val texts =
                mapOf(
                    "text" to c.text,
                    "textSecondary" to c.textSecondary,
                    "waitingText" to c.waitingText,
                    "danger" to c.danger,
                    "codeString" to c.codeString,
                )
            for ((fgName, fg) in texts) {
                for ((bgName, bg) in backgrounds) {
                    val ratio = contrast(fg, bg)
                    assertTrue("$theme: $fgName on $bgName is $ratio", ratio >= 4.5)
                }
            }
            // 主按钮：主文字色底上的字
            assertTrue("$theme: primary button", contrast(c.panel, c.text) >= 4.5)
        }
    }

    /** 不带文字的信号（琥珀点、上下文外圈、开关关闭时的描边）至少 3:1。 */
    @Test
    fun signalsAreVisibleOnPageAndPanel() {
        for ((theme, c) in listOf("light" to LightDesignColors, "dark" to DarkDesignColors)) {
            for ((bgName, bg) in mapOf("page" to c.page, "panel" to c.panel)) {
                assertTrue("$theme: waitingDot on $bgName", contrast(c.waitingDot, bg) >= 3.0)
                assertTrue("$theme: ringProgress on $bgName", contrast(c.ringProgress, bg) >= 3.0)
                assertTrue("$theme: spinner arc on $bgName", contrast(c.text, bg) >= 3.0)
            }
        }
    }

    /** Material 组件也只用设计里的颜色：主按钮是主文字色，待处理不用错误色。 */
    @Test
    fun materialSchemeIsDerivedFromDesignColors() {
        for ((dark, c) in listOf(false to LightDesignColors, true to DarkDesignColors)) {
            val scheme = colorSchemeOf(c, dark)
            assertEquals(c.page, scheme.background)
            assertEquals(c.page, scheme.surface)
            assertEquals(c.text, scheme.onSurface)
            assertEquals(c.textSecondary, scheme.onSurfaceVariant)
            assertEquals(c.text, scheme.primary)
            assertEquals(c.panel, scheme.onPrimary)
            assertEquals(c.panel, scheme.surfaceContainerLowest)
            assertEquals(c.danger, scheme.error)
            assertEquals(c.divider, scheme.outlineVariant)
            val designed =
                listOf(
                    c.page,
                    c.panel,
                    c.panelBorder,
                    c.focusBorder,
                    c.fill,
                    c.codeBackground,
                    c.divider,
                    c.text,
                    c.textSecondary,
                    c.danger,
                    c.diffRemoved,
                    c.imagePlaceholder,
                    Color.Transparent,
                )
            val used =
                listOf(
                    scheme.primary,
                    scheme.primaryContainer,
                    scheme.onPrimaryContainer,
                    scheme.secondary,
                    scheme.secondaryContainer,
                    scheme.tertiary,
                    scheme.tertiaryContainer,
                    scheme.background,
                    scheme.surface,
                    scheme.surfaceVariant,
                    scheme.surfaceTint,
                    scheme.outline,
                    scheme.outlineVariant,
                    scheme.surfaceContainerLowest,
                    scheme.surfaceContainerLow,
                    scheme.surfaceContainer,
                    scheme.surfaceContainerHigh,
                    scheme.surfaceContainerHighest,
                    scheme.errorContainer,
                )
            used.forEach { assertTrue("dark=$dark: $it is not a design color", it in designed) }
        }
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
