package io.github.qianyeeew.dshremote.ui.attention

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.Question
import io.github.qianyeeew.dshremote.data.attention.AnswerForm
import io.github.qianyeeew.dshremote.data.attention.QuestionDraft
import io.github.qianyeeew.dshremote.data.attention.QuestionProblem
import io.github.qianyeeew.dshremote.ui.LocalStatusColors
import io.github.qianyeeew.dshremote.ui.chat.CodeSection
import io.github.qianyeeew.dshremote.ui.chat.prettyJson

/** 处理一个事项时的操作，由 [AttentionHandler] 实现。 */
class AttentionCardActions(
    val onToggle: (attention: Attention, questionId: String, label: String) -> Unit = { _, _, _ -> },
    val onCustom: (attention: Attention, questionId: String, text: String) -> Unit = { _, _, _ -> },
    val onDecide: (attention: Attention, allow: Boolean) -> Unit = { _, _ -> },
    val onSubmit: (attention: Attention) -> Unit = {},
) {
    companion object {
        fun of(handler: AttentionHandler) =
            AttentionCardActions(
                onToggle = handler::toggle,
                onCustom = handler::setCustom,
                onDecide = handler::decide,
                onSubmit = handler::submitAnswers,
            )
    }
}

/**
 * 一个待处理事项（m2-android.md 3.5）：审批只有「允许」和「拒绝」，提问支持单选、多选和自由文本。
 * 会话详情页底部和独立的处理页面共用。
 */
@Composable
fun AttentionCard(
    attention: Attention,
    ui: AttentionUi,
    actions: AttentionCardActions,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("attention-${attention.attentionId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val approval = attention.approval
            val question = attention.question
            when {
                attention.kind == AttentionKind.APPROVAL && approval != null -> ApprovalContent(attention, approval, ui, actions)
                attention.kind == AttentionKind.QUESTION && question != null -> {
                    Text(stringResource(R.string.attention_question_title), style = MaterialTheme.typography.titleMedium)
                    question.questions.forEach { q ->
                        QuestionContent(attention, q, ui.drafts[q.id] ?: QuestionDraft(), ui.problems[q.id], ui.submitting, actions)
                    }
                    if (question.questions.size > 1) {
                        Text(
                            stringResource(R.string.attention_partial_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ErrorText(ui)
                    Button(onClick = { actions.onSubmit(attention) }, enabled = !ui.submitting, modifier = Modifier.fillMaxWidth()) {
                        if (ui.submitting) Progress() else Text(stringResource(R.string.attention_submit))
                    }
                }
                else -> Text(stringResource(R.string.attention_unknown_kind))
            }
        }
    }
}

@Composable
private fun ApprovalContent(
    attention: Attention,
    approval: Approval,
    ui: AttentionUi,
    actions: AttentionCardActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(R.drawable.ic_dsh_shield), null, Modifier.size(16.dp), tint = LocalStatusColors.current.waiting)
        Text(
            stringResource(R.string.attention_approval_title),
            style = MaterialTheme.typography.labelMedium,
            color = LocalStatusColors.current.waiting,
        )
    }
    Text(approval.summary ?: stringResource(R.string.attention_tool, approval.toolName), style = MaterialTheme.typography.titleSmall)
    if (approval.reason.isNotBlank()) Text(approval.reason, style = MaterialTheme.typography.bodyMedium)
    approval.input?.takeIf { it.isNotEmpty() }?.let { input ->
        var open by rememberSaveable(attention.attentionId) { mutableStateOf(false) }
        Text(
            stringResource(if (open) R.string.attention_hide_input else R.string.attention_show_input),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
        )
        if (open) CodeSection(stringResource(R.string.attention_tool, approval.toolName), prettyJson(input))
    }
    Text(
        stringResource(R.string.attention_approval_once),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ErrorText(ui)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { actions.onDecide(attention, false) }, enabled = !ui.submitting, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.attention_reject))
        }
        Button(onClick = { actions.onDecide(attention, true) }, enabled = !ui.submitting, modifier = Modifier.weight(1f)) {
            if (ui.submitting) Progress() else Text(stringResource(R.string.attention_allow))
        }
    }
}

@Composable
private fun QuestionContent(
    attention: Attention,
    question: Question,
    draft: QuestionDraft,
    problem: QuestionProblem?,
    submitting: Boolean,
    actions: AttentionCardActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        question.header?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(question.question, style = MaterialTheme.typography.bodyLarge)
        question.options.forEach { option ->
            val checked = option.label in draft.selected
            val onClick = { actions.onToggle(attention, question.id, option.label) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .let {
                            if (question.multiSelect) {
                                it.toggleable(value = checked, enabled = !submitting, role = Role.Checkbox, onValueChange = { onClick() })
                            } else {
                                it.selectable(selected = checked, enabled = !submitting, role = Role.RadioButton, onClick = onClick)
                            }
                        }.padding(vertical = 2.dp),
            ) {
                if (question.multiSelect) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = !submitting)
                } else {
                    RadioButton(selected = checked, onClick = null, enabled = !submitting)
                }
                Column(Modifier.padding(start = 8.dp)) {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    option.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedTextField(
            value = draft.custom,
            onValueChange = { actions.onCustom(attention, question.id, it) },
            label = { Text(stringResource(R.string.attention_custom_label)) },
            enabled = !submitting,
            isError = problem != null,
            supportingText =
                problem?.let {
                    { Text(stringResource(R.string.attention_error_too_long, AnswerForm.MAX_CUSTOM)) }
                },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ErrorText(ui: AttentionUi) {
    val error = ui.error ?: return
    Text(
        when (error) {
            AttentionError.NOTHING_ANSWERED -> stringResource(R.string.attention_error_nothing)
            AttentionError.CUSTOM_TOO_LONG -> stringResource(R.string.attention_error_too_long, AnswerForm.MAX_CUSTOM)
            AttentionError.OFFLINE -> stringResource(R.string.attention_error_offline)
            AttentionError.REJECTED -> stringResource(R.string.attention_error_rejected)
            AttentionError.FAILED -> stringResource(R.string.attention_error_failed)
        },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Progress() {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
}
