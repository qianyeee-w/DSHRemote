package io.github.qianyeeew.dshremote.ui.attention

import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.AttentionRef
import io.github.qianyeeew.dshremote.data.api.Question
import io.github.qianyeeew.dshremote.data.api.QuestionSet
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttentionRulesTest {
    private fun approval(
        tool: String,
        vararg input: Pair<String, String>,
        id: String = "a",
        session: String = "s",
    ) = Attention(
        id,
        session,
        AttentionKind.APPROVAL,
        approval =
            Approval(
                tool,
                "原因",
                input =
                    if (input.isEmpty()) {
                        null
                    } else {
                        JsonObject(
                            input.associate {
                                it.first to
                                    JsonPrimitive(it.second)
                            },
                        )
                    },
            ),
        createdAt = 0,
    )

    private fun question(
        vararg ids: String,
        id: String = "q",
    ) = Attention(id, "s", AttentionKind.QUESTION, question = QuestionSet(ids.map { Question(it, "问题 $it") }), createdAt = 0)

    @Test
    fun composerFollowsTheOldestPendingItem() {
        assertEquals(ComposerAttention.None, composerAttention(emptyList()))
        val shell = approval("bash")
        assertEquals(ComposerAttention.Approval(shell), composerAttention(listOf(shell, question("q1"))))
        val single = question("q1")
        assertEquals(ComposerAttention.Answer(single, "q1"), composerAttention(listOf(single)))
        val many = question("q1", "q2")
        assertEquals(ComposerAttention.AnswerAbove(many), composerAttention(listOf(many)))
        assertEquals(ComposerAttention.None, composerAttention(listOf(Attention("u", "s", AttentionKind.UNKNOWN, createdAt = 0))))
    }

    @Test
    fun approvalHeadlineAndTarget() {
        assertEquals(ApprovalAction.RUN, approvalAction("bash"))
        assertEquals(ApprovalAction.RUN, approvalAction("pwsh"))
        assertEquals(ApprovalAction.EDIT, approvalAction("edit"))
        assertEquals(ApprovalAction.EDIT, approvalAction("write"))
        assertEquals(ApprovalAction.OTHER, approvalAction("web_fetch"))
        assertEquals("./gradlew test", approvalTarget(approval("bash", "command" to "./gradlew test").approval!!))
        assertEquals("/src/A.kt", approvalTarget(approval("edit", "file_path" to "/src/A.kt").approval!!))
        // 没找到工具调用、参数里没有这一项、其他工具：改为显示 reason
        assertNull(approvalTarget(approval("bash").approval!!))
        assertNull(approvalTarget(approval("bash", "description" to "x").approval!!))
        assertNull(approvalTarget(approval("web_fetch", "url" to "https://x").approval!!))
    }

    @Test
    fun notificationOpensTheSessionThatOwnsTheItem() {
        val own = approval("bash", id = "a1", session = "s1")
        val child = approval("bash", id = "a2", session = "child")
        val host =
            HostLiveState(
                sessions =
                    mapOf(
                        "s1" to SessionSummary("s1", updatedAt = 0),
                        "s2" to SessionSummary("s2", attention = listOf(AttentionRef("a2")), updatedAt = 0),
                    ),
                attention = mapOf("a1" to own, "a2" to child),
            )
        assertEquals("s1", attentionSession(host, "a1"))
        // 子代理的事项挂在父会话上
        assertEquals("s2", attentionSession(host, "a2"))
        // 找不到会话（还没连上、事项不在列表里）：用独立的处理页
        assertNull(attentionSession(host, "missing"))
        assertNull(attentionSession(null, "a1"))
        assertNull(attentionSession(host.copy(sessions = emptyMap()), "a1"))
    }
}
