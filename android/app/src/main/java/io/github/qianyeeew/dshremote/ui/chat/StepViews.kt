package io.github.qianyeeew.dshremote.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.OutputOmission
import io.github.qianyeeew.dshremote.ui.CodeTextStyles
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.common.DesignDivider
import io.github.qianyeeew.dshremote.ui.common.StatusMark
import io.github.qianyeeew.dshremote.ui.common.StatusMarker
import kotlinx.coroutines.delay

/**
 * 一段连续的思考和工具调用（设计规格 6.6）：默认折成一行灰字「5 个步骤 · 改了 2 个文件 ›」，点了在原地展开成步骤行，
 * 再点某一步展开它的详情。
 */
@Composable
fun StepsView(
    block: ChatBlock.Steps,
    modifier: Modifier = Modifier,
) {
    val colors = DesignTheme.colors
    var open by rememberSaveable(block.key) { mutableStateOf(false) }
    val summary =
        buildList {
            add(stringResource(R.string.chat_steps, block.steps.size))
            if (block.changedFiles > 0) add(stringResource(R.string.chat_steps_files, block.changedFiles))
        }.joinToString(" · ")
    Column(modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(if (open) R.string.chat_steps_collapse else R.string.chat_steps_expand),
                    role = Role.Button,
                ) { open = !open }
                .testTag("steps-summary"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Icon(
                painterResource(R.drawable.ic_dsh_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(14.dp).rotate(if (open) 90f else 0f),
                tint = colors.textSecondary,
            )
        }
        if (open) {
            Column(Modifier.fillMaxWidth().testTag("steps-list")) {
                DesignDivider()
                block.steps.forEach { step ->
                    StepItem(step)
                    DesignDivider()
                }
            }
        }
    }
}

@Composable
private fun StepItem(step: Step) {
    var open by rememberSaveable(step.key) { mutableStateOf(false) }
    val hasDetail = step.thinking != null || step.tool != null
    Column(Modifier.fillMaxWidth()) {
        StepRow(step, onClick = if (hasDetail) ({ open = !open }) else null)
        if (open) {
            Box(Modifier.fillMaxWidth().padding(bottom = 12.dp)) { StepDetail(step) }
        }
    }
}

