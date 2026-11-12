package io.github.qianyeeew.dshremote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计规格（docs/design/ui-redesign/README.md）第 3 节的颜色。
 *
 * 只有一种信号色：琥珀色只表示「等你处理」，危险色只用于失败、被拒绝的连接和删除，其余是黑、灰、白。
 * Material 组件用的 [ColorScheme] 也从这里推出，见 [colorSchemeOf]。
 */
@Immutable
data class DesignColors(
    /** 所有页面的背景。 */
    val page: Color,
    /** 进行中卡片、待处理面板、输入框、浮层。 */
    val panel: Color,
    /** 上述面板的 1dp 描边。 */
    val panelBorder: Color,
    /** 展开的输入框的描边。 */
    val focusBorder: Color,
    /** 用户气泡、次要按钮、选项胶囊、行内代码、空闲的发送键。 */
    val fill: Color,
    /** 代码块、改动对比、命令输出。 */
    val codeBackground: Color,
    /** 列表行之间、面板内部。 */
    val divider: Color,
    /** 正文、标题、主按钮的底色。 */
    val text: Color,
    /** 说明、时间、图标、未选中的切换。在页面底上对比度约 5.9:1，新加的灰色文字不要比它浅。 */
    val textSecondary: Color,
    /** 待处理的圆点、上下文快满时的外圈。 */
    val waitingDot: Color,
    /** 「想运行命令」「还剩 4:32」这类等你处理的提示文字。 */
    val waitingText: Color,
    /** 失败、已拒绝连接、移除。 */
    val danger: Color,
    /** 运行中标记的浅色部分。 */
    val spinnerTrack: Color,
    /** 发送键外圈的轨道。 */
    val ringTrack: Color,
    /** 发送键外圈的进度。 */
    val ringProgress: Color,
    /** 改动对比里新增行的整行底色。 */
    val diffAdded: Color,
    /** 改动对比里删除行的整行底色。 */
    val diffRemoved: Color,
    /** 代码高亮里唯一的额外颜色。 */
    val codeString: Color,
    /** 附件缩略图加载前。 */
    val imagePlaceholder: Color,
) {
    /** 输入框展开、电脑菜单弹出时盖在后面页面上的一层。 */
    val scrim: Color get() = page.copy(alpha = 0.7f)

    /** 浮层的阴影颜色：主文字的 10%。 */
    val shadow: Color get() = text.copy(alpha = 0.1f)
}

internal val LightDesignColors =
    DesignColors(
        page = Color(0xFFFAFAF8),
        panel = Color(0xFFFFFFFF),
        panelBorder = Color(0xFFE3E2DD),
        focusBorder = Color(0xFF16181C),
        fill = Color(0xFFF1F0EC),
        codeBackground = Color(0xFFF4F3EF),
        divider = Color(0xFFE9E8E3),
        text = Color(0xFF16181C),
        textSecondary = Color(0xFF5F646C),
        waitingDot = Color(0xFFC77700),
        waitingText = Color(0xFF8A5200),
        danger = Color(0xFFB3261E),
        spinnerTrack = Color(0xFFD5D4CE),
        ringTrack = Color(0xFFE3E2DD),
        ringProgress = Color(0xFF5F646C),
        diffAdded = Color(0xFFE2ECE1),
        diffRemoved = Color(0xFFF3E3DF),
        codeString = Color(0xFF46607A),
        imagePlaceholder = Color(0xFFECEBE6),
    )

/** 深色没有出稿，是按浅色的关系推出来的起点（设计规格第 3 节），真机看过后再调。表里没有的几项按同样的关系补。 */
internal val DarkDesignColors =
    DesignColors(
        page = Color(0xFF131416),
        panel = Color(0xFF1B1D20),
        panelBorder = Color(0xFF34373C),
        focusBorder = Color(0xFFECEDEF),
        fill = Color(0xFF24272B),
        codeBackground = Color(0xFF1E2023),
        divider = Color(0xFF2A2D31),
        text = Color(0xFFECEDEF),
        textSecondary = Color(0xFFA0A5AD),
        waitingDot = Color(0xFFE0A040),
        waitingText = Color(0xFFE9C58F),
        danger = Color(0xFFFFB4AB),
        spinnerTrack = Color(0xFF3A3D42),
        ringTrack = Color(0xFF34373C),
        ringProgress = Color(0xFFA0A5AD),
        diffAdded = Color(0xFF1F2E22),
        diffRemoved = Color(0xFF3A2523),
        codeString = Color(0xFF9DB4CC),
        imagePlaceholder = Color(0xFF24272B),
    )

val LocalDesignColors = staticCompositionLocalOf { LightDesignColors }

/** 新界面的颜色，在 Composable 里用 `DesignTheme.colors.xxx` 取。 */
object DesignTheme {
    val colors: DesignColors
        @Composable get() = LocalDesignColors.current
}

/**
 * Material 组件按设计颜色上色。没有对应设计色的槽位一律落在黑、灰、白上，不引入新的颜色。
 * 主按钮（primary）是主文字色，容器类是浅填充，面板类（surfaceContainerLowest）是白。
 */
