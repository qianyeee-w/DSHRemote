package io.github.qianyeeew.dshremote.ui.attention

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionAnswer
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.attention.AnswerCheck
import io.github.qianyeeew.dshremote.data.attention.AnswerForm
import io.github.qianyeeew.dshremote.data.attention.QuestionDraft
import io.github.qianyeeew.dshremote.data.attention.QuestionProblem
import io.github.qianyeeew.dshremote.data.live.ConnectionManager
import io.github.qianyeeew.dshremote.data.live.LiveEvent
import io.github.qianyeeew.dshremote.data.live.OfflineException
import io.github.qianyeeew.dshremote.data.live.ResolvedBy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 回答待处理事项的接口（测试中替换成假的实现）。不在线时抛出 [OfflineException]。 */
fun interface AttentionResponder {
    suspend fun answer(
        attentionId: String,
        answer: AttentionAnswer,
    )
}

/** 通过这台主机在线时的连接回答。 */
class LiveAttentionResponder(
    private val connections: ConnectionManager,
    private val hostId: String,
) : AttentionResponder {
    override suspend fun answer(
        attentionId: String,
        answer: AttentionAnswer,
    ) = (connections.api(hostId) ?: throw OfflineException()).answer(attentionId, answer)
}

/** 一个事项最后是怎么结束的。 */
enum class Resolution {
    /** 这台手机的回答生效了。 */
    ANSWERED_HERE,

    /** 在别处处理掉了（Desktop、其他手机），或者任务被停止了（DSH 不区分这两种情况，protocol.md 6.2）。 */
    ELSEWHERE,

    /** 插件说这个事项不存在（`404`）。 */
    GONE,
}

enum class AttentionError {
    NOTHING_ANSWERED,
    CUSTOM_TOO_LONG,
    OFFLINE,

    /** 插件认为回答的格式不对（`400`）。本地校验之后不应该出现，出现了多半是版本不匹配。 */
    REJECTED,
    FAILED,
}

/** 一个事项在界面上的状态。 */
data class AttentionUi(
    val drafts: Map<String, QuestionDraft> = emptyMap(),
    val problems: Map<String, QuestionProblem> = emptyMap(),
    val submitting: Boolean = false,
    val error: AttentionError? = null,
    /** 不为 null 表示已经结束，界面收起。 */
    val resolution: Resolution? = null,
)

/**
 * 处理待处理事项（m2-android.md 3.5）：填写提问的回答、允许 / 拒绝、提交，以及事项在别处被处理时收起。
 * 会话详情页和独立的处理页面共用。所有状态只在 [scope]（主线程）上修改。
 *
 * - 回答成功（`200`）：插件保证生效的是这台手机的回答，结果为 [Resolution.ANSWERED_HERE]。
 * - `409 ATTENTION_RESOLVED` 或者收到 `attention.resolved`（`by` 不是这台设备）：[Resolution.ELSEWHERE]，并通过 [notice] 提示一次。
 */
