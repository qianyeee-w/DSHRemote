package io.github.qianyeeew.dshremote.ui.attention

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.Question
import io.github.qianyeeew.dshremote.data.api.QuestionSet
import io.github.qianyeeew.dshremote.data.attention.AnswerForm
import io.github.qianyeeew.dshremote.data.attention.QuestionDraft
import io.github.qianyeeew.dshremote.data.attention.QuestionProblem
import io.github.qianyeeew.dshremote.ui.CodeTextStyles
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.common.BlockButton
import io.github.qianyeeew.dshremote.ui.common.DesignDivider
import io.github.qianyeeew.dshremote.ui.common.PillButton
import io.github.qianyeeew.dshremote.ui.common.RowChevron
import io.github.qianyeeew.dshremote.ui.common.StatusMark
import io.github.qianyeeew.dshremote.ui.common.StatusMarker
import io.github.qianyeeew.dshremote.ui.common.designPanel
import io.github.qianyeeew.dshremote.ui.input

/** 处理一个事项时的操作，由 [AttentionHandler] 实现。 */
class AttentionCardActions(
    val onToggle: (attention: Attention, questionId: String, label: String) -> Unit = { _, _, _ -> },
    /** 只有一个问题且是单选时，点选项直接回答（其他情况等同 [onToggle]）。 */
    val onAnswerOption: (attention: Attention, questionId: String, label: String) -> Unit = { _, _, _ -> },
    val onCustom: (attention: Attention, questionId: String, text: String) -> Unit = { _, _, _ -> },
    /** 用底部输入框写的答案回答只有一个问题的提问。返回 true 表示已发出。 */
    val onAnswerWithCustom: (attention: Attention, questionId: String, text: String) -> Boolean = { _, _, _ -> false },
    val onDecide: (attention: Attention, allow: Boolean) -> Unit = { _, _ -> },
    val onSubmit: (attention: Attention) -> Unit = {},
) {
    companion object {
        fun of(handler: AttentionHandler) =
            AttentionCardActions(
                onToggle = handler::toggle,
                onAnswerOption = handler::answerOption,
                onCustom = handler::setCustom,
                onAnswerWithCustom = handler::answerWithCustom,
                onDecide = handler::decide,
                onSubmit = { handler.submitAnswers(it) },
            )
    }
}

/**
 * 待处理面板（设计规格 6.8、7.4、7.5）：面板底、面板描边、圆角 22，压在输入框上方。第一行是琥珀色圆点加一句琥珀色小字。
 * 会话页底部、独立处理页共用。[cwd] 是审批的工作目录（会话的工作目录），不知道时为 null。
 */