internal fun colorSchemeOf(
    c: DesignColors,
    dark: Boolean,
): ColorScheme {
    val onDanger = if (dark) Color(0xFF690005) else Color.White
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.text,
        onPrimary = c.panel,
        primaryContainer = c.fill,
        onPrimaryContainer = c.text,
        inversePrimary = c.textSecondary,
        secondary = c.textSecondary,
        onSecondary = c.panel,
        secondaryContainer = c.fill,
        onSecondaryContainer = c.text,
        tertiary = c.textSecondary,
        onTertiary = c.panel,
        tertiaryContainer = c.fill,
        onTertiaryContainer = c.text,
        background = c.page,
        onBackground = c.text,
        surface = c.page,
        onSurface = c.text,
        surfaceVariant = c.fill,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = Color.Transparent,
        inverseSurface = c.text,
        inverseOnSurface = c.page,
        error = c.danger,
        onError = onDanger,
        errorContainer = c.diffRemoved,
        onErrorContainer = c.danger,
        outline = c.textSecondary,
        outlineVariant = c.divider,
        scrim = c.text,
        surfaceBright = c.panel,
        surfaceDim = c.fill,
        surfaceContainerLowest = c.panel,
        surfaceContainerLow = c.codeBackground,
        surfaceContainer = c.fill,
        surfaceContainerHigh = c.fill,
        surfaceContainerHighest = c.imagePlaceholder,
    )
}

/** 跟随系统明暗；不增加可切换主题功能。 */
@Composable
fun DshRemoteTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkDesignColors else LightDesignColors
    CompositionLocalProvider(LocalDesignColors provides colors) {
        MaterialTheme(
            colorScheme = colorSchemeOf(colors, dark),
            typography = InterfaceTypography,
            shapes =
                Shapes(
                    extraSmall = RoundedCornerShape(5.dp),
                    small = RoundedCornerShape(12.dp),
                    medium = RoundedCornerShape(14.dp),
                    large = RoundedCornerShape(18.dp),
                    extraLarge = RoundedCornerShape(22.dp),
                ),
            content = content,
        )
    }
}

private fun style(
    font: FontFamily,
    size: TextUnit,
    height: TextUnit,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(fontFamily = font, fontSize = size, lineHeight = height, fontWeight = weight, letterSpacing = 0.sp)

/**
 * 设计规格第 4 节的字号表落到 Material 的槽位上：
 *
 * | 槽位 | 用途 |
 * |---|---|
 * | titleLarge 17/24 600 | 首页的电脑名、单台电脑的名字 |
 * | titleMedium 15/22 500 | 顶栏里的会话名、列表行标题 |
 * | titleSmall 15/24 600 | 面板里的问题、整行的大按钮 |
 * | bodyLarge 15/26 400 | 回复正文 |
 * | bodyMedium 15/24 400 | 用户气泡、输入框里的字 |
 * | bodySmall 13/20 400 | 说明、状态 |
 * | labelLarge 13/18 400 | 胶囊按钮（主按钮另加粗到 600） |
 * | labelMedium 12.5/18 400 | 列表行第二行 |
 * | labelSmall 12/16 400 | 顶栏第二行、时间、角标 |
 *
 * 页面不加大标题，display、headline 只为 Material 组件的默认值保留，不在新界面里用。
 * 等宽的几种见 [CodeTextStyles]。
 */
internal val InterfaceTypography: Typography =
    InterfaceFontFamily.let { f ->
        Typography(
            displayLarge = style(f, 32.sp, 40.sp),
            displayMedium = style(f, 28.sp, 36.sp),
            displaySmall = style(f, 24.sp, 32.sp),
            headlineLarge = style(f, 22.sp, 30.sp, FontWeight.SemiBold),
            headlineMedium = style(f, 20.sp, 28.sp, FontWeight.SemiBold),
            headlineSmall = style(f, 18.sp, 26.sp, FontWeight.SemiBold),
            titleLarge = style(f, 17.sp, 24.sp, FontWeight.SemiBold),
            titleMedium = style(f, 15.sp, 22.sp, FontWeight.Medium),
            titleSmall = style(f, 15.sp, 24.sp, FontWeight.SemiBold),
            bodyLarge = style(f, 15.sp, 26.sp),
            bodyMedium = style(f, 15.sp, 24.sp),
            bodySmall = style(f, 13.sp, 20.sp),
            labelLarge = style(f, 13.sp, 18.sp),
            labelMedium = style(f, 12.5.sp, 18.sp),
            labelSmall = style(f, 12.sp, 16.sp),
        )
    }

/** 设计规格第 4 节里的等宽字号。代码的关键字用 [FontWeight.SemiBold]。 */
object CodeTextStyles {
    /** 代码块。 */
    val block = style(CodeFontFamily, 12.5.sp, 20.sp)

    /** 改动对比、命令输出。 */
    val diff = style(CodeFontFamily, 12.sp, 20.sp)

    /** 行内代码。 */
    val inline = style(CodeFontFamily, 13.sp, 20.sp)

    /** 列表和步骤行里的命令、文件名。 */
    val command = style(CodeFontFamily, 12.5.sp, 18.sp)

    /** 审批面板里的命令或文件（设计规格 7.4）。 */
    val approval = style(CodeFontFamily, 14.sp, 22.sp)
}
