package io.github.qianyeeew.dshremote.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PromptMode
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.chat.LocalMessage
import io.github.qianyeeew.dshremote.data.chat.OutgoingStatus
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.chat.ChatActions
import io.github.qianyeeew.dshremote.ui.chat.ChatContent
import io.github.qianyeeew.dshremote.ui.chat.ChatItem
import io.github.qianyeeew.dshremote.ui.chat.ChatState
import io.github.qianyeeew.dshremote.ui.chat.ForkState
import io.github.qianyeeew.dshremote.ui.chat.Picker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 对话分支（设计规格 7.13，落地方案 C1）：长按自己的消息，复制或另开对话。 */
class ForkUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val items =
        listOf(
            ChatItem.Entry("u1", Message("u1", MessageRole.USER, listOf(TextPart("通知里同一个审批会重复出现，帮我查一下")), 0, turn = 1)),
            ChatItem.Entry("a1", Message("a1", MessageRole.ASSISTANT, listOf(TextPart("找到原因了，有两种修法。")), 1, turn = 1)),
            ChatItem.Entry("u2", Message("u2", MessageRole.USER, listOf(TextPart("用第一种，统一成一个键。")), 2, turn = 2)),
            ChatItem.Entry("a2", Message("a2", MessageRole.ASSISTANT, listOf(TextPart("好，两处去重已经统一。")), 3, turn = 2)),
        )
    private var back: OnBackPressedDispatcher? = null

    private fun render(
        state: ChatState,
        fork: () -> ForkState = { ForkState.Idle },
        actions: ChatActions = ChatActions(),
    ) {
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            DshRemoteTheme { ChatContent(state, null, Picker.Closed, actions, fork = fork()) }
        }
    }

    @Test
    fun longPressOffersCopyAndForkAndConfirmSendsTheTurnAndText() {
        val forks = mutableListOf<Pair<Int, String>>()
        var closed = 0
        render(
            onlineChatState().copy(items = items),
            actions =
                ChatActions(onFork = {
                    t,
                    s,
                    ->
                    forks += t to s
                }, onCloseFork = { closed++ }),
        )
        compose.onNodeWithText("用第一种，统一成一个键。").performTouchInput { longClick() }
        compose.onNodeWithTag("message-actions").assertIsDisplayed()
        // 输入框让位给面板
        compose.onNodeWithTag("chat-composer").assertDoesNotExist()
        compose.onNodeWithTag("message-fork").performClick()
        compose.onNodeWithTag("fork-confirm").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.fork_files)).assertIsDisplayed()
        save("fork-confirm")
        compose.onNodeWithTag("fork-confirm-button").performClick()
        compose.runOnIdle { assertEquals(listOf(2 to "用第一种，统一成一个键。"), forks) }
        // 取消：收起，回到输入框
        compose.onNodeWithTag("fork-cancel").performClick()
        compose.onNodeWithTag("fork-confirm").assertDoesNotExist()
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test
    fun copyPutsTheTextOnTheClipboardAndBackCloses() {
        render(onlineChatState().copy(items = items))
        compose.onNodeWithText("用第一种，统一成一个键。").performTouchInput { longClick() }
        compose.onNodeWithTag("message-copy").performClick()
        compose.onNodeWithTag("message-actions").assertDoesNotExist()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(
                "用第一种，统一成一个键。",
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
        }
        compose.onNodeWithText("用第一种，统一成一个键。").performTouchInput { longClick() }
        compose.runOnIdle { back!!.onBackPressed() }
        compose.onNodeWithTag("message-actions").assertDoesNotExist()
    }

    /** 还没进会话的本地消息只能复制；不在线时「另开对话」不能点。 */
    @Test
    fun localMessagesOnlyCopyAndOfflineCannotFork() {
        var state by mutableStateOf(
            onlineChatState().copy(
                items = items + ChatItem.Local("l1", LocalMessage(OutgoingMessage("还在路上", PromptMode.QUEUE), OutgoingStatus.SENDING)),
            ),
        )
        compose.setContent { DshRemoteTheme { ChatContent(state, null, Picker.Closed, ChatActions()) } }
        compose.onNodeWithText("还在路上").performTouchInput { longClick() }
        compose.onNodeWithTag("message-copy").assertIsDisplayed()
        compose.onNodeWithTag("message-fork").assertDoesNotExist()
        // 面板开着时其余内容点了只会收起面板：先收起
        compose.onNodeWithTag("message-copy").performClick()
        compose.runOnIdle { state = state.copy(link = LinkStatus.Stopped) }
        compose.onNodeWithText("用第一种，统一成一个键。").performTouchInput { longClick() }
        compose.onNodeWithTag("message-fork").assertIsNotEnabled()
    }

    /** 失败：说明一句，按钮变成「重试」。 */
    @Test
    fun failureOffersRetry() {
        var fork by mutableStateOf<ForkState>(ForkState.Idle)
        render(onlineChatState().copy(items = items), fork = { fork })
        compose.onNodeWithText("用第一种，统一成一个键。").performTouchInput { longClick() }
        compose.onNodeWithTag("message-fork").performClick()
        compose.runOnIdle { fork = ForkState.Running(2) }
        compose.onNodeWithTag("fork-confirm-button").assertIsNotEnabled().assertTextContains(text(R.string.fork_running))
        compose.runOnIdle { fork = ForkState.Failed(2) }
        compose.onNodeWithTag("fork-failed").assertIsDisplayed()
        compose.onNodeWithTag("fork-confirm-button").assertTextContains(text(R.string.fork_retry))
    }

    private fun text(id: Int) = context.getString(id)

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "visual-verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
