package io.github.qianyeeew.dshremote.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.ui.DesignTheme

/*
 * 下面三个是上一版界面的组件，还没改版的页面在用。对应页面按 docs/plans/ui-redesign.md 改完后删除。
 * 新界面用本文件后半部分「新版界面的通用组件」。
 */

/** 图形紧凑，点击区域保留 48dp。 */
@Composable
fun RoundIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(icon),
                    contentDescription = description,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignTopBar(
    title: String,
    navigation: @Composable () -> Unit = {},
    subtitle: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.invoke()
            }
        },
        navigationIcon = navigation,
        actions = actions,
    )
}

/** 设置与主机页面共用的分组行。长说明允许换行，避免字体放大后挤掉操作。 */
@Composable
fun DesignRow(
    title: String,
    @DrawableRes icon: Int,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .let { if (onClick != null) it.clickable(onClickLabel = onClickLabel, onClick = onClick) else it }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// ---------------------------------------------------------------------------
// 新版界面的通用组件（docs/design/ui-redesign/README.md 第 5、6 节）。
// 看上去的尺寸按样稿，布局也按样稿的大小，不为了点击区域把布局撑高。
// 比 48dp 小的可点控件，Compose 的触摸命中会自动把可点区域扩到 48dp（ViewConfiguration.minimumTouchTargetSize），
// DesignComponentsUiTest 在控件外几 dp 处点击来验证这一点。
// ---------------------------------------------------------------------------

/** 状态标记（设计规格 6.1）。失败、已停止、结果待确认用文字表示，不用标记。 */
enum class StatusMark { WAITING, RUNNING }

/**
 * 等你处理：直径 8 的琥珀色实心圆点；运行中：直径 10 的细圆环，四分之一是主文字色，持续旋转。
 * 标记旁边通常有文字说明，所以默认不朗读；单独出现时传 [description]。
 */
@Composable
fun StatusMarker(
    mark: StatusMark,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val semantics =
        if (description != null) {
            Modifier.semantics { contentDescription = description }
        } else {
            Modifier.clearAndSetSemantics {}
        }
    when (mark) {
        StatusMark.WAITING -> WaitingDot(modifier.testTag(STATUS_WAITING_TAG).then(semantics))
        StatusMark.RUNNING -> RunningSpinner(modifier.testTag(STATUS_RUNNING_TAG).then(semantics))
    }
}

@Composable
private fun WaitingDot(modifier: Modifier) {
    val color = DesignTheme.colors.waitingDot
    Canvas(modifier.size(8.dp)) { drawCircle(color) }
}

@Composable
private fun RunningSpinner(modifier: Modifier) {
    val colors = DesignTheme.colors
    val rotation by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "spinner-rotation",
    )
    Canvas(modifier.size(10.dp)) {
        val stroke = 1.5.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawCircle(colors.spinnerTrack, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
        rotate(rotation) {
            drawArc(
                colors.text,
                startAngle = -135f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke),
            )
        }
    }
}

/** 胶囊按钮的两种看上去的高度：卡片里的审批 32，提问的选项 34（设计规格 5、6.2）。 */
enum class PillSize(
    val height: Dp,
) {
    COMPACT(32.dp),
    REGULAR(34.dp),
}

/**
 * 胶囊按钮（设计规格 6.2）。主操作是主文字色底、白字、600；次要操作和提问的选项是浅填充底、主文字色字。
 * 两个并排时主操作放右边，见 [PillPair]。
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    size: PillSize = PillSize.REGULAR,
) {
    val colors = DesignTheme.colors
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .height(size.height)
            .clip(shape)
            .background(if (primary) colors.text else colors.fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            color = if (primary) colors.panel else colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 两个胶囊并排：次要的在左，主操作在右。 */
@Composable
fun PillPair(
    secondary: String,
    onSecondary: () -> Unit,
    primary: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: PillSize = PillSize.COMPACT,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton(secondary, onSecondary, enabled = enabled, size = size)
        PillButton(primary, onPrimary, primary = true, enabled = enabled, size = size)
    }
}

/**
 * 整行的大按钮：审批面板里各占一半的「拒绝」「允许」（看上去高 44、14 号字），
 * 单台电脑页的「重新配对」这类主按钮（[tall]，高 48、15 号字）。全圆角。
 */
@Composable
fun BlockButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    tall: Boolean = false,
) {
    val colors = DesignTheme.colors
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .height(if (tall) 48.dp else 44.dp)
            .clip(shape)
            .background(if (primary) colors.text else colors.fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = if (tall) 15.sp else 14.sp),
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            color = if (primary) colors.panel else colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 列表行之间、面板内部的分隔线。 */
@Composable
fun DesignDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = DesignTheme.colors.divider)
}

