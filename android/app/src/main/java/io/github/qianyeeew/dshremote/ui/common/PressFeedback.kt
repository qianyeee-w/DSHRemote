package io.github.qianyeeew.dshremote.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// 点击反馈：按下时在控件下面淡入一块圆角浅色底，松手淡出；没有从手指位置扩散的水波纹。
// 全应用的 clickable 默认用它（DshRemoteTheme 里替换 LocalIndication），圆形按钮改用 [pressScale]。
// ---------------------------------------------------------------------------

/** 底块淡入淡出的时长。 */
private const val PRESS_FADE_MILLIS = 100

/** 圆形按钮按下时缩到多大。 */
private const val PRESSED_SCALE = 0.92f

/** 默认的按下底块圆角：小控件和整行都用它，整行看上去是一块圆角矩形。 */
val PressCornerRadius: Dp = 10.dp

/** 按下时画一块 [color] 的圆角矩形（圆角 [radius]），画在内容下面。 */
class RoundedPressIndication(
    private val color: Color,
    private val radius: Dp = PressCornerRadius,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, color, radius)

    override fun equals(other: Any?): Boolean = other is RoundedPressIndication && other.color == color && other.radius == radius

    override fun hashCode(): Int = 31 * color.hashCode() + radius.hashCode()
}

private class PressNode(
    private val interactions: InteractionSource,
    private val color: Color,
    private val radius: Dp,
) : Modifier.Node(),
    DrawModifierNode {
    private val shown = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            interactions.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                val target = if (presses.isEmpty()) 0f else 1f
                launch { shown.animateTo(target, tween(PRESS_FADE_MILLIS)) { invalidateDraw() } }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val alpha = shown.value
        if (alpha > 0f) {
            drawRoundRect(color.copy(alpha = color.alpha * alpha), cornerRadius = CornerRadius(radius.toPx()))
        }
        drawContent()
    }
}

/**
 * 圆形按钮的按下反馈：按下缩到 0.92，松手回弹（不过冲）。和 `clickable(interactionSource = source, indication = null)` 一起用。
 */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) PRESSED_SCALE else 1f,
        if (pressed) tween(PRESS_FADE_MILLIS) else spring(stiffness = 800f),
        label = "press-scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
