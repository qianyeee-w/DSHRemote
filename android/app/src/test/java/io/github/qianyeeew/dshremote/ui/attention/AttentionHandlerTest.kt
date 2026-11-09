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

    private fun ui(id: String) = handler.ui.value.getValue(id)
}
