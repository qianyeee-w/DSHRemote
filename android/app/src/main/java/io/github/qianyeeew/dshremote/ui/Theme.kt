package io.github.qianyeeew.dshremote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF292F36),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEEF0F2),
        onPrimaryContainer = Color(0xFF24282E),
        secondary = Color(0xFF656C75),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFEEF0F2),
        onSecondaryContainer = Color(0xFF24282E),
        tertiary = Color(0xFF42745D),
        tertiaryContainer = Color(0xFFEBF2ED),
        onTertiaryContainer = Color(0xFF234E39),
        background = Color(0xFFFCFCFB),
        onBackground = Color(0xFF24282E),
        surface = Color(0xFFFCFCFB),
        onSurface = Color(0xFF24282E),
        surfaceVariant = Color(0xFFF2F3F5),
        onSurfaceVariant = Color(0xFF656C75),
        surfaceContainer = Color(0xFFF2F3F5),
        surfaceContainerLow = Color(0xFFF8F9FA),
        surfaceContainerHigh = Color(0xFFEEF0F2),
        surfaceContainerHighest = Color(0xFFE8EBEE),
        surfaceContainerLowest = Color(0xFFFCFCFB),
        outline = Color(0xFF858D98),
        outlineVariant = Color(0xFFE0E3E7),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFE2E6EB),
        onPrimary = Color(0xFF20252A),
        primaryContainer = Color(0xFF272B31),
        onPrimaryContainer = Color(0xFFE4E7EB),
        secondary = Color(0xFFAFB6C0),
        onSecondary = Color(0xFF20252A),
        secondaryContainer = Color(0xFF272B31),
        onSecondaryContainer = Color(0xFFE4E7EB),
        tertiary = Color(0xFF91BDA6),
        tertiaryContainer = Color(0xFF20382C),
        onTertiaryContainer = Color(0xFFBAEAD0),
        background = Color(0xFF17191C),
        onBackground = Color(0xFFE4E7EB),
        surface = Color(0xFF17191C),
        onSurface = Color(0xFFE4E7EB),
        surfaceVariant = Color(0xFF23272D),
        onSurfaceVariant = Color(0xFFAFB6C0),
        surfaceContainer = Color(0xFF23272D),
        surfaceContainerLow = Color(0xFF191C20),
        surfaceContainerHigh = Color(0xFF272B31),
        surfaceContainerHighest = Color(0xFF34383E),
        surfaceContainerLowest = Color(0xFF17191C),
        outline = Color(0xFF89919D),
        outlineVariant = Color(0xFF34383E),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )

/** 业务状态有独立语义，待审批不能使用错误色。 */
data class StatusColors(
    val waiting: Color,
    val waitingContainer: Color,
    val online: Color,
    val running: Color,
    val chatBackground: Color,
    val gridDot: Color,
)

private val LightStatus =
    StatusColors(
        Color(0xFF86551A),
        Color(0xFFF6F0E5),
        Color(0xFF42745D),
        Color(0xFF3C5F93),
        Color(0xFFF8F9FA),
        Color(0xFFE0E4E8),
    )
private val DarkStatus =
    StatusColors(
        Color(0xFFE9C58F),
        Color(0xFF342C21),
        Color(0xFF91BDA6),
        Color(0xFFA5BBDF),
        Color(0xFF191C20),
        Color(0xFF343A43),
    )
val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** 固定中性配色，仍跟随系统明暗；不增加可切换主题功能。 */
@Composable
fun DshRemoteTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val fonts = rememberInterfaceFont()
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = interfaceTypography(fonts),
            shapes =
                Shapes(
                    extraSmall = RoundedCornerShape(6.dp),
                    small = RoundedCornerShape(8.dp),
                    medium = RoundedCornerShape(12.dp),
                    large = RoundedCornerShape(16.dp),
                    extraLarge = RoundedCornerShape(24.dp),
                ),
            content = content,
        )
    }
}

private fun interfaceTypography(font: FontFamily): Typography {
    fun style(
        size: Int,
        height: Int,
        weight: FontWeight = FontWeight.Normal,
    ) = TextStyle(fontFamily = font, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight, letterSpacing = 0.sp)
    return Typography(
        displayLarge = style(40, 48),
        displayMedium = style(34, 42),
        displaySmall = style(28, 36),
        headlineLarge = style(26, 34, FontWeight.SemiBold),
        headlineMedium = style(24, 32, FontWeight.SemiBold),
        headlineSmall = style(22, 30, FontWeight.SemiBold),
        titleLarge = style(18, 26, FontWeight.SemiBold),
        titleMedium = style(15, 22, FontWeight.Medium),
        titleSmall = style(14, 21, FontWeight.Medium),
        bodyLarge = style(15, 26),
        bodyMedium = style(14, 23),
        bodySmall = style(12, 19),
        labelLarge = style(13, 19, FontWeight.Medium),
        labelMedium = style(12, 18),
        labelSmall = style(11, 16),
    )
}
