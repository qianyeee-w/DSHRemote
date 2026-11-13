package io.github.qianyeeew.dshremote.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.GoalBlock
import io.github.qianyeeew.dshremote.data.api.GoalStatus
import io.github.qianyeeew.dshremote.data.api.PlanStep
import io.github.qianyeeew.dshremote.data.api.SessionPlan
import io.github.qianyeeew.dshremote.data.api.StepStatus
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.attention.ApprovalAction
import io.github.qianyeeew.dshremote.ui.attention.approvalAction
import io.github.qianyeeew.dshremote.ui.common.DesignBackBar
import io.github.qianyeeew.dshremote.ui.common.DesignDivider
import io.github.qianyeeew.dshremote.ui.common.StatusMark
import io.github.qianyeeew.dshremote.ui.common.StatusMarker
import io.github.qianyeeew.dshremote.ui.common.sessionTitleText

// ---------------------------------------------------------------------------
// 计划（设计规格 6.9、7.12，落地方案 B5）。数据来自 DSH 的目标和模型当前这一轮的任务清单，
// 全部以 DSH 的真实状态为依据：拿不到就不显示；「最近进展」DSH 没有对应的数据，不显示（用户确认）。
// ---------------------------------------------------------------------------

/**
 * 计划条（6.9）：会话顶栏下面的一行，高 44，上下各一条分隔线：「第 3 步，共 5 步」（灰色）加当前步骤，
 * 最右边一个小箭头，点了打开计划页。只有目标、没有步骤时写「目标」加目标的开头，目标受阻时用琥珀色（用户确认）。
 * 没有计划数据时不显示。不画进度条，不显示百分比。
 */
@Composable
fun PlanStrip(
    plan: SessionPlan?,
    onOpen: () -> Unit,
) {
    if (plan == null || plan.isEmpty) return
    val colors = DesignTheme.colors
    val steps = plan.steps.orEmpty()
    val current = plan.currentIndex
    val goal = plan.goal
    val (label, value) =
        when {
            steps.isNotEmpty() && current != null -> stringResource(R.string.plan_step_of, current + 1, steps.size) to steps[current].text
            steps.isNotEmpty() -> stringResource(R.string.plan_all_done, steps.size) to null
            else -> stringResource(R.string.plan_goal) to goal?.objective
        }
    val blocked = steps.isEmpty() && goal?.status == GoalStatus.BLOCKED
    Column(Modifier.fillMaxWidth().testTag(PLAN_STRIP_TAG)) {
        DesignDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(onClickLabel = stringResource(R.string.plan_open), role = Role.Button, onClick = onOpen)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked) colors.waitingText else colors.textSecondary,
                maxLines = 1,
            )
            Text(
                value.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked) colors.waitingText else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(painterResource(R.drawable.ic_dsh_chevron_right), null, Modifier.size(14.dp), tint = colors.textSecondary)
        }
        DesignDivider()
    }
}

/** 计划页：和会话页共用同一个视图模型（AppNavigation 里取会话页的那一个），自己也持有一次可见。 */
@Composable
fun PlanScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.onVisible()
        onStopOrDispose { viewModel.onHidden() }
    }
    PlanContent(state, onBack, onHandle = onBack)
}

/**
 * 计划页（7.12）：目标；步骤（已完成的前面是灰色的勾，文字也是灰色；当前步骤前面是转圈标记，文字加粗；
 * 还没做的前面是空心圆）。当前步骤卡住时标记换成琥珀点，下面琥珀色字写「卡在这里：」加原因；
 * 原因是等审批或等回答时再给一个「去处理」（回到会话页，面板就在底部）。
 */
