package io.github.qianyeeew.dshremote.data.live

import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.GoalStatus
import io.github.qianyeeew.dshremote.data.api.PlanGoal
import io.github.qianyeeew.dshremote.data.api.PlanStep
import io.github.qianyeeew.dshremote.data.api.SessionPlan
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.StepStatus
import io.github.qianyeeew.dshremote.data.api.Turn
import io.github.qianyeeew.dshremote.data.api.TurnResult
import io.github.qianyeeew.dshremote.data.live.FakePlugin.Companion.approval
import io.github.qianyeeew.dshremote.data.live.FakePlugin.Companion.event
import io.github.qianyeeew.dshremote.data.live.FakePlugin.Companion.message
import io.github.qianyeeew.dshremote.data.live.FakePlugin.Companion.session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** protocol.md 6.1、6.2 的消息格式，以及退避和去重。 */
class StreamEventTest {
    @Test
    fun parsesHello() {
        val hello =
            StreamEvent.parse(
                event("hello", """"protocol":1,"sessions":[${session("s1", status = "waiting")}],"attention":[${approval("a1")}]""", "e1"),
            ) as StreamEvent.Hello
        assertEquals("e1", hello.eventId)
        assertEquals(1, hello.protocol)
        assertEquals(SessionStatus.WAITING, hello.sessions.single().status)
        assertEquals(AttentionKind.APPROVAL, hello.attention.single().kind)
    }

    @Test
    fun parsesEveryEventType() {
        assertEquals("s1", (parse("session.updated", """"session":${session("s1")}""") as StreamEvent.SessionUpdated).session.sessionId)
        assertEquals(StreamEvent.SessionRemoved("e", "s1"), parse("session.removed", """"sessionId":"s1""""))
        assertEquals(StreamEvent.SessionIdle("e", "s1", null), parse("session.idle", """"sessionId":"s1","title":null"""))
        assertEquals(
            StreamEvent.SessionIdle("e", "s1", "T", TurnResult.FAILED),
            parse("session.idle", """"sessionId":"s1","title":"T","result":"failed""""),
        )
        assertEquals(
            StreamEvent.SessionIdle("e", "s1", null, TurnResult.UNKNOWN),
            parse("session.idle", """"sessionId":"s1","title":null,"result":"future""""),
        )
        assertEquals(
            StreamEvent.TurnUpdated("e", "s1", Turn(4, startedAt = 1, endedAt = 2, result = TurnResult.COMPLETED)),
            parse("turn.updated", """"sessionId":"s1","turn":{"turn":4,"startedAt":1,"endedAt":2,"result":"completed"}"""),
        )
        // 落地方案 B5：计划整条替换；不认识的状态变成 OTHER / PENDING
        assertEquals(
            StreamEvent.PlanUpdated(
                "e",
                "s1",
                SessionPlan(
                    PlanGoal("目标", GoalStatus.OTHER),
                    listOf(PlanStep("一", StepStatus.COMPLETED), PlanStep("二", StepStatus.PENDING)),
                ),
            ),
            parse(
                "plan.updated",
                """"sessionId":"s1","plan":{"goal":{"objective":"目标","status":"archived"},"steps":[{"text":"一","status":"completed"},{"text":"二","status":"later"}]}""",
            ),
        )
        assertEquals(
            StreamEvent.PlanUpdated("e", "s1", SessionPlan()),
            parse("plan.updated", """"sessionId":"s1","plan":{"goal":null,"steps":null}"""),
        )
        assertEquals(
            "a1",
            (parse("attention.requested", """"attention":${approval("a1")}""") as StreamEvent.AttentionRequested).attention.attentionId,
        )
        assertEquals(
            StreamEvent.AttentionResolved("e", "a1", "s1", ResolvedBy.THIS_DEVICE),
            parse("attention.resolved", """"attentionId":"a1","sessionId":"s1","by":"this-device""""),
        )
        val upsert =
            parse("message.upsert", """"sessionId":"s1","rev":7,"message":${message("draft:1", "x").dropLast(1)},"draft":true}""")
                as StreamEvent.MessageUpsert
        assertEquals(7L, upsert.rev)
        assertTrue(upsert.message.draft)
        assertNull(upsert.replaces)
        assertEquals(
            StreamEvent.MessageRemoved("e", "s1", "draft:1"),
            parse("message.removed", """"sessionId":"s1","messageId":"draft:1""""),
        )
        assertEquals(StreamEvent.Error("e", ErrorCode.DSH_UNAVAILABLE, "m"), parse("error", """"code":"DSH_UNAVAILABLE","message":"m""""))
        assertEquals(StreamEvent.Error("e", ErrorCode.UNKNOWN, null), parse("error", """"code":"NEW_CODE""""))
    }

    @Test
    fun unknownTypeAndExtraFieldsAreTolerated() {
        assertEquals(StreamEvent.Unknown("e", "future.event"), parse("future.event", """"x":1"""))
        assertEquals(StreamEvent.SessionRemoved("e", "s1"), parse("session.removed", """"sessionId":"s1","extra":{"a":1}"""))
        assertEquals(
            ResolvedBy.UNKNOWN,
            (parse("attention.resolved", """"attentionId":"a","sessionId":"s","by":"x"""") as StreamEvent.AttentionResolved).by,
        )
    }

    @Test
    fun malformedMessagesAreDropped() {
        listOf(
            "",
            "not json",
            "[1,2]",
            """{"eventId":"e"}""",
            """{"type":"hello"}""",
            """{"type":"hello","eventId":""}""",
            """{"type":1,"eventId":"e"}""",
            event("hello", """"protocol":"1","sessions":[],"attention":[]"""),
            event("hello", """"protocol":1,"sessions":[]"""),
            event("session.updated", """"session":{"sessionId":"s1"}"""),
            event("session.updated", """"session":null"""),
            event("session.removed", """"sessionId":5"""),
            event("message.upsert", """"sessionId":"s1","message":${message("m", "x")}"""),
            event("message.upsert", """"sessionId":"s1","rev":1,"message":{"id":"m"}"""),
            event("attention.resolved", """"attentionId":"a""""),
        ).forEach { assertNull("应丢弃：$it", StreamEvent.parse(it)) }
    }

    @Test
    fun commandsMatchProtocol() {
        assertEquals("""{"type":"subscribe","sessionId":"s_1-a"}""", StreamCommands.subscribe("s_1-a"))
        assertEquals("""{"type":"unsubscribe","sessionId":"s1"}""", StreamCommands.unsubscribe("s1"))
    }

    @Test
    fun backoffGrowsToMaxWithJitterAndResets() {
        val backoff = Backoff(initialMillis = 1_000, maxMillis = 30_000, random = Random(42))
        val bases = listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000)
        for (base in bases) {
            val wait = backoff.next()
            assertTrue("$wait 应在 [${base / 2}, $base] 内", wait in base / 2..base)
        }
        repeat(100) { assertTrue(backoff.next() in 15_000L..30_000) }
        backoff.reset()
        assertTrue(backoff.next() in 500L..1_000)
    }

    @Test
    fun recentIdsForgetOldestBeyondCapacity() {
        val ids = RecentIds(capacity = 3)
        assertTrue(ids.add("a"))
        assertFalse(ids.add("a"))
        ids.add("b")
        ids.add("c")
        ids.add("d")
        // a 被挤出去了，再出现时当作新的
        assertTrue(ids.add("a"))
        assertFalse(ids.add("d"))
    }

    private fun parse(
        type: String,
        body: String,
    ): StreamEvent? = StreamEvent.parse(event(type, body, "e"))
}
