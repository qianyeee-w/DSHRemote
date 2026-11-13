package io.github.qianyeeew.dshremote.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PromptMode
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.api.ThinkingPart
import io.github.qianyeeew.dshremote.data.api.ToolPart
import io.github.qianyeeew.dshremote.data.api.ToolStatus
import io.github.qianyeeew.dshremote.data.api.Turn
import io.github.qianyeeew.dshremote.data.api.TurnResult
import io.github.qianyeeew.dshremote.data.chat.LocalMessage
import io.github.qianyeeew.dshremote.data.chat.OutgoingStatus
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.OfflineReason
import io.github.qianyeeew.dshremote.ui.chat.ChatActions
import io.github.qianyeeew.dshremote.ui.chat.ChatContent
import io.github.qianyeeew.dshremote.ui.chat.ChatItem
import io.github.qianyeeew.dshremote.ui.chat.ChatState
import io.github.qianyeeew.dshremote.ui.chat.Picker
import io.github.qianyeeew.dshremote.ui.common.TOP_BAR_SUBTITLE_TAG
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 会话页（设计规格 7.2、7.3）：顶栏、过程折叠和展开、改动对比、命令输出、发送状态、运行状态行。 */
@RunWith(AndroidJUnit4::class)
class ChatContentUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tool(
        id: String,
        name: String,
        vararg input: Pair<String, String>,
        status: ToolStatus = ToolStatus.DONE,
        output: String? = null,
    ) = ToolPart(id, name, status = status, input = JsonObject(input.associate { it.first to JsonPrimitive(it.second) }), output = output)

    private val turn =
        listOf(
            ChatItem.Entry("u1", Message("u1", MessageRole.USER, listOf(TextPart("修一下重复通知")), 0)),
            ChatItem.Entry(
                "a1",
                Message(
                    "a1",
                    MessageRole.ASSISTANT,
                    listOf(
                        ThinkingPart("先读一下规则"),
                        tool("r", "read", "file_path" to "/src/NotificationRules.kt", output = "1\n2\n3"),
                        tool(
                            "e",
                            "edit",
                            "file_path" to "/src/NotificationRules.kt",
                            "old_string" to "if (!seen.add(id)) return",
                            "new_string" to "if (!seen.add(key)) return",
                        ),
                        tool("b", "bash", "command" to "./gradlew test", output = "a\nb\nc\nd\ne\nBUILD SUCCESSFUL"),
                        TextPart("测试通过，重复通知已经修好。"),
                    ),
                    1,
                ),
            ),
        )

    private fun render(state: ChatState) {
        compose.setContent { DshRemoteTheme { ChatContent(state, null, Picker.Closed, ChatActions()) } }
    }

    @Test
    fun topBarShowsComputerProjectAndApprovalMode() {
        val base = onlineChatState()
        render(base.copy(session = base.session?.copy(permissionPreset = "workspace-write"), hostName = "工作电脑", projectName = "DSHRemote"))
        compose
            .onNodeWithTag(TOP_BAR_SUBTITLE_TAG)
            .assertTextEquals("工作电脑 · DSHRemote · ${context.getString(R.string.design_permission_workspace)}")
    }

    @Test
    fun stepsFoldIntoOneLineAndExpandInPlace() {
        render(onlineChatState().copy(items = turn))
        val summary = "${context.getString(R.string.chat_steps, 4)} · ${context.getString(R.string.chat_steps_files, 1)}"
        compose.onNodeWithText(summary).assertIsDisplayed()
        compose.onNodeWithTag("steps-list").assertDoesNotExist()
        compose.onNodeWithText("测试通过，重复通知已经修好。").assertIsDisplayed()

        compose.onNodeWithTag("steps-summary").performClick()
        compose.onNodeWithTag("steps-list").assertIsDisplayed()
        assertEquals(4, compose.onAllNodesWithTag("step-row").fetchSemanticsNodes().size)
        compose.onNodeWithText(context.getString(R.string.chat_step_changed, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_step_lines, 3)).assertIsDisplayed()

        compose.onNodeWithTag("steps-summary").performClick()
        compose.onNodeWithTag("steps-list").assertDoesNotExist()
    }

    @Test
    fun editStepShowsDiffAndRunStepShowsTheTail() {
        render(onlineChatState().copy(items = turn))
        compose.onNodeWithTag("steps-summary").performClick()
        compose.onNodeWithText("./gradlew test").performClick()
        compose.onNodeWithTag("output-block").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_output_last, 4)).assertIsDisplayed()
        compose.onNodeWithText("b\nc\nd\ne\nBUILD SUCCESSFUL".lines().takeLast(4).joinToString("\n")).assertIsDisplayed()
        compose.onNodeWithTag("output-all").performClick()
        compose.onNodeWithText("a\nb\nc\nd\ne\nBUILD SUCCESSFUL").assertIsDisplayed()

        compose.onNodeWithText(context.getString(R.string.chat_step_edit)).performClick()
        compose.onNodeWithTag("diff-block").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("if (!seen.add(key)) return").assertIsDisplayed()
    }

    /** 给人看的样张：过程展开、改动对比和命令输出都打开，浅色和深色各一张。 */
    @Test
    fun saveStepsScreenshots() {
        var dark by mutableStateOf(false)
        val base = onlineChatState()
        val state =
            base.copy(
                items = turn,
                session = base.session?.copy(permissionPreset = "workspace-write"),
                hostName = "工作电脑",
                projectName = "DSHRemote",
            )
        compose.setContent { DshRemoteTheme(dark = dark) { ChatContent(state, null, Picker.Closed, ChatActions()) } }
        compose.onNodeWithTag("steps-summary").performClick()
        compose.onNodeWithText("./gradlew test").performClick()
        compose.onNodeWithText(context.getString(R.string.chat_step_edit)).performClick()
        compose.onNodeWithTag("diff-block").performScrollTo()
        save("chat-steps-light")
        dark = true
        save("chat-steps-dark")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "visual-verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun runningSessionShowsTheCurrentStepAtTheEnd() {
        val running =
            listOf(
                ChatItem.Entry(
                    "a",
                    Message("a", MessageRole.ASSISTANT, listOf(tool("b", "bash", "command" to "npm test", status = ToolStatus.RUNNING)), 0),
                ),
            )
        render(onlineChatState(SessionStatus.RUNNING).copy(items = running))
        compose.onNodeWithTag("running-line").assertIsDisplayed()
        compose.onNodeWithText("${context.getString(R.string.chat_step_run)} npm test").assertIsDisplayed()
    }

    @Test
    fun localMessagesShowTheirSendStatus() {
        val steered = LocalMessage(OutgoingMessage("说快一点", PromptMode.STEER), OutgoingStatus.SENT)
        val failed = LocalMessage(OutgoingMessage("再试一次", PromptMode.QUEUE), OutgoingStatus.FAILED)
        var retried = ""
        compose.setContent {
            DshRemoteTheme {
                ChatContent(
                    onlineChatState().copy(items = listOf(ChatItem.Local("l1", steered), ChatItem.Local("l2", failed))),
                    null,
                    Picker.Closed,
                    ChatActions(onRetrySend = { retried = it }),
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.chat_local_steered)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_local_failed)).assertIsDisplayed()
        compose.onNodeWithTag("chat-retry").performClick()
        compose.runOnIdle { assertEquals(failed.outgoing.requestId, retried) }
    }

    /** 落地方案 B3：请求发出但没收到回应，「不确定是否送达 · 查看 · 重发」，两个都只在用户点时才执行。 */
    @Test
    fun uncertainMessageOffersCheckAndResend() {
        val uncertain = LocalMessage(OutgoingMessage("不知道到没到", PromptMode.QUEUE), OutgoingStatus.UNCERTAIN)
        val checked = mutableListOf<String>()
        val resent = mutableListOf<String>()
        compose.setContent {
            DshRemoteTheme {
                ChatContent(
                    onlineChatState().copy(items = listOf(ChatItem.Local("l1", uncertain))),
                    null,
                    Picker.Closed,
                    ChatActions(onCheckSend = { checked += it }, onRetrySend = { resent += it }),
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.chat_local_uncertain)).assertIsDisplayed()
        compose.onNodeWithTag("chat-retry").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), checked)
            assertEquals(emptyList<String>(), resent)
        }
        compose.onNodeWithTag("chat-check").performClick()
        compose.runOnIdle { assertEquals(listOf(uncertain.outgoing.requestId), checked) }
        compose.onNodeWithTag("chat-resend").performClick()
        compose.runOnIdle { assertEquals(listOf(uncertain.outgoing.requestId), resent) }
    }

    /** 每一轮的结尾（设计规格 6.7）：结果加用时，不写「已完成」；失败是红字加原因。 */
    @Test
    fun finishedTurnsEndWithResultAndDuration() {
        val numbered = turn.map { (it as ChatItem.Entry).let { e -> e.copy(message = e.message.copy(turn = 1)) } }
        val failed =
            listOf(
                ChatItem.Entry("u2", Message("u2", MessageRole.USER, listOf(TextPart("再试一次")), 2, turn = 2)),
            )
        val turns =
            mapOf(
                1 to Turn(1, startedAt = 0, endedAt = 290_000, result = TurnResult.COMPLETED),
                2 to Turn(2, startedAt = 300_000, endedAt = 301_000, result = TurnResult.FAILED, error = "invalid request"),
            )
        render(onlineChatState().copy(items = numbered + failed, turns = turns))
        val dot = context.getString(R.string.separator_dot)
        compose
            .onNodeWithText("${context.getString(R.string.turn_ended)}$dot${context.getString(R.string.duration_minutes, 4, 50)}")
            .assertIsDisplayed()
        compose
            .onNodeWithText(
                "${context.getString(
                    R.string.turn_failed_reason,
                    "invalid request",
                )}$dot${context.getString(R.string.duration_seconds, 1)}",
            ).assertIsDisplayed()
        save("chat-turn-end")
    }

    /** 运行状态行右边是本轮已经运行的时间，按电脑的时钟算。 */
    @Test
    fun runningLineShowsTheElapsedTime() {
        val clockOffset = 3_600_000L
        val startedAt = System.currentTimeMillis() + clockOffset - 252_000
        render(
            onlineChatState(SessionStatus.RUNNING).copy(
                turns = mapOf(5 to Turn(5, startedAt = startedAt)),
                clockOffsetMillis = clockOffset,
            ),
        )
        val shown =
            compose
                .onNodeWithTag("running-elapsed")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("")
        assertTrue(shown, shown.startsWith(context.getString(R.string.chat_turn_elapsed, "4:1")))
    }

    /** 断线前还在运行：不知道结果，不再转圈，写「结果待确认」。 */
    @Test
    fun runningSessionWithoutLinkIsUnconfirmed() {
        render(onlineChatState(SessionStatus.RUNNING).copy(link = LinkStatus.Offline(OfflineReason.UNREACHABLE, 1_000)))
        compose.onNodeWithTag("unconfirmed-line").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.turn_unconfirmed)).assertIsDisplayed()
        compose.onNodeWithTag("running-line").assertDoesNotExist()
    }
}
