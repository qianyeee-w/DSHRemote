package io.github.qianyeeew.dshremote.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.Question
import io.github.qianyeeew.dshremote.data.api.QuestionOption
import io.github.qianyeeew.dshremote.data.api.QuestionSet
import io.github.qianyeeew.dshremote.data.attention.AnswerForm
import io.github.qianyeeew.dshremote.data.attention.QuestionDraft
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.attention.AttentionCard
import io.github.qianyeeew.dshremote.ui.attention.AttentionCardActions
import io.github.qianyeeew.dshremote.ui.attention.AttentionPageContent
import io.github.qianyeeew.dshremote.ui.attention.AttentionPageState
import io.github.qianyeeew.dshremote.ui.attention.AttentionUi
import io.github.qianyeeew.dshremote.ui.attention.Resolution
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 审批和提问的卡片（m2-android.md 3.5）。 */
@RunWith(AndroidJUnit4::class)
class AttentionCardUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun approvalOffersOnlyAllowAndRejectForThisRequest() {
        val decisions = mutableListOf<Boolean>()
        val attention =
            Attention("a1", "s1", AttentionKind.APPROVAL, approval = Approval("bash", "需要更高的权限", "运行命令：npm test"), createdAt = 0)
        compose.setContent {
            DshRemoteTheme {
                AttentionCard(attention, AttentionUi(), AttentionCardActions(onDecide = { _, allow -> decisions += allow }))
            }
        }
        compose.onNodeWithText("运行命令：npm test").assertExists()
        compose.onNodeWithText(text(R.string.attention_approval_once)).assertExists()
        // DSH 的审批只对当前这一个请求有效，没有「以后都允许」（spec-v1、protocol.md 5.3）
        val labels =
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            }
        assertEquals(setOf(text(R.string.attention_reject), text(R.string.attention_allow)), labels.toSet())

        compose.onNodeWithText(text(R.string.attention_reject)).performClick()
        compose.onNodeWithText(text(R.string.attention_allow)).performClick()
        assertEquals(listOf(false, true), decisions)
    }

    @Test
    fun singleAndMultipleChoiceQuestions() {
        val single = Question("q1", "用哪种方案？", "选择", listOf(QuestionOption("方案A", "快"), QuestionOption("方案B")))
        val multi = Question("q2", "要哪些功能？", options = listOf(QuestionOption("甲"), QuestionOption("乙")), multiSelect = true)
        val attention = Attention("a2", "s1", AttentionKind.QUESTION, question = QuestionSet(listOf(single, multi)), createdAt = 0)
        var drafts by mutableStateOf(mapOf<String, QuestionDraft>())
        var submitted = 0
        compose.setContent {
            DshRemoteTheme {
                AttentionCard(
                    attention,
                    AttentionUi(drafts = drafts),
                    AttentionCardActions(
                        onToggle = { _, q, label ->
                            val question = if (q == "q1") single else multi
                            drafts = drafts + (q to AnswerForm.toggle(question, drafts[q] ?: QuestionDraft(), label))
                        },
                        onSubmit = { submitted++ },
                    ),
                )
            }
        }
        compose.onNodeWithText("方案A").performClick()
        compose.onNodeWithText("方案B").performClick()
        compose.onNodeWithText("甲").performClick()
        compose.onNodeWithText("乙").performClick()
        assertEquals(listOf("方案B"), drafts["q1"]?.selected)
        assertEquals(listOf("甲", "乙"), drafts["q2"]?.selected)
        compose.onNodeWithText(text(R.string.attention_submit)).performClick()
        assertEquals(1, submitted)
        // 选中状态体现在无障碍信息中（单选是 selected，多选是 toggle）
        compose.onNodeWithText("方案B", useUnmergedTree = false).assertIsSelected()
        compose.onNodeWithText("甲").assertIsOn()
        compose.onNodeWithText("乙").performClick()
        compose.onNodeWithText("乙").assertIsOff()
    }

    @Test
    fun standalonePageShowsTheRightState() {
        val attention = Attention("a1", "sub-1", AttentionKind.APPROVAL, approval = Approval("bash", "r", "运行命令：ls"), createdAt = 0)
        var state by mutableStateOf(AttentionPageState(link = online, attention = attention, pending = true))
        var opened: String? = null
        compose.setContent {
            DshRemoteTheme { AttentionPageContent(state, AttentionCardActions(), onBack = {}, onOpenSession = { opened = it }) }
        }
        compose.onNodeWithText("运行命令：ls").assertExists()
        // 子代理的事项不在会话列表中：没有「打开会话」
        compose.onNodeWithText(text(R.string.attention_open_session)).assertDoesNotExist()

        state = state.copy(pending = false, ui = AttentionUi(resolution = Resolution.ELSEWHERE))
        compose.onNodeWithText(text(R.string.attention_done_elsewhere)).assertExists()
        compose.onNodeWithText(text(R.string.attention_allow)).assertDoesNotExist()

        state = AttentionPageState(link = online, attention = attention.copy(sessionId = "s1"), pending = false, sessionKnown = true)
        compose.onNodeWithText(text(R.string.attention_done_elsewhere)).assertExists()
        compose.onNodeWithText(text(R.string.attention_open_session)).performClick()
        assertEquals("s1", opened)

        state = AttentionPageState(link = online)
        compose.onNodeWithText(text(R.string.attention_not_found)).assertExists()
        state = AttentionPageState(link = LinkStatus.Connecting)
        compose.onNodeWithText(text(R.string.attention_waiting_connection)).assertExists()
    }

    private val online = LinkStatus.Online(HostAddress("192.168.1.5", 3190))

    private fun text(id: Int): String = context.getString(id)
}