@Composable
fun PlanContent(
    state: ChatState,
    onBack: () -> Unit,
    onHandle: () -> Unit,
) {
    val colors = DesignTheme.colors
    val plan = state.plan
    val waitingOn = if (state.online) state.attention.firstOrNull() else null
    Scaffold(topBar = { DesignBackBar(onBack, sessionTitleText(state.session?.title)) }, containerColor = colors.page) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("plan-page"),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (plan == null || plan.isEmpty) {
                Text(stringResource(R.string.plan_empty), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                return@Column
            }
            plan.goal?.let { goal ->
                Column(Modifier.testTag("plan-goal"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.plan_goal), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    Text(goal.objective, style = MaterialTheme.typography.bodyMedium, color = colors.text)
                    goalStatusText(goal.status, goal.blocked)?.let { (text, amber) ->
                        Text(
                            text,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (amber) colors.waitingText else colors.textSecondary,
                            modifier = Modifier.testTag("plan-goal-status"),
                        )
                    }
                }
            }
            val steps = plan.steps
            if (!steps.isNullOrEmpty()) {
                val current = plan.currentIndex
                Column {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.plan_steps),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (current != null) {
                            Text(
                                stringResource(R.string.plan_step_of, current + 1, steps.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                    steps.forEachIndexed { index, step ->
                        DesignDivider()
                        StepRow(
                            step,
                            index,
                            current = index == current,
                            waitingOn = waitingOn.takeIf { index == current },
                            onHandle = onHandle,
                        )
                    }
                    DesignDivider()
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    step: PlanStep,
    index: Int,
    current: Boolean,
    waitingOn: Attention?,
    onHandle: () -> Unit,
) {
    val colors = DesignTheme.colors
    val done = step.status == StepStatus.COMPLETED
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp).testTag("plan-step:$index"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(18.dp).padding(top = 3.dp), contentAlignment = Alignment.Center) {
            when {
                done ->
                    Icon(
                        painterResource(R.drawable.ic_dsh_check),
                        stringResource(R.string.plan_step_done),
                        Modifier.size(16.dp),
                        tint = colors.textSecondary,
                    )
                current && waitingOn != null -> StatusMarker(StatusMark.WAITING)
                current -> StatusMarker(StatusMark.RUNNING)
                else -> Box(Modifier.size(10.dp).border(1.5.dp, colors.textSecondary, CircleShape))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                step.text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal),
                color = if (done) colors.textSecondary else colors.text,
            )
            if (waitingOn != null) {
                Text(
                    stringResource(R.string.plan_stuck, waitingReason(waitingOn)),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.waitingText,
                    modifier = Modifier.testTag("plan-stuck"),
                )
                Text(
                    stringResource(R.string.plan_handle),
                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                    color = colors.text,
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button, onClick = onHandle)
                            .padding(vertical = 14.dp)
                            .testTag("plan-handle"),
                )
            }
        }
    }
}

/** 等审批时说想做什么，等回答时说等你回答。 */
@Composable
private fun waitingReason(attention: Attention): String {
    val approval = attention.approval ?: return stringResource(R.string.plan_waiting_question)
    return when (approvalAction(approval.toolName)) {
        ApprovalAction.RUN -> stringResource(R.string.plan_waiting_run)
        ApprovalAction.EDIT -> stringResource(R.string.plan_waiting_edit)
        ApprovalAction.OTHER -> stringResource(R.string.plan_waiting_tool, approval.toolName)
    }
}

/** 目标状态的一行；第二项为 true 时用琥珀色。进行中不另写（目标本来就在进行）。不认识的状态不写。 */
@Composable
private fun goalStatusText(
    status: GoalStatus,
    blocked: GoalBlock?,
): Pair<String, Boolean>? =
    when (status) {
        GoalStatus.ACTIVE, GoalStatus.OTHER -> null
        GoalStatus.PAUSED -> stringResource(R.string.plan_goal_paused) to false
        GoalStatus.COMPLETE -> stringResource(R.string.plan_goal_complete) to false
        GoalStatus.BLOCKED -> stringResource(R.string.plan_stuck, blockedReason(blocked)) to true
    }

/** 受阻原因：认识的 DSH 分类说中文，不认识的用 DSH 的说明。 */
@Composable
fun blockedReason(blocked: GoalBlock?): String =
    when (blocked?.code) {
        "round-limit" -> stringResource(R.string.plan_blocked_round_limit)
        null -> stringResource(R.string.plan_blocked_unknown)
        else -> blocked.message.ifBlank { blocked.code }
    }

const val PLAN_STRIP_TAG = "plan-strip"