class AttentionHandler(
    private val scope: CoroutineScope,
    private val responder: AttentionResponder,
    resolvedEvents: Flow<LiveEvent.AttentionResolved>,
) {
    private val _ui = MutableStateFlow<Map<String, AttentionUi>>(emptyMap())
    val ui: StateFlow<Map<String, AttentionUi>> = _ui.asStateFlow()

    private val _notice = MutableStateFlow<Resolution?>(null)

    /** 界面上正在显示的事项在别处被处理掉时为 [Resolution.ELSEWHERE]，显示后调用 [dismissNotice]。 */
    val notice: StateFlow<Resolution?> = _notice.asStateFlow()

    /** 显示过的事项。只有它们在别处被处理时才提示（其他会话的事项不打扰）。 */
    private val shown = HashSet<String>()

    init {
        scope.launch {
            resolvedEvents.filter { it.attentionId in shown }.collect { event ->
                if (event.by == ResolvedBy.THIS_DEVICE) {
                    resolve(event.attentionId, Resolution.ANSWERED_HERE)
                } else {
                    resolve(event.attentionId, Resolution.ELSEWHERE)
                }
            }
        }
    }

    /** 界面显示了这些事项（之后它们在别处被处理时会提示）。 */
    fun shown(attentionIds: Collection<String>) {
        shown += attentionIds
    }

    fun toggle(
        attention: Attention,
        questionId: String,
        label: String,
    ) {
        val question = attention.question?.questions?.firstOrNull { it.id == questionId } ?: return
        edit(attention.attentionId) { ui ->
            val draft = ui.drafts[questionId] ?: QuestionDraft()
            ui.copy(drafts = ui.drafts + (questionId to AnswerForm.toggle(question, draft, label)), error = null)
        }
    }

    fun setCustom(
        attention: Attention,
        questionId: String,
        text: String,
    ) {
        edit(attention.attentionId) { ui ->
            val draft = ui.drafts[questionId] ?: QuestionDraft()
            ui.copy(drafts = ui.drafts + (questionId to draft.copy(custom = text)), problems = ui.problems - questionId, error = null)
        }
    }

    /** 审批：允许或拒绝（只对这一次请求有效，protocol.md 5.3）。 */
    fun decide(
        attention: Attention,
        allow: Boolean,
    ) = submit(attention.attentionId, AttentionAnswer.Decision(allow))

    /**
     * 提交提问的回答。先在本地校验，不通过时不发送。返回 true 表示已经发出（结果之后通过 [ui] 反映）。
     */
    fun submitAnswers(attention: Attention): Boolean {
        val set = attention.question ?: return false
        val ui = _ui.value[attention.attentionId] ?: AttentionUi()
        if (ui.submitting || ui.resolution != null) return false
        return when (val check = AnswerForm.check(set, ui.drafts)) {
            AnswerCheck.NothingAnswered -> {
                edit(attention.attentionId) { it.copy(error = AttentionError.NOTHING_ANSWERED) }
                false
            }
            is AnswerCheck.Invalid -> {
                edit(attention.attentionId) { it.copy(problems = check.problems, error = AttentionError.CUSTOM_TOO_LONG) }
                false
            }
            is AnswerCheck.Ok -> {
                submit(attention.attentionId, check.answer)
                true
            }
        }
    }

    /**
     * 只有一个问题、而且是单选时，点选项就是回答（设计规格 7.5）：选中这一项，丢掉自己写的内容，马上提交。
     * 不是这种提问时只切换选项，不提交。
     */
    fun answerOption(
        attention: Attention,
        questionId: String,
        label: String,
    ) {
        val set = attention.question ?: return
        val question = set.questions.singleOrNull()?.takeIf { it.id == questionId && !it.multiSelect }
        if (question == null || question.options.none { it.label == label }) {
            toggle(attention, questionId, label)
            return
        }
        val ui = _ui.value[attention.attentionId] ?: AttentionUi()
        if (ui.submitting || ui.resolution != null) return
        edit(attention.attentionId) {
            it.copy(drafts = mapOf(questionId to QuestionDraft(selected = listOf(label))), problems = emptyMap(), error = null)
        }
        submitAnswers(attention)
    }

    /**
     * 只有一个问题时，从下面的输入框写的答案（协议里的 `custom`）。多选题已经勾的选项一起提交。
     * 返回 true 表示通过了本地校验并已发出，输入框可以清空；false 时输入框保留文字，错误显示在面板里。
     */
    fun answerWithCustom(
        attention: Attention,
        questionId: String,
        text: String,
    ): Boolean {
        val question =
            attention.question
                ?.questions
                ?.singleOrNull()
                ?.takeIf { it.id == questionId } ?: return false
        val ui = _ui.value[attention.attentionId] ?: AttentionUi()
        if (ui.submitting || ui.resolution != null) return false
        val draft = ui.drafts[question.id] ?: QuestionDraft()
        // 单选题自己写了答案时，就不再带着之前点过的选项
        val selected = if (question.multiSelect) draft.selected else emptyList()
        edit(attention.attentionId) {
            it.copy(drafts = it.drafts + (question.id to QuestionDraft(selected, text)), problems = emptyMap(), error = null)
        }
        return submitAnswers(attention)
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private fun submit(
        attentionId: String,
        answer: AttentionAnswer,
    ) {
        val current = _ui.value[attentionId] ?: AttentionUi()
        if (current.submitting || current.resolution != null) return
        edit(attentionId) { it.copy(submitting = true, error = null, problems = emptyMap()) }
        scope.launch {
            try {
                responder.answer(attentionId, answer)
                resolve(attentionId, Resolution.ANSWERED_HERE)
            } catch (e: ApiException) {
                when {
                    e is ApiException.Http && (e.code == ErrorCode.ATTENTION_RESOLVED || e.status == HTTP_CONFLICT) ->
                        resolve(attentionId, Resolution.ELSEWHERE)
                    e is ApiException.Http && (e.code == ErrorCode.NOT_FOUND || e.status == HTTP_NOT_FOUND) ->
                        resolve(attentionId, Resolution.GONE)
                    e is ApiException.Http && e.status == HTTP_BAD_REQUEST -> fail(attentionId, AttentionError.REJECTED)
                    e is ApiException.Network -> fail(attentionId, AttentionError.OFFLINE)
                    else -> fail(attentionId, AttentionError.FAILED)
                }
            } catch (_: OfflineException) {
                fail(attentionId, AttentionError.OFFLINE)
            }
        }
    }

    private fun fail(
        attentionId: String,
        error: AttentionError,
    ) = edit(attentionId) { it.copy(submitting = false, error = error) }

    /** 第一次得到的结论为准（例如自己回答成功后又收到 `attention.resolved`）。 */
    private fun resolve(
        attentionId: String,
        resolution: Resolution,
    ) {
        val previous = _ui.value[attentionId]?.resolution
        if (previous != null) return
        edit(attentionId) { it.copy(submitting = false, error = null, resolution = resolution) }
        if (resolution == Resolution.ELSEWHERE && attentionId in shown) _notice.value = Resolution.ELSEWHERE
    }

    private fun edit(
        attentionId: String,
        change: (AttentionUi) -> AttentionUi,
    ) {
        _ui.update { all -> all + (attentionId to change(all[attentionId] ?: AttentionUi())) }
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
    }
}
