package io.github.qianyeeew.dshremote.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
// 原来键盘的位置换成选项面板，高度跟着选项走（最多和键盘一样高）。点输入框时键盘回来、面板让位；点输入区外面收起面板。
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
 * 输入框下面的选项面板。[open] 时出现在原来键盘的位置。高度跟着内容走，最多和键盘一样高（不含导航条，
 * 导航条那一截也画成面板底色），装不下时里面滚动；选项少时只占它们自己的高度（用户真机走查：两个模型占了半屏）。
 * 键盘还在的时候只补上键盘让出来的那一截，键盘收起时输入框跟着落到面板上沿。面板顶部一行小字 [title]，下面是选项。
 *
 * 打开时把 [onDismiss] 交给页面的 [PanelDismiss]：点输入区以外的地方收起面板（见 [dismissPanelOnOutsideTap]）。
 *
 * 外层要用 imePadding（扣掉导航条）把输入框放在键盘上面，面板放在输入框下面。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardPanel(
    open: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    TrackKeyboardHeight()
    RegisterPanelDismiss(open, onDismiss)
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
    // 内容自己的高度（最多 full）；还没量过时先按 full 算，量到后马上换掉
    var contentPx by remember { mutableIntStateOf(0) }
    val target = if (contentPx > 0) contentPx.coerceAtMost(full) else full
    // 面板「有」多少：1 是整块。键盘在的时候直接是 1，只露出键盘让出来的部分
    val shown = remember { Animatable(0f) }
    val currentIme by rememberUpdatedState(ime)
    val currentTarget by rememberUpdatedState(target)
    val coming by rememberUpdatedState(keyboardComing)
    LaunchedEffect(open) {
        if (open) {
            if (currentIme > 0) shown.snapTo(1f) else shown.animateTo(1f, tween(PANEL_MILLIS, easing = FastOutSlowInEasing))
        } else if (shown.value > 0f) {
            // 收起：键盘要回来时等它弹到位再拿掉面板（面板一直补足键盘还没占的那一截）；键盘不来就自己收起
            val keyboard = withTimeoutOrNull(KEYBOARD_WAIT_MILLIS) { snapshotFlow { coming }.first { it } }
            if (keyboard != null) {
                withTimeoutOrNull(KEYBOARD_ANIMATION_LIMIT) { snapshotFlow { currentIme >= currentTarget }.first { it } }
                shown.snapTo(0f)
            } else {
                shown.animateTo(0f, tween(PANEL_MILLIS, easing = FastOutSlowInEasing))
            }
        }
    }
    val heightPx = ((target * shown.value).roundToInt() - ime).coerceAtLeast(0)
    // 打开时即使还是 0 高也要排一次，才能量到内容的高度
    if (heightPx == 0 && !open) return
    val panelColor = colors.panel
    val border = colors.panelBorder
    val fullDp = with(density) { full.toDp() }
    Column(
        modifier
            .fillMaxWidth()
            .height(with(density) { heightPx.toDp() })
            // 导航条那一截也画成面板底色，不留一道缝
            .drawBehind { if (heightPx > 0) drawRect(panelColor, Offset(0f, size.height), Size(size.width, nav.toFloat())) }
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(panelColor)
            .border(1.dp, border, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .testTag(KEYBOARD_PANEL_TAG),
    ) {
        // 内容按自己的整块高度排，面板没长全时下面被裁掉，不挤压内容
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .heightIn(max = fullDp)
                .onSizeChanged { contentPx = it.height }
                .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
            )
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
        }
    }
}

/**
 * 页面上「点外面收起面板」的登记处。页面用 [rememberPanelDismiss] 建一个，通过 [LocalPanelDismiss] 交给下面的输入区；
 * 页面根部加 [dismissPanelOnOutsideTap]，输入区（输入框和面板）加 [panelArea]，点在输入区以外就收起正在开着的面板。
 */
@Stable
class PanelDismiss {
    internal var onDismiss: (() -> Unit)? = null
    internal var area: Rect? = null
    internal var root: LayoutCoordinates? = null
}

val LocalPanelDismiss = staticCompositionLocalOf<PanelDismiss?> { null }

@Composable
fun rememberPanelDismiss(): PanelDismiss = remember { PanelDismiss() }

@Composable
private fun RegisterPanelDismiss(
    open: Boolean,
    onDismiss: (() -> Unit)?,
) {
    val holder = LocalPanelDismiss.current ?: return
    val current by rememberUpdatedState(onDismiss)
    DisposableEffect(holder, open, onDismiss != null) {
        val dismiss: () -> Unit = { current?.invoke() }
        if (open && onDismiss != null) holder.onDismiss = dismiss
        onDispose { if (holder.onDismiss === dismiss) holder.onDismiss = null }
    }
}

/** 输入区（输入框和下面的面板）：点在这里面不算「外面」。 */
fun Modifier.panelArea(holder: PanelDismiss?): Modifier =
    if (holder == null) this else onGloballyPositioned { holder.area = it.boundsInRoot() }

/**
 * 页面根部：面板开着时，点在输入区以外的地方只收起面板，这一下不再交给下面的控件（不会顺手点开别的）。
 * 拖动（滚动列表）不收起。
 */
fun Modifier.dismissPanelOnOutsideTap(holder: PanelDismiss): Modifier =
    onGloballyPositioned { holder.root = it }
        .pointerInput(holder) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val dismiss = holder.onDismiss ?: return@awaitEachGesture
                val root = holder.root
                val area = holder.area
                if (root != null && area != null && root.isAttached && area.contains(root.localToRoot(down.position))) {
                    return@awaitEachGesture
                }
                var moved = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (!change.pressed) {
                        if (!moved) {
                            change.consume()
                            dismiss()
                        }
                        break
                    }
                }
            }
        }

/**
 * 输入框下面的配置行（模型 · 审批方式）：键盘弹出时**藏在键盘后面**，而不是从布局里拿掉。
 * 这一行只占「键盘没盖住的那一截」高度（至少 [KeyboardGap]），多出来的部分画在键盘底下。
 * 键盘收起时这一行跟着露出来，输入框随键盘一起下移、碰到这一行就停住，只有一段连续的动作；
 * 原来要等键盘完全收起后这一行才长出来，输入框先落到底再被顶上去（用户真机走查）。
 *
 * [exposed] 时整行都占位（面板开着：面板自己补足键盘让出的那一截）。外层要用 imePadding（扣掉导航条）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TuckUnderKeyboard(
    exposed: Boolean,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val nav = WindowInsets.navigationBars.getBottom(density)
    val ime = (WindowInsets.ime.getBottom(density) - nav).coerceAtLeast(0)
    val gap = with(density) { KeyboardGap.roundToPx() }
    Layout(content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val full = placeables.maxOfOrNull { it.height } ?: 0
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = tuckedHeight(full, ime, gap, exposed)
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

/**
 * 配置行占的高度：整行高 [full]，键盘（已扣掉导航条）高 [ime]。键盘盖住的部分不占位，但至少留 [gap]；
 * [exposed] 时占整行。所以输入框底边离屏幕底部始终是 max(整行, 键盘 + 空隙)，随键盘连续变化。
 */
internal fun tuckedHeight(
    full: Int,
    ime: Int,
    gap: Int,
    exposed: Boolean,
): Int = if (exposed) full else (full - ime).coerceAtLeast(minOf(gap, full))

/** 键盘弹出时输入框和键盘之间留的空隙。 */
private val KeyboardGap = 8.dp

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
