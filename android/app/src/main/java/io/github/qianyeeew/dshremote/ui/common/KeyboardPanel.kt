package io.github.qianyeeew.dshremote.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.ui.DesignTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 输入框下面的配置行和选项面板。
// 平时输入框下面常驻一行「模型 · 审批方式」，键盘弹出时这一行收起；点了其中一项，键盘收起，
// 原来键盘的位置换成选项面板，高度和键盘一样，所以输入框不动。点输入框时键盘回来、面板让位。
// ---------------------------------------------------------------------------

/** 还没见过键盘时面板的高度（不含导航条）。 */
private val DefaultPanelHeight = 280.dp

/** 面板从无到有（之前没有键盘）时长高的时长。 */
private const val PANEL_MILLIS = 220

/** 收起面板后等键盘多久：这段时间里键盘开始弹出，面板就跟着键盘让位，否则自己收起。 */
private const val KEYBOARD_WAIT_MILLIS = 150L

/** 最近一次键盘完全弹出时的高度（像素，含导航条）。整个进程共用：每个页面都按同一个键盘来算面板高度。 */
private object KeyboardHeight {
    var px by mutableIntStateOf(0)
}

/**
 * 记下键盘完全弹出时的高度。放在有输入框的页面里，键盘每弹出一次就更新一次。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackKeyboardHeight() {
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    val target = WindowInsets.imeAnimationTarget.getBottom(density)
    LaunchedEffect(ime, target) {
        if (ime > 0 && ime == target) KeyboardHeight.px = ime
    }
}

/**
 * 输入框下面的选项面板。[open] 时出现在原来键盘的位置，高度和键盘一样（不含导航条，导航条那一截也画成面板底色）；
 * 键盘还在的时候只补上键盘让出来的那一截，所以输入框始终不动。面板顶部一行小字 [title]，下面是选项。
 *
 * 外层要用 imePadding（扣掉导航条）把输入框放在键盘上面，面板放在输入框下面。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardPanel(
    open: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    TrackKeyboardHeight()
    val colors = DesignTheme.colors
    val density = LocalDensity.current
    val nav = WindowInsets.navigationBars.getBottom(density)
    val ime = (WindowInsets.ime.getBottom(density) - nav).coerceAtLeast(0)
    val keyboardComing = WindowInsets.imeAnimationTarget.getBottom(density) > 0
    val full =
        if (KeyboardHeight.px > nav) {
            KeyboardHeight.px - nav
        } else {
            with(density) { DefaultPanelHeight.roundToPx() }
        }
    // 面板「有」多少：1 是整块。键盘在的时候直接是 1，只露出键盘让出来的部分
    val shown = remember { Animatable(0f) }
    val currentIme by rememberUpdatedState(ime)
    val coming by rememberUpdatedState(keyboardComing)
    LaunchedEffect(open) {
        if (open) {
            if (currentIme > 0) shown.snapTo(1f) else shown.animateTo(1f, tween(PANEL_MILLIS, easing = FastOutSlowInEasing))
        } else if (shown.value > 0f) {
            // 收起：键盘要回来时等它弹到位再拿掉面板（面板一直补足键盘还没占的那一截）；键盘不来就自己收起
            val keyboard = withTimeoutOrNull(KEYBOARD_WAIT_MILLIS) { snapshotFlow { coming }.first { it } }
            if (keyboard != null) {
                withTimeoutOrNull(KEYBOARD_ANIMATION_LIMIT) { snapshotFlow { currentIme >= full }.first { it } }
                shown.snapTo(0f)
            } else {
                shown.animateTo(0f, tween(PANEL_MILLIS, easing = FastOutSlowInEasing))
            }
        }
    }
    val heightPx = ((full * shown.value).roundToInt() - ime).coerceAtLeast(0)
    if (heightPx == 0) return
    val panelColor = colors.panel
    val border = colors.panelBorder
    Column(
        modifier
            .fillMaxWidth()
            .height(with(density) { heightPx.toDp() })
            // 导航条那一截也画成面板底色，不留一道缝
            .drawBehind { drawRect(panelColor, Offset(0f, size.height), Size(size.width, nav.toFloat())) }
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(panelColor)
            .border(1.dp, border, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .testTag(KEYBOARD_PANEL_TAG),
    ) {
        // 内容按整块的高度排，面板没长全时下面被裁掉，不挤压内容
        Column(
            Modifier
                .fillMaxWidth()
                .height(with(density) { full.toDp() })
                .padding(start = 8.dp, end = 8.dp, top = 10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
            )
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), content = content)
        }
    }
}

/** 键盘弹出的动画一般三百毫秒左右，超过这个时间还没到位就不等了。 */
private const val KEYBOARD_ANIMATION_LIMIT = 600L

/** 审批方式的盾牌颜色：只读灰、改文件不问正常色、全部不问红色。 */
@Composable
fun presetTint(preset: String?): Color {
    val colors = DesignTheme.colors
    return when (preset) {
        "workspace-write" -> colors.text
        "danger-full-access" -> colors.danger
        else -> colors.textSecondary
    }
}

/** 输入框下面的配置行（模型 · 审批方式），后面可以跟一点别的（例如「上下文快满」）。 */
@Composable
fun ComposerOptionsRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/**
 * 配置行里的一项：小图标、文字、小箭头。展开时文字换成主文字色、字重 500，小箭头朝上。
 * 按下时是圆角 8 的底块，比文字大一圈。
 */
@Composable
fun ComposerOption(
    label: String,
    description: String,
    icon: Int,
    iconTint: Color,
    enabled: Boolean,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DesignTheme.colors
    val text = if (open) colors.text else colors.textSecondary
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(14.dp), tint = iconTint.copy(alpha = alpha))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (open) FontWeight.Medium else FontWeight.Normal),
            color = text.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        ChoiceChevron(open, Modifier.size(10.dp), tint = text.copy(alpha = alpha))
    }
}

/** 配置行里两项之间的「·」。 */
@Composable
fun ComposerOptionDot() {
    Text("·", style = MaterialTheme.typography.bodySmall, color = DesignTheme.colors.spinnerTrack)
}

private const val DISABLED_ALPHA = 0.5f

const val KEYBOARD_PANEL_TAG = "keyboard-panel"
