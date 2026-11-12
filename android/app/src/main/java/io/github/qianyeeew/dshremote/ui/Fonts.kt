package io.github.qianyeeew.dshremote.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.qianyeeew.dshremote.R

/**
 * 界面和正文：IBM Plex Sans SC。它同时带中文和西文字形，所以所有系统版本都只用这一个字族，
 * 不再需要 Android 10 起的自定义回退链，Android 8、9 也不用另走一条分支。两者都没有的字形（例如 emoji）
 * 由系统字体回退。
 *
 * 字族是进程级的单例：Compose 的字体解析器和 `ResourcesCompat` 都按资源缓存已加载的 Typeface，
 * 每个约 8.5 MB 的字重在一个进程里只加载一次。不要在 Composable 里新建字族或用 `Font.Builder` 读字体文件——
 * 上一次改版这样做时，每次创建界面都重新加载中文字库，真机测试进程因此内存不足。
 */
val InterfaceFontFamily =
    FontFamily(
        Font(R.font.ibm_plex_sans_sc_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_sc_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_sc_semibold, FontWeight.SemiBold),
    )

/** 代码：JetBrains Mono，400 和 600（关键字加粗）。 */
val CodeFontFamily =
    FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    )
