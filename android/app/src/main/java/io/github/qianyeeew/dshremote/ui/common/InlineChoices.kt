package io.github.qianyeeew.dshremote.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.ui.DesignTheme

// ---------------------------------------------------------------------------
// 原地展开的选择（设计规格 6.10）：选项插在触发它的那一行旁边，把别的内容推开，不盖在任何东西上面。
// 不另开窗口，所以输入框的焦点和键盘都保持原样。全应用不用浮在上面的菜单，也不用居中的选择对话框。
// ---------------------------------------------------------------------------

/** 展开和收起：高度变化加淡入淡出，150–200 毫秒，不弹跳。 */
private const val EXPAND_MILLIS = 180

/** 只有名字的选项行高 48。 */
private val ChoiceRowHeight = 48.dp

/** 带说明的行：上下各 9，名字一行 22、间隔 2、说明一行 18。 */
private val ChoiceRowWithSubtitleHeight = 60.dp

/** 最多同时显示 5 行，超出时在选项区里上下滚动。 */
private const val MAX_VISIBLE_ROWS = 5

/** 选项区最多多高：[withSubtitles] 时按带说明的行算。长名字折成两行时会少显示一点，仍然可以滚动。 */
fun choiceListMaxHeight(withSubtitles: Boolean): Dp =
    (if (withSubtitles) ChoiceRowWithSubtitleHeight else ChoiceRowHeight) * MAX_VISIBLE_ROWS

/**
 * 输入框卡片里的选项区：[visible] 时在原地展开。只是卡片里的普通内容，展开时输入框的焦点不变。
 * 收起的动画期间内容还在，调用方要让 [content] 在收起时仍画最后一次的选项（不要在收起时立刻变空）。
 */
@Composable
fun InlineChoices(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(tween(EXPAND_MILLIS)) + fadeIn(tween(EXPAND_MILLIS)),
        exit = shrinkVertically(tween(EXPAND_MILLIS)) + fadeOut(tween(EXPAND_MILLIS)),
    ) {
        Column(Modifier.fillMaxWidth().testTag(INLINE_CHOICES_TAG), content = content)
    }
}

/** 选项区里可以滚动的那一段：最多显示 5 行。 */
@Composable
fun ChoiceList(
    modifier: Modifier = Modifier,
    withSubtitles: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = choiceListMaxHeight(withSubtitles))
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}

/**
 * 首页的选择面板（电脑、项目筛选）：面板底、面板描边、圆角 20，出现在触发行正下方，下面的内容下移。
 * 左右的 20 由首页列表的内边距提供。
 */
@Composable
fun InlinePanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    InlineChoices(visible, modifier) {
        Column(
            Modifier
                .padding(bottom = 12.dp)
                .designPanel(RoundedCornerShape(20.dp))
                .padding(vertical = 6.dp),
            content = content,
        )
    }
}

/**
 * 选项行（设计规格 6.10）。只有名字的行高 48；带 [subtitle] 的行名字 15 号、说明 12.5 号灰字，上下各 9。
 * 当前选中的一项名字用字重 500，最右边一个勾，不用单选圆圈。名字放不下时折行，不截断。
 * [trailing] 是右边的灰色小字（状态、数量），颜色可以用 [trailingColor] 换掉。
 * [muted] 的名字用次要文字色（例如「不指定项目」）。
 */
@Composable
fun ChoiceRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    muted: Boolean = false,
    trailing: String? = null,
    trailingColor: Color = Color.Unspecified,
    leadingIcon: Int? = null,
    leadingTint: Color = Color.Unspecified,
    trailingIcon: Int? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    val colors = DesignTheme.colors
    val hasSubtitle = !subtitle.isNullOrBlank()
    val selectedLabel = stringResource(R.string.choice_selected)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = ChoiceRowHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(contentPadding)
            .padding(vertical = if (hasSubtitle) 9.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon !=
            null
        ) {
            Icon(painterResource(leadingIcon), null, Modifier.size(18.dp), tint = leadingTint.takeOrElse { colors.text })
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                color = if (muted) colors.textSecondary else colors.text,
            )
            if (hasSubtitle) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            }
        }
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = trailingColor.takeOrElse { colors.textSecondary },
            )
        }
        if (selected) Icon(painterResource(R.drawable.ic_dsh_check), selectedLabel, Modifier.size(18.dp), tint = colors.text)
        if (trailingIcon != null) Icon(painterResource(trailingIcon), null, Modifier.size(16.dp), tint = colors.textSecondary)
    }
}

/** 触发行的小箭头：收起时朝下，展开时朝上（转半圈，和选项区一起动）。 */
@Composable
fun ChoiceChevron(
    open: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = DesignTheme.colors.textSecondary,
) {
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(EXPAND_MILLIS), label = "chevron")
    Icon(painterResource(R.drawable.ic_dsh_chevron_down), null, modifier.rotate(turn), tint = tint)
}

/**
 * 首页的面板展开时，其余内容盖一层 60% 的页面底色，点任何地方收起（设计规格 6.10）。
 * 盖上的那一层接住点击，下面的内容点不到；列表仍然可以滑动。
 */
@Composable
fun Covered(
    covered: Boolean,
    onUncover: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        content()
        if (covered) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(DesignTheme.colors.page.copy(alpha = COVER_ALPHA))
                    .clickable(interactionSource = null, indication = null, onClick = onUncover)
                    .testTag(COVER_TAG),
            )
        }
    }
}

/**
 * 点到这里时通知一声，不吃掉这次点击（输入框照常移动光标）。用来实现「点输入的文字，选项收起」。
 */
@Composable
fun Modifier.onTap(action: () -> Unit): Modifier {
    val current by rememberUpdatedState(action)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (waitForUpOrCancellation(PointerEventPass.Initial) != null) current()
        }
    }
}

private const val COVER_ALPHA = 0.6f

const val INLINE_CHOICES_TAG = "inline-choices"
const val COVER_TAG = "cover"