/**
 * 两行列表行（设计规格 5、7.1、7.8）：上下内边距 12，两行之间 2，不加卡片，行与行之间由调用方放 [DesignDivider]。
 * 左右边距由外层（页面 20）决定。
 *
 * @param titleWeight 列表标题 500；未读 600；已读的最近会话 400。
 * @param secondary 第二行；为空时只有一行。设置页 13 号，首页最近列表 12.5 号，用 [secondaryStyle] 选。
 * @param leading 行首，例如 [StatusMarker]。
 * @param trailing 行尾，例如时间、小箭头 [RowChevron]、[DesignSwitch]。
 */
@Composable
fun TwoLineRow(
    title: String,
    modifier: Modifier = Modifier,
    secondary: (@Composable () -> Unit)? = null,
    titleWeight: FontWeight = FontWeight.Medium,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let { if (onClick != null) it.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else it }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = titleWeight,
                color = DesignTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            secondary?.invoke()
        }
        trailing?.invoke()
    }
}

/** 列表行第二行的字号：设置和电脑页 13/18，首页最近列表 12.5/18。 */
enum class SecondaryLine { SETTINGS, LIST }

/** [TwoLineRow] 第二行的文字。颜色默认次要文字，状态有问题时传等你（字）或危险色。 */
@Composable
fun RowSecondaryText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DesignTheme.colors.textSecondary,
    kind: SecondaryLine = SecondaryLine.SETTINGS,
) {
    Text(
        text,
        modifier,
        style =
            when (kind) {
                SecondaryLine.SETTINGS -> MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp)
                SecondaryLine.LIST -> MaterialTheme.typography.labelMedium
            },
        color = color,
        maxLines = if (kind == SecondaryLine.LIST) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 行尾的小箭头，16dp，次要文字色。 */
@Composable
fun RowChevron(modifier: Modifier = Modifier) {
    Icon(
        painterResource(R.drawable.ic_dsh_chevron_right),
        contentDescription = null,
        modifier = modifier.size(16.dp),
        tint = DesignTheme.colors.textSecondary,
    )
}

/**
 * 开关（设计规格 7.8）：看上去 48 × 28，打开时主文字色底、白色圆钮靠右。
 * 关闭时是带描边的浅底、灰色圆钮靠左，描边和圆钮用次要文字色，保证关闭状态也能看清。
 * [label] 是开关朗读出来的名字，通常和行标题相同。
 */
@Composable
fun DesignSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DesignTheme.colors
    val offset by animateDpAsState(if (checked) 22.dp else 5.dp, label = "switch-thumb")
    val thumb by animateDpAsState(if (checked) 24.dp else 18.dp, label = "switch-thumb-size")
    Box(
        modifier
            .size(width = 56.dp, height = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 48.dp, height = 28.dp)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(RoundedCornerShape(14.dp))
                .background(if (checked) colors.text else colors.fill)
                .let { if (checked) it else it.border(1.5.dp, colors.textSecondary, RoundedCornerShape(14.dp)) },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .offset { IntOffset(offset.roundToPx(), 0) }
                    .size(thumb)
                    .background(if (checked) colors.panel else colors.textSecondary, CircleShape),
            )
        }
    }
}

/** 面板：进行中卡片、待处理面板、输入框和浮层共用的白底加 1dp 描边。[focused] 时描边换成主文字色（展开的输入框）。 */
@Composable
fun Modifier.designPanel(
    shape: Shape,
    focused: Boolean = false,
): Modifier {
    val colors = DesignTheme.colors
    return this
        .clip(shape)
        .background(colors.panel, shape)
        .border(1.dp, if (focused) colors.focusBorder else colors.panelBorder, shape)
}

/**
 * 浮层（设计规格 6.10）：用量卡片和电脑菜单。面板底、描边、圆角 18、阴影 0 10dp 30dp（主文字的 10%），
 * 贴着触发它的控件出现，点外面收起。
 *
 * 放在触发控件的同一个父布局里调用；[alignment] 和 [offset] 相对于那个父布局。
 * 盖在页面上的那层底色（设计规格第 3 节）由调用方决定要不要画。
 */
@Composable
fun DesignPopup(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DesignTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val room = with(LocalDensity.current) { PopupShadowRoom.roundToPx() }
    val bias = alignment as? BiasAlignment
    Popup(
        alignment = alignment,
        // 四周留给阴影的空白会把浮层推离锚点，按对齐方向抵消回来。
        offset = offset + IntOffset(((bias?.horizontalBias ?: 0f) * room).toInt(), ((bias?.verticalBias ?: 0f) * room).toInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier
                .padding(PopupShadowRoom)
                .dropShadow(shape, Shadow(radius = 30.dp, color = colors.shadow, offset = DpOffset(0.dp, 10.dp)))
                .designPanel(shape)
                .testTag(POPUP_TAG),
            content = content,
        )
    }
}

/** 浮层四周留出画阴影的位置，否则阴影会被弹出窗口的边界裁掉。 */
private val PopupShadowRoom = 40.dp

private const val DISABLED_ALPHA = 0.38f

const val STATUS_WAITING_TAG = "status-waiting"
const val STATUS_RUNNING_TAG = "status-running"
const val POPUP_TAG = "design-popup"