@Composable
fun AttentionCard(
    attention: Attention,
    ui: AttentionUi,
    actions: AttentionCardActions,
    modifier: Modifier = Modifier,
    cwd: String? = null,
) {
    val approval = attention.approval
    val question = attention.question
    Column(
        modifier
            .fillMaxWidth()
            .designPanel(RoundedCornerShape(22.dp))
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (question != null) 4.dp else 16.dp)
            .testTag("attention-${attention.attentionId}"),
    ) {
        when {
            attention.kind == AttentionKind.APPROVAL && approval != null -> ApprovalContent(attention, approval, ui, actions, cwd)
            attention.kind == AttentionKind.QUESTION && question != null -> QuestionContent(attention, question, ui, actions)
            else -> {
                Headline(stringResource(R.string.attention_decide))
                Text(
                    stringResource(R.string.attention_unknown_kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = DesignTheme.colors.text,
                    modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** 第一行：琥珀色圆点加一句琥珀色小字。 */
@Composable
private fun Headline(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusMarker(StatusMark.WAITING)
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(lineHeight = MaterialTheme.typography.labelMedium.lineHeight),
            color = DesignTheme.colors.waitingText,
        )
    }
}

/**
 * 审批（7.4）：想运行命令 / 想修改文件 / 想使用某工具；命令或文件（等宽 14 号，可以折行）；工作目录（等宽 12 号，灰色）；
 * 「拒绝」「允许」各占一半。插件没能找到对应的工具调用时，第 2、3 行换成审批的 `reason`。只对这一次有效。
 */
@Composable
private fun ApprovalContent(
    attention: Attention,
    approval: Approval,
    ui: AttentionUi,
    actions: AttentionCardActions,
    cwd: String?,
) {
    val colors = DesignTheme.colors
    Headline(
        when (approvalAction(approval.toolName)) {
            ApprovalAction.RUN -> stringResource(R.string.attention_run)
            ApprovalAction.EDIT -> stringResource(R.string.attention_edit)
            ApprovalAction.OTHER -> stringResource(R.string.attention_use, approval.toolName)
        },
    )
    val target = approvalTarget(approval)
    if (target != null) {
        // 长命令在这一块里滚动，「拒绝」「允许」始终看得见（用户真机走查：长命令把按钮挤出了屏幕）
        val scroll = rememberScrollState()
        val fade = colors.panel
        Column(
            Modifier
                .padding(top = 10.dp)
                .heightIn(max = APPROVAL_TARGET_MAX)
                .drawWithContent {
                    drawContent()
                    // 下面还有内容：底边渐隐，提示可以往下滑
                    if (scroll.canScrollForward) {
                        val h = APPROVAL_FADE.toPx()
                        drawRect(
                            Brush.verticalGradient(listOf(fade.copy(alpha = 0f), fade), startY = size.height - h, endY = size.height),
                            topLeft = Offset(0f, size.height - h),
                        )
                    }
                }.verticalScroll(scroll)
                .testTag(APPROVAL_TARGET_SCROLL_TAG),
        ) {
            Text(
                target,
                style = CodeTextStyles.approval,
                color = colors.text,
                modifier = Modifier.testTag("approval-target"),
            )
            cwd?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = CodeTextStyles.diff.copy(lineHeight = CodeTextStyles.command.lineHeight),
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    } else {
        Text(
            approval.reason.ifBlank { approval.summary ?: approval.toolName },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    StatusLine(ui, onRetry = null)
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BlockButton(stringResource(R.string.attention_reject), {
            actions.onDecide(attention, false)
        }, Modifier.weight(1f), enabled = !ui.submitting)
        BlockButton(stringResource(R.string.attention_allow), {
            actions.onDecide(attention, true)
        }, Modifier.weight(1f), primary = true, enabled = !ui.submitting)
    }
}

/**
 * 提问（7.5）：「需要你决定」、问题、选项列表。只有一个问题而且是单选时，点选项就是回答，没有提交按钮；
 * 多选或多个问题时选项左边是勾选框，底部一个「回答」。只有一个问题时自己写的答案用下面的输入框；
 * 多个问题时每题下面有自己的输入框（用户确认）。
 */
@Composable
private fun QuestionContent(
    attention: Attention,
    set: QuestionSet,
    ui: AttentionUi,
    actions: AttentionCardActions,
) {
    val direct = set.questions.size == 1 && !set.questions.single().multiSelect
    Headline(stringResource(R.string.attention_decide))
    set.questions.forEach { question ->
        QuestionBlock(
            attention,
            question,
            ui.drafts[question.id] ?: QuestionDraft(),
            ui.problems[question.id],
            direct = direct,
            withCustom = set.questions.size > 1,
            enabled = !ui.submitting,
            actions = actions,
        )
    }
    StatusLine(ui, onRetry = if (direct) ({ actions.onSubmit(attention) }) else null)
    if (set.questions.size > 1) {
        Text(
            stringResource(R.string.attention_partial_hint),
            style = MaterialTheme.typography.labelSmall,
            color = DesignTheme.colors.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (!direct) {
        BlockButton(
            stringResource(R.string.attention_answer),
            { actions.onSubmit(attention) },
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp).testTag("attention-answer"),
            primary = true,
            enabled = !ui.submitting,
        )
    } else {
        Box(Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun QuestionBlock(
    attention: Attention,
    question: Question,
    draft: QuestionDraft,
    problem: QuestionProblem?,
    direct: Boolean,
    withCustom: Boolean,
    enabled: Boolean,
    actions: AttentionCardActions,
) {
    val colors = DesignTheme.colors
    question.header?.takeIf { it.isNotBlank() && withCustom }?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, modifier = Modifier.padding(top = 12.dp))
    }
    Text(
        question.question,
        style = MaterialTheme.typography.titleSmall,
        color = colors.text,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
    question.options.forEach { option ->
        val checked = option.label in draft.selected
        DesignDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .let {
                    when {
                        direct ->
                            it.selectable(selected = checked, enabled = enabled, role = Role.Button) {
                                actions.onAnswerOption(attention, question.id, option.label)
                            }
                        question.multiSelect ->
                            it.toggleable(value = checked, enabled = enabled, role = Role.Checkbox) {
                                actions.onToggle(attention, question.id, option.label)
                            }
                        else ->
                            it.selectable(selected = checked, enabled = enabled, role = Role.RadioButton) {
                                actions.onToggle(attention, question.id, option.label)
                            }
                    }
                }.padding(vertical = 12.dp)
                .testTag("option-${question.id}-${option.label}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!direct) CheckMark(checked, round = !question.multiSelect)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(option.label, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal), color = colors.text)
                option.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = MaterialTheme.typography.labelMedium.lineHeight),
                        color = colors.textSecondary,
                    )
                }
            }
            if (direct) RowChevron()
        }
    }
    if (withCustom) {
        DesignDivider()
        CustomField(draft.custom, enabled, problem != null) { actions.onCustom(attention, question.id, it) }
    }
}

/** 多选、多个问题时选项左边的勾选框（多选方形，单选圆形）。 */
@Composable
private fun CheckMark(
    checked: Boolean,
    round: Boolean,
) {
    val colors = DesignTheme.colors
    val shape = if (round) CircleShape else RoundedCornerShape(5.dp)
    Box(
        Modifier
            .size(20.dp)
            .clip(shape)
            .background(if (checked) colors.text else colors.panel)
            .border(1.5.dp, if (checked) colors.text else colors.textSecondary, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            if (round) {
                Box(Modifier.size(8.dp).background(colors.panel, CircleShape))
            } else {
                Icon(painterResource(R.drawable.ic_dsh_check), null, Modifier.size(14.dp), tint = colors.panel)
            }
        }
    }
}

/** 多个问题时每题下面「自己写」的输入框。 */
@Composable
private fun CustomField(
    value: String,
    enabled: Boolean,
    error: Boolean,
    onChange: (String) -> Unit,
) {
    val colors = DesignTheme.colors
    val hint = stringResource(R.string.attention_custom_hint)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        textStyle = MaterialTheme.typography.input.copy(color = colors.text),
        cursorBrush = SolidColor(colors.text),
        maxLines = 4,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.fill)
                .let { if (error) it.border(1.dp, colors.danger, RoundedCornerShape(12.dp)) else it }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { contentDescription = hint },
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.input, color = colors.textSecondary)
                field()
            }
        },
    )
}

