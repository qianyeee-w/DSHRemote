package io.github.qianyeeew.dshremote.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.ui.chat.ChatActions
import io.github.qianyeeew.dshremote.ui.chat.ChatContent
import io.github.qianyeeew.dshremote.ui.chat.ChatItem
import io.github.qianyeeew.dshremote.ui.chat.Picker
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 截图来自真机 Compose 页面，示例内容只存在测试中。 */
@RunWith(AndroidJUnit4::class)
class VisualLayoutUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun markdownAndComposerRemainReadableWithKeyboard() {
        renderChat()
        compose.onNodeWithTag("chat-input").assertIsDisplayed()
        save("chat-reading")
        compose.onNodeWithTag("chat-input").performClick().performTextInput("请补充重连失败的处理方式")
        compose.onNodeWithTag("chat-composer-options").assertIsDisplayed()
        compose.onNodeWithTag("chat-send").assertIsDisplayed()
        save("chat-writing")
    }

    @Test
    fun darkLargeTextKeepsApprovalAndInputReachable() {
        renderChat(dark = true, fontScale = 1.3f, approval = true)
        // 审批在等时输入框不能写（DSH 停着等审批），只留停止
        compose.onNodeWithTag("chat-input").assertIsNotEnabled()
        compose.onNodeWithTag("chat-stop").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.attention_allow)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chat-input").assertIsDisplayed()
        save("chat-dark-large-approval")
    }

    private fun renderChat(
        dark: Boolean = false,
        fontScale: Float = 1f,
        approval: Boolean = false,
    ) {
        val markdown =
            """
            已检查连接恢复逻辑，建议保留以下三个步骤：

            ### 连接恢复
            1. 检查电脑是否在线。
            2. 使用有限次数的退避重试。
            3. 保留尚未发送的草稿。

            ```kotlin
            suspend fun reconnect() {
                repeat(3) { attempt ->
                    if (connect()) return
                    delay(1000L * (attempt + 1))
                }
            }
            ```

            请求标识保持不变，可以避免重复发送。`requestId` 只在首次发送时生成。
            """.trimIndent()
        val state =
            onlineChatState(if (approval) SessionStatus.WAITING else SessionStatus.IDLE).copy(
                items =
                    listOf(
                        ChatItem.Entry("user", Message("user", MessageRole.USER, listOf(TextPart("检查连接恢复逻辑")), 0)),
                        ChatItem.Entry("assistant", Message("assistant", MessageRole.ASSISTANT, listOf(TextPart(markdown)), 1)),
                    ),
                attention =
                    if (approval) {
                        listOf(
                            Attention(
                                "approval",
                                "visual-test",
                                AttentionKind.APPROVAL,
                                approval = Approval("bash", "验证变更", "运行命令：npm test"),
                                createdAt = 0,
                            ),
                        )
                    } else {
                        emptyList()
                    },
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                DshRemoteTheme(dark = dark) { ChatContent(state, null, Picker.Closed, ChatActions()) }
            }
        }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "visual-verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
