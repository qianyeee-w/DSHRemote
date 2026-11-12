package io.github.qianyeeew.dshremote.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.Question
import io.github.qianyeeew.dshremote.data.api.QuestionOption
import io.github.qianyeeew.dshremote.data.api.QuestionSet
import io.github.qianyeeew.dshremote.data.api.SessionStatus
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
import io.github.qianyeeew.dshremote.ui.chat.ChatActions
import io.github.qianyeeew.dshremote.ui.chat.ChatContent
import io.github.qianyeeew.dshremote.ui.chat.Picker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
            Attention(
                "a1",
                "s1",
                AttentionKind.APPROVAL,
                approval = Approval("bash", "需要更高的权限", "运行命令：npm test", JsonObject(mapOf("command" to JsonPrimitive("npm test")))),
                createdAt = 0,
            )
        compose.setContent {
            DshRemoteTheme {
                AttentionCard(
                    attention,
                    AttentionUi(),
                    AttentionCardActions(onDecide = {
                        _,
                        allow,
                        ->
                        decisions += allow
                    }),
                    cwd = "~/code/app",
                )
            }
        }
        compose.onNodeWithText(text(R.string.attention_run)).assertExists()
        compose.onNodeWithTag("approval-target").assertTextEquals("npm test")
        compose.onNodeWithText("~/code/app").assertExists()
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
        compose.onNodeWithTag("attention-answer").performClick()
        assertEquals(1, submitted)
        // 多个问题时每题下面有自己写的输入框
        assertEquals(2, compose.onAllNodesWithContentDescription(text(R.string.attention_custom_hint)).fetchSemanticsNodes().size)
        // 选中状态体现在无障碍信息中（单选是 selected，多选是 toggle）
        compose.onNodeWithText("方案B", useUnmergedTree = false).assertIsSelected()
        compose.onNodeWithText("甲").assertIsOn()
        compose.onNodeWithText("乙").performClick()
        compose.onNodeWithText("乙").assertIsOff()
    }

    @Test
    fun standalonePageShowsTheRightState() {
        val attention = Attention("a1", "sub-1", AttentionKind.APPROVAL, approval = Approval("bash", "需要运行 ls", "运行命令：ls"), createdAt = 0)
        var state by mutableStateOf(AttentionPageState(link = online, attention = attention, pending = true))
        var opened: String? = null
        compose.setContent {
            DshRemoteTheme { AttentionPageContent(state, AttentionCardActions(), onBack = {}, onOpenSession = { opened = it }) }
        }
        // 插件没找到对应的工具调用（没有 input）时显示审批的 reason
        compose.onNodeWithText("需要运行 ls").assertExists()
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

    /** 只有一个问题而且是单选：点选项就是回答，没有「回答」按钮（设计规格 7.5）。 */
    @Test
    fun singleChoiceAnswersOnTap() {
        val attention =
            Attention(
                "a3",
                "s1",
                AttentionKind.QUESTION,
                question =
                    QuestionSet(
                        listOf(Question("q1", "要保留时间戳吗？", options = listOf(QuestionOption("保留原时间", "推荐"), QuestionOption("用导入时间")))),
                    ),
                createdAt = 0,
            )
        val picked = mutableListOf<String>()
        compose.setContent {
            DshRemoteTheme {
                AttentionCard(attention, AttentionUi(), AttentionCardActions(onAnswerOption = { _, _, label -> picked += label }))
            }
        }
        compose.onNodeWithText(text(R.string.attention_decide)).assertExists()
        compose.onNodeWithTag("attention-answer").assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.attention_custom_hint)).assertDoesNotExist()
        compose.onNodeWithText("用导入时间").performClick()
        assertEquals(listOf("用导入时间"), picked)
    }

    /** 多选：点选项只是勾选，按「回答」才提交。 */
    @Test
    fun multiSelectNeedsConfirmation() {
        val handlerAnswers = mutableListOf<String>()
        val attention =
            Attention(
                "a4",
                "s1",
                AttentionKind.QUESTION,
                question =
                    QuestionSet(
                        listOf(Question("q1", "改哪些？", options = listOf(QuestionOption("通知"), QuestionOption("文案")), multiSelect = true)),
                    ),
                createdAt = 0,
            )
        var toggled = 0
        compose.setContent {
            DshRemoteTheme {
                AttentionCard(
                    attention,
                    AttentionUi(),
                    AttentionCardActions(
                        onToggle = { _, _, _ -> toggled++ },
                        onAnswerOption = { _, _, label -> handlerAnswers += label },
                        onSubmit = { handlerAnswers += "submit" },
                    ),
                )
            }
        }
        compose.onNodeWithText("通知").performClick()
        assertEquals(1, toggled)
        assertEquals(emptyList<String>(), handlerAnswers)
        compose.onNodeWithTag("attention-answer").performClick()
        assertEquals(listOf("submit"), handlerAnswers)
    }

    /** 审批在等时输入框不能写，只留停止；在别处处理掉、面板收起后输入框恢复。 */
    @Test
    fun approvalBlocksTheInputAndPanelCollapsesWhenHandledElsewhere() {
        val attention =
            Attention(
                "a5",
                "visual-test",
                AttentionKind.APPROVAL,
                approval =
                    Approval(
                        "bash",
                        "r",
                        input =
                            JsonObject(
                                mapOf(
                                    "command" to JsonPrimitive("ls"),
                                ),
                            ),
                    ),
                createdAt = 0,
            )
        var state by mutableStateOf(onlineChatState(SessionStatus.WAITING).copy(attention = listOf(attention)))
        compose.setContent { DshRemoteTheme { ChatContent(state, null, Picker.Closed, ChatActions()) } }
        compose.onNodeWithTag("attention-panel").assertIsDisplayed()
        compose.onNodeWithTag("chat-input").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.chat_input_hint_approval)).assertIsDisplayed()
        compose.onNodeWithTag("chat-stop").assertIsEnabled()
        // 视图模型去掉已经结束的事项（在别处处理掉）
        compose.runOnIdle { state = onlineChatState(SessionStatus.RUNNING) }
        compose.onNodeWithTag("attention-panel").assertDoesNotExist()
        compose.onNodeWithTag("chat-input").assertIsEnabled()
    }

    /** 只有一个问题时，输入框里写的就是答案。 */
    @Test
    fun singleQuestionTakesTheAnswerFromTheInput() {
        val attention =
            Attention(
                "a6",
                "visual-test",
                AttentionKind.QUESTION,
                question = QuestionSet(listOf(Question("q1", "叫什么名字？", options = listOf(QuestionOption("甲"))))),
                createdAt = 0,
            )
        val answered = mutableListOf<Pair<String, String>>()
        val sent = mutableListOf<String>()
        compose.setContent {
            DshRemoteTheme {
                ChatContent(
                    onlineChatState(SessionStatus.WAITING).copy(attention = listOf(attention)),
                    null,
                    Picker.Closed,
                    ChatActions(onSend = {
                        sent += it
                        true
                    }),
                    attentionActions =
                        AttentionCardActions(onAnswerWithCustom = { _, q, t ->
                            answered += q to t
                            true
                        }),
                )
            }
        }
        compose.onNodeWithText(text(R.string.chat_input_hint_answer)).assertIsDisplayed()
        compose.onNodeWithTag("chat-input").performTextInput("乙")
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals(listOf("q1" to "乙"), answered)
            assertEquals(emptyList<String>(), sent)
        }
    }

    /** 给人看的样张：会话页底部的审批和提问面板。 */
    @Test
    fun saveAttentionScreenshots() {
        val approval =
            Attention(
                "p1",
                "visual-test",
                AttentionKind.APPROVAL,
                approval =
                    Approval(
                        "bash",
                        "r",
                        input =
                            JsonObject(
                                mapOf(
                                    "command" to JsonPrimitive("./gradlew test"),
                                ),
                            ),
                    ),
                createdAt = 0,
            )
        val question =
            Attention(
                "p2",
                "visual-test",
                AttentionKind.QUESTION,
                question =
                    QuestionSet(
                        listOf(
                            Question(
                                "q1",
                                "要保留旧笔记原来的时间戳吗？",
                                options = listOf(QuestionOption("保留原时间", "排序和导入前一致"), QuestionOption("用导入时间"), QuestionOption("先不导入")),
                            ),
                        ),
                    ),
                createdAt = 0,
            )
        val base = onlineChatState(SessionStatus.WAITING)
        var state by mutableStateOf(base.copy(attention = listOf(approval), session = base.session?.copy(cwd = "~/code/dshremote/android")))
        compose.setContent { DshRemoteTheme { ChatContent(state, null, Picker.Closed, ChatActions()) } }
        save("attention-approval")
        state = base.copy(attention = listOf(question))
        save("attention-question")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "visual-verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val online = LinkStatus.Online(HostAddress("192.168.1.5", 3190))

    private fun text(id: Int): String = context.getString(id)
}
