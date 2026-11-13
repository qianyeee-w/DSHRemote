package io.github.qianyeeew.dshremote.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.ModelList
import io.github.qianyeeew.dshremote.data.api.ModelOption
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.PermissionPreset
import io.github.qianyeeew.dshremote.data.api.PermissionPresetList
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.chat.ChatActions
import io.github.qianyeeew.dshremote.ui.chat.ChatComposer
import io.github.qianyeeew.dshremote.ui.chat.ChatState
import io.github.qianyeeew.dshremote.ui.chat.Picker
import io.github.qianyeeew.dshremote.ui.common.INLINE_CHOICES_TAG
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

    /** 没有焦点就收起（设计规格 6.3），写了一半的字留着；收起时发送键还在，发送被拒绝时字不丢。 */
    @Test
    fun draftSurvivesBlurAndRejectedSend() {
        render(ChatActions(onSend = { false }))
        compose.onNodeWithTag("chat-input").performTextInput("继续验证")
        compose.onNodeWithText("移开焦点").performClick()
        compose.onNodeWithTag("chat-composer-options").assertDoesNotExist()
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        compose.onNodeWithTag("chat-input").assertTextContains("继续验证")
    }

    @Test
    fun acceptedSendClearsDraft() {
        val sent = mutableListOf<String>()
        render(
            ChatActions(onSend = { text ->
                sent += text
                true
            }),
        )
        compose.onNodeWithTag("chat-input").performTextInput("运行测试")
        compose.onNodeWithTag("chat-send").performClick()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("运行测试"), sent) }
    }

    /** 发送键的三种样子：没字且空闲不可点；有字发送；没字且在运行时是停止，有字时又变回发送（插话）。 */
    @Test
    fun sendKeyHasThreeStates() {
        var state by mutableStateOf(onlineChatState())
        var stopped = 0
        val sent = mutableListOf<String>()
        compose.setContent {
            DshRemoteTheme {
                ChatComposer(
                    state,
                    ChatActions(onSend = {
                        sent += it
                        true
                    }, onCancelRun = { stopped++ }),
                )
            }
        }
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("chat-stop").assertDoesNotExist()
        compose.runOnIdle { state = onlineChatState(SessionStatus.RUNNING) }
        compose.onNodeWithTag("chat-send").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_stop)).assertIsEnabled()
        compose.onNodeWithTag("chat-stop").performClick()
        compose.onNodeWithTag("chat-input").performTextInput("说快一点")
        compose.onNodeWithTag("chat-stop").assertDoesNotExist()
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, stopped)
            assertEquals(listOf("说快一点"), sent)
        }
    }

    /** 占位文字随场景变化：空闲「继续对话」，运行中「补充一句」，离线时说明不能发。 */
    @Test
    fun placeholderFollowsTheSession() {
        var state by mutableStateOf(onlineChatState())
        compose.setContent { DshRemoteTheme { ChatComposer(state, ChatActions()) } }
        compose.onNodeWithText(context.getString(R.string.chat_input_hint)).assertIsDisplayed()
        compose.runOnIdle { state = onlineChatState(SessionStatus.RUNNING) }
        compose.onNodeWithText(context.getString(R.string.chat_input_hint_running)).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(link = LinkStatus.Stopped) }
        compose.onNodeWithText(context.getString(R.string.chat_input_hint_offline)).assertIsDisplayed()
    }

    /**
     * 选项在输入框里、最下面一行的上方展开（设计规格 6.10）：不另开窗口，输入框一直有焦点（键盘不收起）；
     * 点另一个触发项换成它的选项；选中一项调用对应的操作；点输入的文字、按返回键都会收起，返回键先收起选项。
     */
    @Test
    fun choicesOpenInsideTheComposerAndKeepTheFocus() {
        var picker by mutableStateOf<Picker>(Picker.Closed)
        val chosen = mutableListOf<String>()
        var back: OnBackPressedDispatcher? = null
        val models = ModelList(models = listOf(ModelOption("test", "测试来源", "测试模型", "测试模型"), ModelOption("test", "测试来源", "m2", "第二个模型")))
        val presets = PermissionPresetList(presets = listOf(PermissionPreset("read-only", "Read only"), PermissionPreset("auto", "Auto")))
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            DshRemoteTheme {
                ChatComposer(
                    state = onlineChatState(),
                    picker = picker,
                    actions =
                        ChatActions(
                            onOpenModels = { picker = if (picker is Picker.Models) Picker.Closed else Picker.Models(models) },
                            onOpenPresets = { picker = if (picker is Picker.Presets) Picker.Closed else Picker.Presets(presets) },
                            onChooseModel = {
                                chosen += it.model
                                picker = Picker.Closed
                            },
                            onChoosePreset = {
                                chosen += it
                                picker = Picker.Closed
                            },
                            onClosePicker = { picker = Picker.Closed },
                        ),
                )
            }
        }
        compose.onNodeWithTag("chat-input").performClick()
        compose.onNodeWithTag("chat-input").performTextInput("先看一下")
        compose.onNodeWithTag("chat-model").performClick()
        compose.onNodeWithTag(INLINE_CHOICES_TAG).assertIsDisplayed()
        compose.onNodeWithTag("chat-input").assertIsFocused()
        compose.onNodeWithTag("chat-model:test/测试模型").assertIsSelected()
        // 换成审批方式：已知的三种用中文名和说明，DSH 给的其他方式照常列出
        compose.onNodeWithTag("chat-preset").performClick()
        compose.onNodeWithTag("chat-model:test/m2").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.design_permission_read_only_hint)).assertIsDisplayed()
        compose.onNodeWithText("Auto").assertIsDisplayed()
        compose.onNodeWithTag("chat-input").assertIsFocused()
        compose.onNodeWithTag("chat-preset:auto").performClick()
        compose.onNodeWithTag(INLINE_CHOICES_TAG).assertDoesNotExist()
        compose.onNodeWithTag("chat-input").assertIsFocused().assertTextContains("先看一下")
        // 点输入的文字收起
        compose.onNodeWithTag("chat-model").performClick()
        compose.onNodeWithTag("chat-input").performClick()
        compose.onNodeWithTag(INLINE_CHOICES_TAG).assertDoesNotExist()
        // 返回键先收起选项，输入框还在编辑
        compose.onNodeWithTag("chat-model").performClick()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.onNodeWithTag(INLINE_CHOICES_TAG).assertDoesNotExist()
        compose.onNodeWithTag("chat-input").assertIsFocused()
        compose.onNodeWithTag("chat-composer-options").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("auto"), chosen) }
    }

    private fun text(id: Int) = context.getString(id)

    @Test
    fun offlineStateDisablesInputSendAndPickersWithoutDiscardingDraft() {
        var state by mutableStateOf(onlineChatState())
        compose.setContent { DshRemoteTheme { ChatComposer(state, ChatActions()) } }
        compose.onNodeWithTag("chat-input").performTextInput("保留草稿")
        compose.runOnIdle { state = state.copy(link = LinkStatus.Stopped) }
        compose.onNodeWithTag("chat-input").assertTextContains("保留草稿").assertIsNotEnabled()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose
            .onAllNodesWithContentDescription(context.getString(R.string.design_model_value, "测试模型"))
            .assertAll(isNotEnabled())
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
