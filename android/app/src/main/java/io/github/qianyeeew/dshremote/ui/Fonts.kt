package io.github.qianyeeew.dshremote.ui

import android.content.res.Resources
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.qianyeeew.dshremote.R
import android.graphics.fonts.Font as AndroidFont
import android.graphics.fonts.FontFamily as AndroidFontFamily

val CodeFontFamily =
    FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    )

private val ChineseFontFamily =
    FontFamily(
        Font(R.font.noto_sans_sc_regular, FontWeight.Normal),
        Font(R.font.noto_sans_sc_medium, FontWeight.Medium),
        Font(R.font.noto_sans_sc_semibold, FontWeight.SemiBold),
    )

private val combinedFamilyLock = Any()

@Volatile
private var cachedCombinedFamily: FontFamily? = null

/** 字体全部随包提供。Android 10 起由系统按字形使用 Inter → Noto Sans SC 回退链。 */
@Composable
internal fun rememberInterfaceFont(): FontFamily {
    val resources = LocalResources.current
    return remember(resources) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) combinedFamily(resources) else ChineseFontFamily
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun combinedFamily(resources: Resources): FontFamily {
    cachedCombinedFamily?.let { return it }
    return synchronized(combinedFamilyLock) {
        cachedCombinedFamily ?: buildCombinedFamily(resources).also { cachedCombinedFamily = it }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun buildCombinedFamily(resources: Resources): FontFamily {
    fun family(vararg ids: Int): AndroidFontFamily {
        val builder = AndroidFontFamily.Builder(AndroidFont.Builder(resources, ids.first()).build())
        ids.drop(1).forEach { builder.addFont(AndroidFont.Builder(resources, it).build()) }
        return builder.build()
    }
    return FontFamily(
        Typeface
            .CustomFallbackBuilder(family(R.font.inter_regular, R.font.inter_medium, R.font.inter_semibold))
            .addCustomFallback(family(R.font.noto_sans_sc_regular, R.font.noto_sans_sc_medium, R.font.noto_sans_sc_semibold))
            .setSystemFallback("sans-serif")
            .build(),
    )
}
