package io.github.qianyeeew.dshremote.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 计划（落地方案 B5）：当前这一步怎么算、什么时候算没有计划。 */
class PlanTest {
    private fun step(
        text: String,
        status: StepStatus,
    ) = PlanStep(text, status)

    @Test
    fun currentStepIsTheFirstInProgressOtherwiseTheFirstPending() {
        val steps =
            listOf(
                step("一", StepStatus.COMPLETED),
                step("二", StepStatus.PENDING),
                step("三", StepStatus.IN_PROGRESS),
            )
        assertEquals(2, SessionPlan(steps = steps).currentIndex)
        assertEquals(1, SessionPlan(steps = steps.take(2)).currentIndex)
        // 全部完成：没有当前这一步
        assertNull(SessionPlan(steps = listOf(step("一", StepStatus.COMPLETED))).currentIndex)
        assertNull(SessionPlan().currentIndex)
    }

    @Test
    fun emptyMeansNoGoalAndNoSteps() {
        assertTrue(SessionPlan().isEmpty)
        // 新一轮开始时 DSH 清空了清单，模型又写了一份空的
        assertTrue(SessionPlan(steps = emptyList()).isEmpty)
        assertFalse(SessionPlan(goal = PlanGoal("目标")).isEmpty)
        assertFalse(SessionPlan(steps = listOf(step("一", StepStatus.PENDING))).isEmpty)
    }
}
