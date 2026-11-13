package io.github.qianyeeew.dshremote.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.GoalBlock
import io.github.qianyeeew.dshremote.data.api.GoalStatus
import io.github.qianyeeew.dshremote.data.api.PlanGoal
import io.github.qianyeeew.dshremote.data.api.PlanStep
import io.github.qianyeeew.dshremote.data.api.SessionPlan
import io.github.qianyeeew.dshremote.data.api.StepStatus
import io.github.qianyeeew.dshremote.ui.chat.ChatActions
import io.github.qianyeeew.dshremote.ui.chat.ChatContent
import io.github.qianyeeew.dshremote.ui.chat.PLAN_STRIP_TAG
import io.github.qianyeeew.dshremote.ui.chat.Picker
import io.github.qianyeeew.dshremote.ui.chat.PlanContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 计划条和计划页（设计规格 6.9、7.12，落地方案 B5）。 */
class PlanUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val steps =
        listOf(
            PlanStep("读连接状态和会话订阅的代码", StepStatus.COMPLETED),
            PlanStep("核对重连后的两处恢复逻辑", StepStatus.COMPLETED),
            PlanStep("跑单元测试，确认实际行为", StepStatus.IN_PROGRESS),
            PlanStep("汇报结论", StepStatus.PENDING),
        )
    private val goal = PlanGoal("确认断线重连以后还能接着看当前会话，阅读位置不丢。", GoalStatus.ACTIVE)
    private val approval =
        Attention("a1", "visual-test", AttentionKind.APPROVAL, approval = Approval("bash", "r"), createdAt = 0)

    /** 计划条：第几步加当前步骤，点了打开计划页；只有目标时写目标；没有计划时不显示。 */
    @Test
    fun stripShowsTheCurrentStepOrTheGoalAndOpensThePlan() {
        var plan by mutableStateOf<SessionPlan?>(SessionPlan(goal, steps))
        var opened = 0
        compose.setContent {
            DshRemoteTheme {
                ChatContent(onlineChatState().copy(plan = plan), null, Picker.Closed, ChatActions(onOpenPlan = { opened++ }))
            }
        }
        compose.onNodeWithTag(PLAN_STRIP_TAG).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plan_step_of, 3, 4)).assertIsDisplayed()
        compose.onNodeWithText("跑单元测试，确认实际行为").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        save("plan-strip")
        // 新一轮开始，DSH 清空了任务清单：显示目标
        compose.runOnIdle { plan = SessionPlan(goal.copy(status = GoalStatus.BLOCKED, blocked = GoalBlock("round-limit")), null) }
        compose.onNodeWithText(text(R.string.plan_goal)).assertIsDisplayed()
        compose.onNodeWithText(goal.objective).assertIsDisplayed()
        // 全部完成
        compose.runOnIdle { plan = SessionPlan(steps = steps.map { it.copy(status = StepStatus.COMPLETED) }) }
        compose.onNodeWithText(context.getString(R.string.plan_all_done, 4)).assertIsDisplayed()
        // 没有计划
        compose.runOnIdle { plan = SessionPlan() }
        compose.onNodeWithTag(PLAN_STRIP_TAG).assertDoesNotExist()
        compose.runOnIdle { plan = null }
        compose.onNodeWithTag(PLAN_STRIP_TAG).assertDoesNotExist()
    }

    /** 计划页：目标和步骤；当前步骤在等审批时写「卡在这里」并给「去处理」。 */
    @Test
    fun planPageShowsGoalStepsAndWhereItIsStuck() {
        var handled = 0
        compose.setContent {
            DshRemoteTheme {
                PlanContent(
                    onlineChatState().copy(plan = SessionPlan(goal, steps), attention = listOf(approval)),
                    onBack = {},
                    onHandle = { handled++ },
                )
            }
        }
        compose.onNodeWithText(goal.objective).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plan_step_of, 3, 4)).assertIsDisplayed()
        compose.onNodeWithText("汇报结论").assertIsDisplayed()
        compose
            .onNodeWithTag("plan-stuck")
            .assertTextContains(context.getString(R.string.plan_stuck, text(R.string.plan_waiting_run)))
        // 进行中的目标不另写状态
        compose.onNodeWithTag("plan-goal-status").assertDoesNotExist()
        save("plan-page")
        compose.onNodeWithTag("plan-handle").performClick()
        compose.runOnIdle { assertEquals(1, handled) }
    }

    /** 目标受阻：认识的原因说中文；不认识的用 DSH 的说明。没有计划时说一句。 */
    @Test
    fun blockedGoalAndEmptyPlan() {
        var plan by mutableStateOf(
            SessionPlan(
                goal.copy(
                    status = GoalStatus.BLOCKED,
                    blocked = GoalBlock("round-limit", "Goal reached its configured limit of 1 rounds."),
                ),
            ),
        )
        compose.setContent { DshRemoteTheme { PlanContent(onlineChatState().copy(plan = plan), onBack = {}, onHandle = {}) } }
        compose
            .onNodeWithTag("plan-goal-status")
            .assertTextContains(context.getString(R.string.plan_stuck, text(R.string.plan_blocked_round_limit)))
        compose.onNodeWithTag("plan-stuck").assertDoesNotExist()
        compose.runOnIdle {
            plan =
                SessionPlan(goal.copy(status = GoalStatus.BLOCKED, blocked = GoalBlock("tests-failing", "Tests keep failing.")))
        }
        compose.onNodeWithTag("plan-goal-status").assertTextContains(context.getString(R.string.plan_stuck, "Tests keep failing."))
        compose.runOnIdle { plan = SessionPlan(goal.copy(status = GoalStatus.PAUSED)) }
        compose.onNodeWithTag("plan-goal-status").assertTextContains(text(R.string.plan_goal_paused))
        compose.runOnIdle { plan = SessionPlan() }
        compose.onNodeWithText(text(R.string.plan_empty)).assertIsDisplayed()
    }

    private fun text(id: Int) = context.getString(id)

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "visual-verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
