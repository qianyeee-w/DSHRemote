package io.github.qianyeeew.dshremote.ui

import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.LastTurn
import io.github.qianyeeew.dshremote.data.api.ModelList
import io.github.qianyeeew.dshremote.data.api.ModelOption
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.PermissionPreset
import io.github.qianyeeew.dshremote.data.api.PermissionPresetList
import io.github.qianyeeew.dshremote.data.api.Question
import io.github.qianyeeew.dshremote.data.api.QuestionOption
import io.github.qianyeeew.dshremote.data.api.QuestionSet
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.TurnResult
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.attention.AttentionCardActions
import io.github.qianyeeew.dshremote.ui.common.COVER_TAG
import io.github.qianyeeew.dshremote.ui.common.INLINE_CHOICES_TAG
import io.github.qianyeeew.dshremote.ui.hosts.HostRow
import io.github.qianyeeew.dshremote.ui.sessions.CreateError
import io.github.qianyeeew.dshremote.ui.sessions.NewTaskActions
import io.github.qianyeeew.dshremote.ui.sessions.NewTaskState
import io.github.qianyeeew.dshremote.ui.sessions.ProjectMode
import io.github.qianyeeew.dshremote.ui.sessions.ProjectPick
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
    private var back: OnBackPressedDispatcher? = null

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
            SessionSummary(
                "recent",
                workspaceId = "p1",
                title = "## 解释 Markdown 渲染流程",
                updatedAt = 2,
                lastTurn = LastTurn(TurnResult.COMPLETED, 2),
                lastReply = "Markdown 先解析成语法树，再逐块渲染。",
            ),
            SessionSummary(
                "tools",
                workspaceId = "p2",
                title = "清理临时目录",
                updatedAt = 1,
                lastTurn = LastTurn(TurnResult.FAILED, 1, "rate limited"),
            ),
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

    /** 最近列表的第二行是最后的结果（落地方案 B1）：最后一句回复，失败是红字加原因。 */
    @Test fun recentRowsShowTheLastResult() {
        render()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("recent:tools"))
        compose.onNodeWithText("Markdown 先解析成语法树，再逐块渲染。").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.turn_failed_reason, "rate limited")).assertIsDisplayed()
    }

    /** 断线前还在运行的会话：回到最近列表，写「结果待确认」，不再转圈。 */
    @Test fun offlineRunningSessionIsUnconfirmed() {
        render(offline = true)
        compose.onNodeWithText(text(R.string.home_running)).assertDoesNotExist()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("recent:working"))
        compose.onNodeWithText(text(R.string.turn_unconfirmed)).assertIsDisplayed()
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
        // 在这一行下面展开，每个项目右边是它在最近列表里的会话数
        compose.onNodeWithTag("home-filter-panel").assertIsDisplayed()
        compose.onNodeWithTag("home-filter:all").assertIsSelected()
        compose.onNodeWithTag("home-filter:p1").assertTextContains("1")
        compose.onNodeWithTag("home-filter:p2").performClick()
        compose.runOnIdle { assertEquals("p2", chosen) }
        compose.onNodeWithTag("home-filter-panel").assertDoesNotExist()
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

    /** 电脑列表在顶栏下面原地展开，其余内容盖一层底色：点被盖住的内容只是收起，不会打开它（设计规格 6.10）。 */
    @Test fun hostListExpandsInPlaceAndTheRestOnlyCollapsesIt() {
        val opened = mutableListOf<String>()
        render(onSession = { opened += it })
        compose.onNodeWithTag("home-host").performClick()
        compose.onNodeWithTag("home-host-panel").assertIsDisplayed()
        compose.onNodeWithTag("home-host:first").assertIsSelected()
        save("host-panel")
        compose.onAllNodesWithTag(COVER_TAG)[0].performClick()
        compose.onNodeWithTag("home-host-panel").assertDoesNotExist()
        compose.onAllNodesWithTag(COVER_TAG).assertCountEquals(0)
        compose.onNodeWithText("优化通知展示").performClick()
        compose.runOnIdle { assertEquals(listOf("working"), opened) }
    }

    /** 返回键先收起电脑列表。 */
    @Test fun backCollapsesTheHostList() {
        render()
        compose.onNodeWithTag("home-host").performClick()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.onNodeWithTag("home-host-panel").assertDoesNotExist()
    }

    /** 只配对了一台电脑：点电脑名直接进「电脑与设置」，不展开只有一行的列表（设计规格 7.7）。 */
    @Test fun singleComputerOpensSettingsDirectly() {
        var settings = 0
        render(onSettings = { settings++ }, hosts = listOf(HostRow("first", "开发电脑", null, online)))
        compose.onNodeWithTag("home-host-settings", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("home-host").performClick()
        compose.onNodeWithTag("home-host-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, settings) }
    }

    /**
     * 新任务的项目、模型、审批方式在输入框卡片里展开（设计规格 6.10）：输入框一直有焦点，写的字不变；
     * 一次只展开一个；点输入的文字收起；返回键先收起选项，再按一次才收起输入框。
     */
    @Test fun newTaskChoicesOpenInsideTheCardAndKeepTheFocus() {
        val chosen = mutableListOf<String>()
        render(
            newTask =
                NewTaskState(
                    models =
                        ModelList(
                            ModelRef("p", "m1"),
                            listOf(ModelOption("p", "来源", "m1", "模型一"), ModelOption("p", "来源", "m2", "模型二")),
                        ),
                    presets =
                        PermissionPresetList(
                            "workspace-write",
                            listOf(PermissionPreset("read-only", "r"), PermissionPreset("workspace-write", "w")),
                        ),
                ),
            newTaskActions =
                NewTaskActions(
                    onChooseModel = { chosen += it.model },
                    onChoosePreset = { chosen += it },
                ),
        )
        compose.onNodeWithTag("home-new-session").performClick()
        compose.onNodeWithTag("new-task-input").performTextInput("给通知加按钮")
        compose.onNodeWithTag("new-task-preset").performClick()
        compose.onNodeWithTag(INLINE_CHOICES_TAG).assertIsDisplayed()
        compose.onNodeWithTag("new-task-input").assertIsFocused()
        compose.onNodeWithText(text(R.string.design_permission_workspace_hint)).assertIsDisplayed()
        save("new-task-preset")
        // 换成模型：只有一个选项区
        compose.onNodeWithTag("new-task-model").performClick()
        compose.onAllNodesWithTag(INLINE_CHOICES_TAG).assertCountEquals(1)
        compose.onNodeWithText("模型二").performClick()
        compose.onNodeWithTag(INLINE_CHOICES_TAG).assertDoesNotExist()
        compose.onNodeWithTag("new-task-input").assertIsFocused().assertTextContains("给通知加按钮")
        // 项目在「项目」那一行上方展开
        compose.onNodeWithTag("new-task-project").performClick()
        compose.onNodeWithTag("new-task-project:p1").assertIsSelected()
        compose.onNodeWithTag("new-task-input").assertIsFocused()
        save("new-task-project")
        compose.onNodeWithTag("new-task-input").performClick()
        compose.onNodeWithTag(INLINE_CHOICES_TAG).assertDoesNotExist()
        // 返回键：先收起选项，再收起输入框
        compose.onNodeWithTag("new-task-model").performClick()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.onNodeWithTag(INLINE_CHOICES_TAG).assertDoesNotExist()
        compose.onNodeWithTag("new-task").assertIsDisplayed()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.onNodeWithTag("new-task").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("m2"), chosen) }
    }

    /** 有问题的电脑在菜单里带短状态，点了不切换，进它的详情页；最后一行「电脑与设置」（设计规格 7.7）。 */
    @Test fun hostMenuSendsProblemComputersToTheirPage() {
        var switched = ""
        var detail = ""
        var settings = 0
        render(onSwitch = { switched = it }, onHostDetail = { detail = it }, onSettings = { settings++ })
        compose.onNodeWithTag("home-host").performClick()
        compose.onNodeWithText(text(R.string.host_menu_rejected)).assertIsDisplayed()
        compose.onNodeWithTag("home-host:third").performClick()
        compose.onNodeWithTag("home-host").performClick()
        compose.onNodeWithTag("home-computers-settings").performClick()
        compose.runOnIdle {
            assertEquals("", switched)
            assertEquals("third", detail)
            assertEquals(1, settings)
        }
    }

    /** 点底部输入框原地展开；点外面收起，已经写的字保留（设计规格 7.6）。 */
    @Test fun newTaskExpandsInPlaceAndKeepsTheTextWhenCollapsed() {
        var expanded = 0
        render(newTaskActions = NewTaskActions(onExpand = { expanded++ }))
        compose.onNodeWithTag("new-task").assertDoesNotExist()
        compose.onNodeWithTag("home-new-session").performClick()
        compose.onNodeWithTag("new-task").assertIsDisplayed()
        compose.onNodeWithTag("new-task-input").performTextInput("给通知加按钮")
        save("new-task")
        compose.onNodeWithTag("new-task-scrim").performClick()
        compose.onNodeWithTag("new-task").assertDoesNotExist()
        compose.onNodeWithText("给通知加按钮").assertIsDisplayed()
        compose.onNodeWithTag("home-new-session").performClick()
        compose.onNodeWithTag("new-task-input").assertTextContains("给通知加按钮")
        compose.runOnIdle { assertEquals(2, expanded) }
    }

    /** 默认用上一次的项目；可以换项目；发送键带上内容和项目。 */
    @Test fun startUsesTheChosenProject() {
        val started = mutableListOf<Pair<String, ProjectPick>>()
        var project: ProjectPick by mutableStateOf(ProjectPick.Existing(workspaces[0]))
        render(
            newTaskProject = { project },
            newTaskActions =
                NewTaskActions(
                    onChooseProject = { project = it },
                    onStart = { text, pick ->
                        started += text to pick
                        true
                    },
                ),
        )
        compose.onNodeWithTag("home-new-session").performClick()
        compose.onNodeWithTag("new-task-start").assertIsNotEnabled()
        compose.onNodeWithTag("new-task-project").assertTextContains("DSHRemote", substring = true)
        compose.onNodeWithTag("new-task-project").performClick()
        compose.onNodeWithTag("new-task-project:p2").performClick()
        compose.onNodeWithTag("new-task-input").performTextInput("清理脚本")
        compose.onNodeWithTag("new-task-start").performClick()
        compose.runOnIdle { assertEquals(listOf("清理脚本" to ProjectPick.Existing(workspaces[1])), started) }
    }

    /** 项目列表里有「不指定项目」，最后一项「新建或导入项目」进入现有的创建／导入流程。 */
    @Test fun projectListOffersNoProjectAndCreateOrImport() {
        val modes = mutableListOf<ProjectMode>()
        var picked: ProjectPick? = null
        render(onProject = { modes += it }, newTaskActions = NewTaskActions(onChooseProject = { picked = it }))
        compose.onNodeWithTag("home-new-session").performClick()
        compose.onNodeWithTag("new-task-project").performClick()
        compose.onNodeWithTag("new-task-project:none").performClick()
        compose.runOnIdle { assertEquals(ProjectPick.None, picked) }
        compose.onNodeWithTag("new-task-project").performClick()
        compose.onNodeWithTag("new-task-create-or-import").performClick()
        compose.onNodeWithTag("new-task-import").performClick()
        compose.runOnIdle { assertEquals(listOf(ProjectMode.IMPORT), modes) }
    }

    /** 会话没能创建：留在首页，提示原因，写的字还在。 */
    @Test fun createFailureIsShownInTheComposer() {
        render(newTask = NewTaskState(error = CreateError.OFFLINE))
        compose.onNodeWithTag("home-new-session").performClick()
        compose.onNodeWithTag("new-task-error").assertIsDisplayed()
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
        onFilter: (String?) -> Unit = {},
        newTask: NewTaskState = NewTaskState(),
        newTaskProject: () -> ProjectPick = { ProjectPick.Existing(workspaces[0]) },
        newTaskActions: NewTaskActions = NewTaskActions(),
        onProject: (ProjectMode) -> Unit = {},
        onHostDetail: (String) -> Unit = {},
        onSettings: () -> Unit = {},
        hosts: List<HostRow> =
            listOf(
                HostRow("first", "开发电脑", null, online),
                HostRow("second", "笔记本", null, LinkStatus.Stopped),
                HostRow("third", "实验室服务器", null, LinkStatus.FingerprintMismatch),
            ),
    ) {
        val attention = mapOf("waiting" to shell, "asking" to ask)
        val state =
            SessionListState(
                hostName = "开发电脑",
                status = if (offline) LinkStatus.Stopped else online,
                sessions = sessions,
                sections = homeSections(sessions, emptyList(), workspaces, filter, online = !offline) { attention[it.sessionId] },
                workspaces = workspaces,
                hostId = "first",
            )
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            DshRemoteTheme {
                SessionListContent(
                    state = state,
                    newTask = newTask,
                    defaultProject = newTaskProject(),
                    newTaskActions = newTaskActions,
                    onProject = onProject,
                    navigation =
                        SessionListNavigation(
                            onBack = null,
                            onOpenSession = onSession,
                            onOpenAttention = {},
                            onManageHost = {},
                            onAddHost = {},
                            onRepair = {},
                            onRetry = {},
                            onOpenSettings = onSettings,
                            hosts = hosts,
                            onSwitchHost = onSwitch,
                            onOpenHostDetail = onHostDetail,
                        ),
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