/** 提交中、失败的提示。失败且没有「回答」按钮时（单选直答）给一个「重试」。 */
@Composable
private fun StatusLine(
    ui: AttentionUi,
    onRetry: (() -> Unit)?,
) {
    val colors = DesignTheme.colors
    when {
        ui.submitting ->
            Row(
                Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusMarker(StatusMark.RUNNING)
                Text(
                    stringResource(R.string.attention_submitting),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        ui.error != null ->
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when (ui.error) {
                        AttentionError.NOTHING_ANSWERED -> stringResource(R.string.attention_error_nothing)
                        AttentionError.CUSTOM_TOO_LONG -> stringResource(R.string.attention_error_too_long, AnswerForm.MAX_CUSTOM)
                        AttentionError.OFFLINE -> stringResource(R.string.attention_error_offline)
                        AttentionError.REJECTED -> stringResource(R.string.attention_error_rejected)
                        AttentionError.FAILED -> stringResource(R.string.attention_error_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                    modifier = Modifier.weight(1f).testTag("attention-error"),
                )
                if (onRetry != null && ui.error in RETRYABLE) PillButton(stringResource(R.string.action_retry), onRetry)
            }
    }
}

/** 换个时间再试可能成功的错误。没回答、太长这类要先改内容。 */
private val RETRYABLE = setOf(AttentionError.OFFLINE, AttentionError.FAILED, AttentionError.REJECTED)

/** 审批的命令或文件最多占多高（约 6 行），再长就在里面滚动。 */
private val APPROVAL_TARGET_MAX = 132.dp

/** 底边渐隐的高度。 */
private val APPROVAL_FADE = 24.dp

const val APPROVAL_TARGET_SCROLL_TAG = "approval-target-scroll"

