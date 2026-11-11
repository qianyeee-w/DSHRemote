package io.github.qianyeeew.dshremote.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.hosts.HostRow
import io.github.qianyeeew.dshremote.ui.sessions.SessionHomeLayout
import io.github.qianyeeew.dshremote.ui.sessions.SessionListNavigation
import io.github.qianyeeew.dshremote.ui.sessions.SessionListState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeLayoutUiTest {
    @get:Rule val compose = createComposeRule()
    private val online = LinkStatus.Online(HostAddress("192.168.1.12", 3290))

    @Test fun collapsingProjectKeepsWaitingSessionReachable() {
        var opened = ""
        render(onSession = { opened = it })
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("project:p1"))
        compose.onNodeWithTag("project:p1").performClick()
        compose.onNodeWithText("整理重连逻辑").assertDoesNotExist()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("waiting:waiting"))
        compose.onNodeWithTag("waiting:waiting").performClick()
        compose.runOnIdle { assertEquals("waiting", opened) }
        save("home")
    }

    @Test fun hostSelectionAndNewSessionUseTheirOwnCallbacks() {
        var selected = ""
        var created = 0
        render(onHost = { selected = it }, onNew = { created++ })
        compose.onNodeWithTag("host-chip:second").performClick()
        compose.onNodeWithTag("home-new-session").performClick()
        compose.runOnIdle {
            assertEquals("second", selected)
            assertEquals(1, created)
        }
    }

    @Test fun disconnectedHostCannotCreateSessionButRetainsCachedConversations() {
        render(offline = true)
        compose.onNodeWithTag("home-new-session").assertIsNotEnabled()
        compose.onNodeWithTag("waiting:waiting").assertIsDisplayed()
    }

    /** M3 T5：会话标题开头的 Markdown 标记不显示。 */
    @Test fun sessionTitleDropsLeadingMarkdownMarkers() {
        render()
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("解释 Markdown 渲染流程"))
        compose.onNodeWithText("解释 Markdown 渲染流程").assertIsDisplayed()
        compose.onNodeWithText("## 解释 Markdown 渲染流程").assertDoesNotExist()
    }

    private fun render(
        offline: Boolean = false,
        onSession: (String) -> Unit = {},
        onHost: (String) -> Unit = {},
        onNew: () -> Unit = {},
    ) {
        val state =
            SessionListState(
                "开发电脑",
                if (offline) LinkStatus.Stopped else online,
                listOf(
                    SessionSummary("waiting", workspaceId = "p1", title = "确认测试命令", status = SessionStatus.WAITING, updatedAt = 0),
                    SessionSummary("working", workspaceId = "p1", title = "整理重连逻辑", updatedAt = 0),
                    SessionSummary("recent", title = "## 解释 Markdown 渲染流程", updatedAt = 0),
                ),
            )
        compose.setContent {
            DshRemoteTheme {
                SessionHomeLayout(
                    state = state,
                    navigation = SessionListNavigation(null, onSession, {}, {}, {}, {}, {}),
                    workspaces = listOf(Workspace("p1", "DSHRemote", "E:/Project/DSHRemote")),
                    hosts = listOf(HostRow("first", "开发电脑", null, online), HostRow("second", "笔记本", null, LinkStatus.Stopped)),
                    selectedHostId = "first",
                    onSelectHost = onHost,
                    onManageHosts = {},
                    onAddHost = {},
                    onNewSession = onNew,
                    onProjectSession = {},
                    onProject = {},
                    statusContent = {},
                    attentionContent = {},
                    emptyContent = {},
                )
            }
        }
    }

    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "visual-verification").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