/** 步骤行：动作（两个字，灰色，宽 28）、对象（等宽，一行）、结果（灰色小字，能确定时才有）。 */
@Composable
private fun StepRow(
    step: Step,
    onClick: (() -> Unit)?,
) {
    val colors = DesignTheme.colors
    val action = stringResource(actionLabel(step.action))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .testTag("step-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
            if (step.status == StepStatus.RUNNING) {
                StatusMarker(StatusMark.RUNNING, description = action)
            } else {
                Text(action, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
            }
        }
        Text(
            step.target.orEmpty(),
            style = CodeTextStyles.command,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StepResultText(step)
    }
}

@Composable
private fun StepResultText(step: Step) {
    val colors = DesignTheme.colors
    when (step.status) {
        StepStatus.FAILED ->
            Text(
                stringResource(R.string.chat_step_failed),
                style = MaterialTheme.typography.labelSmall,
                color = colors.danger,
            )
        StepStatus.REJECTED ->
            Text(
                stringResource(R.string.chat_step_rejected),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        else ->
            when (val result = step.result) {
                is StepResult.Lines ->
                    Text(
                        stringResource(R.string.chat_step_lines, result.count),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                    )
                is StepResult.Changed ->
                    Text(
                        when {
                            result.removed == 0 -> stringResource(R.string.chat_step_added, result.added)
                            result.added == 0 -> stringResource(R.string.chat_step_removed, result.removed)
                            else -> stringResource(R.string.chat_step_changed, result.added, result.removed)
                        },
                        style = CodeTextStyles.diff,
                        color = colors.textSecondary,
                    )
                null -> Unit
            }
    }
}

private fun actionLabel(action: StepAction): Int =
    when (action) {
        StepAction.RUN -> R.string.chat_step_run
        StepAction.READ -> R.string.chat_step_read
        StepAction.WRITE -> R.string.chat_step_write
        StepAction.EDIT -> R.string.chat_step_edit
        StepAction.SEARCH -> R.string.chat_step_search
        StepAction.OPEN -> R.string.chat_step_open
        StepAction.WEB_SEARCH -> R.string.chat_step_web
        StepAction.THINK -> R.string.chat_step_think
        StepAction.CALL -> R.string.chat_step_call
    }

/** 运行中的那一行文字：「运行 ./gradlew test」。 */
@Composable
internal fun stepText(step: Step): String = listOfNotNull(stringResource(actionLabel(step.action)), step.target).joinToString(" ")

@Composable
private fun StepDetail(step: Step) {
    val colors = DesignTheme.colors
    val tool = step.tool
    when {
        step.thinking != null ->
            SelectionContainer {
                Text(step.thinking, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        tool == null -> Unit
        step.action == StepAction.EDIT && tool.stringInput("old_string") != null && tool.stringInput("new_string") != null -> {
            val old = tool.stringInput("old_string").orEmpty()
            val new = tool.stringInput("new_string").orEmpty()
            DiffBlock(lineDiff(old, new), clipped = isClipped(tool, old) || isClipped(tool, new))
        }
        step.action == StepAction.RUN && tool.output != null ->
            OutputBlock(tool.output, tool.omitted, clipped = tool.omitted == null && isClipped(tool, tool.output), saved = tool.outputSaved)
        else ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tool.input.isNotEmpty()) CodeSection(stringResource(R.string.chat_tool_input), prettyJson(tool.input))
                tool.output?.takeIf { it.isNotEmpty() }?.let { CodeSection(stringResource(R.string.chat_tool_output), it) }
                if (tool.outputSaved) {
                    Text(
                        stringResource(R.string.chat_output_saved),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                    )
                }
                if (tool.truncated) {
                    Text(
                        stringResource(R.string.chat_tool_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                    )
                }
            }
    }
}

/**
 * 改动对比：每行最左边一列是符号，新增行 `+` 加新增行底色，删除行 `−` 加删除行底色、文字用次要文字色，
 * 上下文行没有符号、文字用次要文字色。不只靠颜色区分。
 */
@Composable
internal fun DiffBlock(
    lines: List<DiffLine>,
    clipped: Boolean,
) {
    val colors = DesignTheme.colors
    val added = stringResource(R.string.chat_diff_added)
    val removed = stringResource(R.string.chat_diff_removed)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.codeBackground)
            .testTag("diff-block"),
    ) {
        val width = maxWidth
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .widthIn(min = width)
                .width(IntrinsicSize.Max)
                .padding(vertical = 8.dp),
        ) {
            lines.forEach { line ->
                val (symbol, background, color, label) =
                    when (line.kind) {
                        DiffKind.ADDED -> DiffStyle("+", colors.diffAdded, colors.text, added)
                        DiffKind.REMOVED -> DiffStyle("−", colors.diffRemoved, colors.textSecondary, removed)
                        DiffKind.CONTEXT -> DiffStyle("", null, colors.textSecondary, null)
                    }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .let { if (background != null) it.background(background) else it }
                        .padding(horizontal = 12.dp)
                        .semantics(mergeDescendants = true) { if (label != null) contentDescription = "$label ${line.text}" },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(symbol, style = CodeTextStyles.diff, color = color, modifier = Modifier.width(8.dp))
                    Text(line.text, style = CodeTextStyles.diff, color = color, softWrap = false)
                }
            }
            if (clipped) {
                Text(
                    stringResource(R.string.chat_tool_truncated),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 改动对比里一行的样式：符号、整行底色（上下文行没有）、文字颜色、读屏时念出的说明。 */
private data class DiffStyle(
    val symbol: String,
    val background: Color?,
    val color: Color,
    val label: String?,
)

/**
 * 命令输出：顶部一行写「输出的最后 N 行」，右边「全部 M 行」；默认只显示最后 4 行，因为结果通常在末尾。
 * 插件去掉了中间时（落地方案 B6，[omitted]），M 是原来的总行数，展开后在开头和末尾之间写「中间省略了 K 行」。
 * 旧插件只保留开头，被截断时（[clipped]）注明「只拿到了前面一部分」。
 */
@Composable
internal fun OutputBlock(
    output: String,
    omitted: OutputOmission?,
    clipped: Boolean,
    /** DSH 自己截断过（完整输出存在电脑上）：原来有多少行不知道，只说拿到了多少行。 */
    saved: Boolean = false,
) {
    val colors = DesignTheme.colors
    var all by rememberSaveable(output) { mutableStateOf(false) }
    val parts = commandOutput(output, omitted)
    val last = parts.tail.takeLast(OUTPUT_TAIL_LINES)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.codeBackground)
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .testTag("output-block"),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            val partial = parts.total > last.size && !all
            Text(
                if (partial) stringResource(R.string.chat_output_last, last.size) else stringResource(R.string.chat_output),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (partial) {
                Text(
                    if (saved) {
                        stringResource(
                            R.string.chat_output_got,
                            parts.total,
                        )
                    } else {
                        stringResource(R.string.chat_output_all, parts.total)
                    },
                    style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline),
                    color = colors.text,
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { all = true }
                            .padding(vertical = 16.dp)
                            .testTag("output-all"),
                )
            }
        }
        if (saved) {
            Text(
                stringResource(R.string.chat_output_saved),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp).testTag("output-saved"),
            )
        }
        if (clipped) {
            Text(
                stringResource(R.string.chat_output_partial),
                style = MaterialTheme.typography.labelSmall,
                color = colors.waitingText,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        // 横向滚动只在最外面一层：嵌套的横向滚动在 Compose 里会直接报错
        val code = @Composable { text: List<String> ->
            Text(text.joinToString("\n"), style = CodeTextStyles.diff, color = colors.textSecondary, softWrap = false)
        }
        SelectionContainer {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                if (!all) {
                    code(last)
                    return@Column
                }
                if (parts.head.isNotEmpty()) code(parts.head)
                parts.omittedLines?.let { count ->
                    Text(
                        stringResource(R.string.chat_output_omitted, count),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.waitingText,
                        modifier = Modifier.padding(vertical = 6.dp).testTag("output-omitted"),
                    )
                }
                code(parts.tail)
            }
        }
    }
}

/**
 * 运行中，对话末尾的一行（6.7）：转圈标记加正在做的那一步，右边是「本轮 4:12」（[startedAt] 按电脑的时钟，
 * 拿不到时不显示）。token 数等落地方案 B4。
 */
@Composable
fun RunningLine(
    step: Step?,
    replying: Boolean,
    modifier: Modifier = Modifier,
    startedAt: Long? = null,
    clockOffsetMillis: Long = 0,
) {
    val colors = DesignTheme.colors
    val text =
        when {
            step != null -> stepText(step)
            replying -> stringResource(R.string.chat_replying)
            else -> stringResource(R.string.chat_running)
        }
    Row(
        modifier.fillMaxWidth().heightIn(min = 24.dp).testTag("running-line"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusMarker(StatusMark.RUNNING)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (startedAt != null) {
            // 每秒走一次。直接用估算的时钟差（精确到秒），不像「多久以前」那样忽略一分钟以内的差
            val now by produceState(System.currentTimeMillis() + clockOffsetMillis, clockOffsetMillis) {
                while (true) {
                    value = System.currentTimeMillis() + clockOffsetMillis
                    delay(ELAPSED_TICK_MILLIS - value % ELAPSED_TICK_MILLIS)
                }
            }
            Text(
                stringResource(R.string.chat_turn_elapsed, elapsedClock(now - startedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.testTag("running-elapsed"),
            )
        }
    }
}

/** 断线前还在运行，重新连上之前不知道结果（落地方案 B1）：不转圈，只写一句。 */
@Composable
fun UnconfirmedLine(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.turn_unconfirmed),
        style = MaterialTheme.typography.labelMedium,
        color = DesignTheme.colors.textSecondary,
        modifier = modifier.fillMaxWidth().testTag("unconfirmed-line"),
    )
}

private const val ELAPSED_TICK_MILLIS = 1_000L
