package io.github.qianyeeew.dshremote.ui.attention

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionAnswer
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.Question
import io.github.qianyeeew.dshremote.data.api.QuestionAnswer
import io.github.qianyeeew.dshremote.data.api.QuestionOption
import io.github.qianyeeew.dshremote.data.api.QuestionSet
import io.github.qianyeeew.dshremote.data.live.LiveEvent
import io.github.qianyeeew.dshremote.data.live.OfflineException
import io.github.qianyeeew.dshremote.data.live.ResolvedBy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 允许 / 拒绝、回答提问、409 和 attention.resolved 时收起并提示。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttentionHandlerTest {
    private val events = MutableSharedFlow<LiveEvent.AttentionResolved>(extraBufferCapacity = 8)
    private val answers = mutableListOf<Pair<String, AttentionAnswer>>()
    private var failWith: Exception? = null
    private var gate: CompletableDeferred<Unit>? = null
    private val handler =
        AttentionHandler(
            CoroutineScope(UnconfinedTestDispatcher()),
            { id, answer ->
                answers += id to answer
                gate?.await()
                failWith?.let { throw it }
            },
            events,
        )

    private val approval = Attention("a1", "s1", AttentionKind.APPROVAL, approval = Approval("bash", "允许运行命令"), createdAt = 0)
    private val question =
        Attention(
            "a2",
            "s1",
            AttentionKind.QUESTION,
            question = QuestionSet(listOf(Question("q1", "用哪种方案？", options = listOf(QuestionOption("方案A"), QuestionOption("方案B"))))),
            createdAt = 0,
        )

    @Test
    fun allowIsSentOnceAndResolvesHere() {
        gate = CompletableDeferred()
        handler.shown(listOf("a1"))
        handler.decide(approval, allow = true)
        assertTrue(ui("a1").submitting)
        handler.decide(approval, allow = false)
        gate!!.complete(Unit)
        assertEquals(listOf("a1" to AttentionAnswer.Decision(true)), answers)
        assertEquals(Resolution.ANSWERED_HERE, ui("a1").resolution)
        // 之后收到自己这台设备的 attention.resolved，不提示
        events.tryEmit(LiveEvent.AttentionResolved("h1", "a1", "s1", ResolvedBy.THIS_DEVICE))
        assertNull(handler.notice.value)
    }

    @Test
    fun conflictMeansHandledElsewhere() {
        handler.shown(listOf("a1"))
        failWith = ApiException.Http(409, ErrorCode.ATTENTION_RESOLVED, null)
        handler.decide(approval, allow = false)
        assertEquals(Resolution.ELSEWHERE, ui("a1").resolution)
        assertEquals(Resolution.ELSEWHERE, handler.notice.value)
        handler.dismissNotice()
        assertNull(handler.notice.value)
    }

    @Test
    fun resolvedElsewhereWhileShownIsNoticed() {
        handler.shown(listOf("a1"))
        events.tryEmit(LiveEvent.AttentionResolved("h1", "a1", "s1", ResolvedBy.OTHER))
        assertEquals(Resolution.ELSEWHERE, ui("a1").resolution)
        assertEquals(Resolution.ELSEWHERE, handler.notice.value)
        // 已经结束的事项不能再提交
        handler.decide(approval, allow = true)
        assertTrue(answers.isEmpty())
    }

    @Test
    fun eventsForItemsNotShownAreIgnored() {
        events.tryEmit(LiveEvent.AttentionResolved("h1", "other", "s9", ResolvedBy.OTHER))
        assertNull(handler.notice.value)
        assertTrue(handler.ui.value.isEmpty())
    }

    @Test
    fun networkErrorsCanBeRetried() {
        failWith = ApiException.Network(IOException("x"))
        handler.decide(approval, allow = true)
        assertEquals(AttentionError.OFFLINE, ui("a1").error)
        assertNull(ui("a1").resolution)

        failWith = OfflineException()
        handler.decide(approval, allow = true)
        assertEquals(AttentionError.OFFLINE, ui("a1").error)

        failWith = null
        handler.decide(approval, allow = true)
        assertEquals(Resolution.ANSWERED_HERE, ui("a1").resolution)
        assertEquals(3, answers.size)
    }

    @Test
    fun otherErrors() {
        failWith = ApiException.Http(404, ErrorCode.NOT_FOUND, null)
        handler.decide(approval, allow = true)
        assertEquals(Resolution.GONE, ui("a1").resolution)

        failWith = ApiException.Http(400, ErrorCode.BAD_REQUEST, null)
        handler.submitAnswers(question.copy(attentionId = "a3"))
        // 什么都没选：本地就拦下，不发送
        assertEquals(AttentionError.NOTHING_ANSWERED, ui("a3").error)
        handler.toggle(question.copy(attentionId = "a3"), "q1", "方案A")
        handler.submitAnswers(question.copy(attentionId = "a3"))
        assertEquals(AttentionError.REJECTED, ui("a3").error)

        failWith = ApiException.Http(502, ErrorCode.DSH_ERROR, null)
        handler.decide(approval.copy(attentionId = "a4"), allow = true)
        assertEquals(AttentionError.FAILED, ui("a4").error)
    }

    @Test
    fun questionAnswersAreValidatedLocallyThenSent() {
        handler.submitAnswers(question)
        assertEquals(AttentionError.NOTHING_ANSWERED, ui("a2").error)
        assertTrue(answers.isEmpty())

        handler.setCustom(question, "q1", "x".repeat(4_001))
        handler.submitAnswers(question)
        assertEquals(AttentionError.CUSTOM_TOO_LONG, ui("a2").error)
        assertTrue(ui("a2").problems.containsKey("q1"))
        assertTrue(answers.isEmpty())

        handler.setCustom(question, "q1", "")
        handler.toggle(question, "q1", "方案B")
        handler.submitAnswers(question)
        assertEquals(listOf("a2" to AttentionAnswer.Answers(listOf(QuestionAnswer("q1", listOf("方案B"))))), answers)
        assertEquals(Resolution.ANSWERED_HERE, ui("a2").resolution)
    }

    private val multiSelect =
        Attention(
            "a4",
            "s1",
            AttentionKind.QUESTION,
            question =
                QuestionSet(
                    listOf(Question("q1", "要改哪些？", options = listOf(QuestionOption("通知"), QuestionOption("文案")), multiSelect = true)),
                ),
            createdAt = 0,
        )
    private val twoQuestions =
        Attention(
            "a5",
            "s1",
            AttentionKind.QUESTION,
            question =
                QuestionSet(
                    listOf(
                        Question("q1", "第一题", options = listOf(QuestionOption("是"), QuestionOption("否"))),
                        Question("q2", "第二题", options = listOf(QuestionOption("好"))),
                    ),
                ),
            createdAt = 0,
        )

    /** 只有一个问题而且是单选：点选项就是回答（设计规格 7.5）。 */
    @Test
    fun singleChoiceOptionAnswersImmediately() {
        handler.answerOption(question, "q1", "方案B")
        assertEquals(listOf("a2" to AttentionAnswer.Answers(listOf(QuestionAnswer("q1", listOf("方案B"))))), answers)
        assertEquals(Resolution.ANSWERED_HERE, ui("a2").resolution)
    }

    /** 多选、多个问题时点选项只是勾选，要按「回答」才提交。 */
    @Test
    fun multiSelectAndMultipleQuestionsNeedConfirmation() {
        handler.answerOption(multiSelect, "q1", "通知")
        handler.answerOption(multiSelect, "q1", "文案")
        handler.answerOption(twoQuestions, "q1", "是")
        assertEquals(emptyList<Pair<String, AttentionAnswer>>(), answers)
        assertEquals(listOf("通知", "文案"), ui("a4").drafts.getValue("q1").selected)
        assertTrue(handler.submitAnswers(multiSelect))
        assertEquals(AttentionAnswer.Answers(listOf(QuestionAnswer("q1", listOf("通知", "文案")))), answers.single().second)
    }

    /** 用输入框写的答案作为 custom 提交；多选题带上已经勾的选项，单选题不带。 */
    @Test
    fun customAnswerFromTheInput() {
        handler.answerOption(multiSelect, "q1", "通知")
        assertTrue(handler.answerWithCustom(multiSelect, "q1", "还有图标"))
        assertEquals(AttentionAnswer.Answers(listOf(QuestionAnswer("q1", listOf("通知"), "还有图标"))), answers.last().second)

        handler.toggle(question, "q1", "方案A")
        assertTrue(handler.answerWithCustom(question, "q1", "都不要"))
        assertEquals(AttentionAnswer.Answers(listOf(QuestionAnswer("q1", emptyList(), "都不要"))), answers.last().second)
    }

    /** 本地校验不通过时不发送，返回 false，输入框保留文字。 */
    @Test
    fun customAnswerThatFailsValidationIsNotSent() {
        assertEquals(false, handler.answerWithCustom(question, "q1", "x".repeat(4_001)))
        assertEquals(AttentionError.CUSTOM_TOO_LONG, ui("a2").error)
        assertEquals(false, handler.answerWithCustom(question, "q1", "   "))
        assertEquals(AttentionError.NOTHING_ANSWERED, ui("a2").error)
        // 多个问题的提问不能从底部输入框回答
        assertEquals(false, handler.answerWithCustom(twoQuestions, "q1", "是"))
        assertTrue(answers.isEmpty())
    }

    /** 提交中再点不会发第二次。 */
    @Test
    fun answeringTwiceWhileSubmittingSendsOnce() {
        gate = CompletableDeferred()
        handler.answerOption(question, "q1", "方案A")
        handler.answerOption(question, "q1", "方案B")
        assertEquals(false, handler.answerWithCustom(question, "q1", "改主意了"))
        gate!!.complete(Unit)
        assertEquals(1, answers.size)
    }

    private fun ui(id: String) = handler.ui.value.getValue(id)
}
