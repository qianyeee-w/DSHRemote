package io.github.qianyeeew.dshremote.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.chat.ChatActions
import io.github.qianyeeew.dshremote.ui.chat.ChatComposer
import io.github.qianyeeew.dshremote.ui.chat.ChatState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatComposerUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun emptyComposerExpandsOnFocusAndCollapsesWhenFocusLeaves() {
        render()
        compose.onNodeWithTag("chat-composer-options").assertDoesNotExist()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("chat-input").performClick()
        compose.onNodeWithTag("chat-composer-options").assertIsDisplayed()
        compose.onNodeWithText("移开焦点").performClick()
        compose.onNodeWithTag("chat-composer-options").assertDoesNotExist()
    }

    @Test
    fun draftSurvivesBlurAndRejectedSend() {
        render(ChatActions(onSend = { _, _ -> false }))
        compose.onNodeWithTag("chat-input").performTextInput("继续验证")
        compose.onNodeWithText("移开焦点").performClick()
        compose.onNodeWithTag("chat-composer-options").assertIsDisplayed()
        compose.onNodeWithTag("chat-send").performClick()
        compose.onNodeWithTag("chat-input").assertTextContains("继续验证")
    }

    @Test
    fun acceptedSendClearsDraftAndForwardsSteeringMode() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        render(
            ChatActions(onSend = { text, steer ->
                sent += text to steer
                true
            }),
        )
        compose.onNodeWithTag("chat-input").performTextInput("运行测试")
        compose.onNodeWithTag("chat-send").performClick()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("运行测试" to true), sent) }
    }

    @Test
    fun pickerRemainsVisibleAfterInputLosesFocusAndCallsCorrectAction() {
        var opened by mutableStateOf(false)
        var models = 0
        var permissions = 0
        compose.setContent {
            val focus = LocalFocusManager.current
            DshRemoteTheme {
                ChatComposer(
                    state = onlineChatState(),
                    pickerOpen = opened,
                    actions =
                        ChatActions(
                            onOpenModels = {
                                models++
                                opened = true
                                focus.clearFocus()
                            },
                            onOpenPresets = {
                                permissions++
                                opened = true
                                focus.clearFocus()
                            },
                        ),
                )
            }
        }
        compose.onNodeWithTag("chat-input").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.design_model_value, "测试模型")).performClick()
        compose.onNodeWithTag("chat-composer-options").assertIsDisplayed()
        compose
            .onNodeWithContentDescription(
                context.getString(R.string.design_permission_value, context.getString(R.string.design_default_permission)),
            ).performClick()
        compose.runOnIdle {
            assertEquals(1, models)
            assertEquals(1, permissions)
        }
    }

    @Test
    fun offlineStateDisablesInputSendAndPickersWithoutDiscardingDraft() {
        var state by mutableStateOf(onlineChatState())
        compose.setContent { DshRemoteTheme { ChatComposer(state, ChatActions()) } }
        compose.onNodeWithTag("chat-input").performTextInput("保留草稿")
        compose.runOnIdle { state = state.copy(link = LinkStatus.Stopped) }
        compose.onNodeWithTag("chat-input").assertTextContains("保留草稿").assertIsNotEnabled()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.design_model_value, "测试模型")).assertIsNotEnabled()
    }

    @Test
    fun stopRemainsAvailableWhileComposerIsCollapsed() {
        var stopped = 0
        render(ChatActions(onCancelRun = { stopped++ }), onlineChatState(SessionStatus.RUNNING))
        compose.onNodeWithTag("chat-composer-options").assertDoesNotExist()
        compose.onNodeWithTag("chat-stop").performClick()
        compose.runOnIdle { assertEquals(1, stopped) }
    }

    private fun render(
        actions: ChatActions = ChatActions(),
        state: ChatState = onlineChatState(),
    ) {
        compose.setContent {
            val focus = LocalFocusManager.current
            DshRemoteTheme {
                Column {
                    TextButton(onClick = { focus.clearFocus() }) { Text("移开焦点") }
                    ChatComposer(state, actions)
                }
            }
        }
    }
}

internal fun onlineChatState(status: SessionStatus = SessionStatus.IDLE): ChatState =
    ChatState(
        session = SessionSummary("visual-test", title = "检查连接恢复逻辑", status = status, model = ModelRef("test", "测试模型"), updatedAt = 0),
        link = LinkStatus.Online(HostAddress("127.0.0.1", 3290)),
        items = emptyList(),
        loaded = true,
        hasMore = false,
        loadingOlder = false,
        loadFailed = false,
    )
