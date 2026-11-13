package io.github.qianyeeew.dshremote.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// 计划（protocol.md 第 4 节「计划」，落地方案 B5）：会话的长期目标，和模型当前这一轮的任务清单。
// DSH 在每一轮开始时清空任务清单，所以 [SessionPlan.steps] 可能在新一轮开始后变成 null。
// ---------------------------------------------------------------------------

/** `GET /v1/sessions/{id}/plan` 和推送 `plan.updated`。 */
@Serializable
data class SessionPlan(
    val goal: PlanGoal? = null,
    val steps: List<PlanStep>? = null,
) {
    /** 有可显示的内容：目标，或者至少一步。 */
    val isEmpty: Boolean get() = goal == null && steps.isNullOrEmpty()

    /** 当前这一步：第一个进行中的，没有时第一个待做的；全部完成时为 null。 */
    val currentIndex: Int?
        get() {
            val list = steps ?: return null
            val active = list.indexOfFirst { it.status == StepStatus.IN_PROGRESS }
            if (active >= 0) return active
            return list.indexOfFirst { it.status == StepStatus.PENDING }.takeIf { it >= 0 }
        }
}

@Serializable
data class PlanGoal(
    val objective: String,
    val status: GoalStatus = GoalStatus.OTHER,
    /** 只在受阻时有。 */
    val blocked: GoalBlock? = null,
)

@Serializable
enum class GoalStatus {
    @SerialName("active")
    ACTIVE,

    @SerialName("paused")
    PAUSED,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("complete")
    COMPLETE,

    /** DSH 以后新增的状态，或者这个版本的 App 不认识的值。 */
    @SerialName("other")
    OTHER,
}

/** 受阻原因：[code] 是 DSH 的分类（认识的显示中文），[message] 是 DSH 的说明（多半是英文）。 */
@Serializable
data class GoalBlock(
    val code: String,
    val message: String = "",
)

@Serializable
data class PlanStep(
    val text: String,
    val status: StepStatus = StepStatus.PENDING,
)

@Serializable
enum class StepStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,
}
