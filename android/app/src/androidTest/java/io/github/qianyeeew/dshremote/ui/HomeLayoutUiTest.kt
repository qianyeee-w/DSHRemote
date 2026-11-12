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
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.Question
import io.github.qianyeeew.dshremote.data.api.QuestionOption
import io.github.qianyeeew.dshremote.data.api.QuestionSet
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.attention.AttentionCardActions
import io.github.qianyeeew.dshremote.ui.hosts.HostRow
import io.github.qianyeeew.dshremote.ui.sessions.NewSession
import io.github.qianyeeew.dshremote.ui.sessions.SessionListContent
import io.github.qianyeeew.dshremote.ui.sessions.SessionListNavigation
import io.github.qianyeeew.dshremote.ui.sessions.SessionListState
import io.github.qianyeeew.dshremote.ui.sessions.homeSections
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 首页（设计规格 7.1）：进行中卡片、最近列表和项目筛选、电脑切换、新任务。 */
class HomeLayoutUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val online = LinkStatus.Online(HostAddress("192.168.1.12", 3290))

    private val shell =
        Attention(
            "a-shell",
            "waiting",
            AttentionKind.APPROVAL,
            approval = Approval("bash", "r", input = JsonObject(mapOf("command" to JsonPrimitive("./gradlew test")))),
            createdAt = 0,
        )
    private val ask =
        Attention(
            "a-ask",
            "asking",
            AttentionKind.QUESTION,
            question =
                QuestionSet(
                    listOf(
                        Question(
                            "q1",
                            "要保留旧笔记原来的时间戳吗？",
                            options = listOf(QuestionOption("保留原时间"), QuestionOption("用导入时间"), QuestionOption("先不导入")),
                        ),
                    ),
                ),
            createdAt = 0,
        )
    private val sessions =
        listOf(
            SessionSummary("waiting", workspaceId = "p1", title = "检查连接恢复逻辑", status = SessionStatus.WAITING, updatedAt = 5),
            SessionSummary("asking", workspaceId = "p1", title = "导入旧笔记", status = SessionStatus.WAITING, updatedAt = 4),
            SessionSummary("working", workspaceId = "p1", title = "优化通知展示", status = SessionStatus.RUNNING, updatedAt = 3),
            SessionSummary("recent", workspaceId = "p1", title = "## 解释 Markdown 渲染流程", updatedAt = 2),
            SessionSummary("tools", workspaceId = "p2", title = "清理临时目录", updatedAt = 1),
        )
    private val workspaces = listOf(Workspace("p1", "DSHRemote", "E:/Project/DSHRemote"), Workspace("p2", "工具脚本", "E:/tools"))

    @Test fun cardShowsWaitingAndRunningAndRecentListsTheRest() {
        render()
        compose.onNodeWithText(text(R.string.home_wants_run)).assertIsDisplayed()
        compose.onNodeWithText("./gradlew test").assertIsDisplayed()
        compose.onNodeWithText("要保留旧笔记原来的时间戳吗？").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.home_running)).assertIsDisplayed()
        // 进行中的会话不在最近列表里重复出现
        compose.onNodeWithTag("recent:working").assertDoesNotExist()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("recent:tools"))
        compose.onNodeWithText("工具脚本").assertIsDisplayed()
        // M3 T5：会话标题开头的 Markdown 标记不显示
        compose.onNodeWithText("解释 Markdown 渲染流程").assertIsDisplayed()
        compose.onNodeWithText("## 解释 Markdown 渲染流程").assertDoesNotExist()
        save("home")
    }

    /** 卡片里直接允许和拒绝，复用会话页的提交逻辑。 */
    @Test fun approvalCanBeAnsweredFromTheCard() {
        val decisions = mutableListOf<Boolean>()
        render(actions = AttentionCardActions(onDecide = { _, allow -> decisions += allow }))
        compose.onNodeWithText(text(R.string.attention_reject)).performClick()
        compose.onNodeWithText(text(R.string.attention_allow)).performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), decisions) }
    }

    /** 单选提问在卡片里给前两个选项和「其他」：点选项直接回答，点「其他」进会话。 */
    @Test fun questionOptionsAnswerDirectlyAndOtherOpensTheSession() {
        val picked = mutableListOf<String>()
        var opened = ""
        render(actions = AttentionCardActions(onAnswerOption = { _, _, label -> picked += label }), onSession = { opened = it })
        compose.onNodeWithText("先不导入").assertDoesNotExist()
        compose.onNodeWithText("用导入时间").performClick()
        compose.onNodeWithText(text(R.string.home_other_answer)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("用导入时间"), picked)
            assertEquals("asking", opened)
        }
        compose.onNodeWithText("优化通知展示").performClick()
        compose.runOnIdle { assertEquals("working", opened) }
    }

    @Test fun projectFilterIsChosenFromTheRecentHeading() {
        var chosen: String? = "unset"
        render(onFilter = { chosen = it })
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-filter"))
        compose.onNodeWithText(text(R.string.home_all_projects)).performClick()
        compose.onNodeWithTag("home-filter:p2").performClick()
        compose.runOnIdle { assertEquals("p2", chosen) }
    }

    @Test fun filteredRecentListHidesOtherProjectsAndTheProjectName() {
        render(filter = "p2")
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("recent:tools"))
        compose.onNodeWithTag("recent:recent").assertDoesNotExist()
        compose.onNodeWithText("工具脚本").assertIsDisplayed() // 只在筛选按钮上
    }

    /** 多台电脑时从左上角的菜单切换（用户确认：首页直接显示上次用的那台）。 */
    @Test fun hostMenuSwitchesComputer() {
        var switched = ""
        render(onSwitch = { switched = it })
        compose.onNodeWithTag("home-host").performClick()
        compose.onNodeWithTag("home-host:second").performClick()
        compose.runOnIdle { assertEquals("second", switched) }
    }

    @Test fun newTaskOpensTheNewSessionFlow() {
        var created = 0
        render(onNew = { created++ })
        compose.onNodeWithTag("home-new-session").performClick()
        compose.runOnIdle { assertEquals(1, created) }
    }

    @Test fun disconnectedHostCannotCreateSessionButRetainsCachedConversations() {
        render(offline = true)
        compose.onNodeWithTag("home-new-session").assertIsNotEnabled()
        compose.onNodeWithTag("home-status").assertIsDisplayed()
        compose.onNodeWithText("检查连接恢复逻辑").assertIsDisplayed()
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("清理临时目录"))
    }

    private fun render(
        offline: Boolean = false,
        filter: String? = null,
        actions: AttentionCardActions = AttentionCardActions(),
        onSession: (String) -> Unit = {},
        onSwitch: (String) -> Unit = {},
        onNew: () -> Unit = {},
        onFilter: (String?) -> Unit = {},
    ) {
        val attention = mapOf("waiting" to shell, "asking" to ask)
        val state =
            SessionListState(
                hostName = "开发电脑",
                status = if (offline) LinkStatus.Stopped else online,
                sessions = sessions,
                sections = homeSections(sessions, emptyList(), workspaces, filter) { attention[it.sessionId] },
                workspaces = workspaces,
                hostId = "first",
            )
        compose.setContent {
            DshRemoteTheme {
                SessionListContent(
                    state = state,
                    newSession = NewSession.Closed,
                    navigation =
                        SessionListNavigation(
                            onBack = null,
                            onOpenSession = onSession,
                            onOpenAttention = {},
                            onManageHost = {},
                            onAddHost = {},
                            onRepair = {},
                            onRetry = {},
                            hosts = listOf(HostRow("first", "开发电脑", null, online), HostRow("second", "笔记本", null, LinkStatus.Stopped)),
                            onSwitchHost = onSwitch,
                        ),
                    onNewSession = onNew,
                    onChooseWorkspace = {},
                    onDismissNewSession = {},
                    attentionActions = actions,
                    onFilter = onFilter,
                )
            }
        }
    }

    private fun text(id: Int) = context.getString(id)

    private fun save(name: String) {
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "visual-verification").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
