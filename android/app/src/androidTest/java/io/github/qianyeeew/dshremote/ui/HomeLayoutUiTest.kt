package io.github.qianyeeew.dshremote.ui

import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
import io.github.qianyeeew.dshremote.ui.common.KEYBOARD_PANEL_TAG
import io.github.qianyeeew.dshremote.ui.hosts.HostRow
import io.github.qianyeeew.dshremote.ui.sessions.CreateError
import io.github.qianyeeew.dshremote.ui.sessions.HomeGrouping
import io.github.qianyeeew.dshremote.ui.sessions.NewTaskActions
import io.github.qianyeeew.dshremote.ui.sessions.NewTaskState
import io.github.qianyeeew.dshremote.ui.sessions.ProjectMode
import io.github.qianyeeew.dshremote.ui.sessions.ProjectPick
import io.github.qianyeeew.dshremote.ui.sessions.ReconnectLine
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

/** 首页（设计规格 7.1）：进行中卡片、按项目分组的最近列表、「⋯」菜单、电脑切换、新任务。 */
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

    /** 右上角「⋯」：分组方式（项目、日期、状态），当前的一项带勾。 */
    @Test fun groupingIsChosenFromTheMoreMenu() {
        var chosen: HomeGrouping? = null
        render(onGrouping = { chosen = it })
        compose.onNodeWithTag("home-more").performClick()
        compose.onNodeWithTag("home-more-menu").assertIsDisplayed()
        compose.onNodeWithTag("home-group:PROJECT").assertIsSelected()
        save("home-more-menu")
        compose.onNodeWithTag("home-group:DATE").performClick()
        compose.runOnIdle { assertEquals(HomeGrouping.DATE, chosen) }
        compose.onNodeWithTag("home-more-menu").assertDoesNotExist()
    }

    /** 默认按项目分组：组名就是项目，行里不再写项目名；点组名收起这一组。 */
    @Test fun recentIsGroupedByProjectAndGroupsFold() {
        render()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("recent:tools"))
        compose.onNodeWithTag("group:project:p2").assertIsDisplayed()
        compose.onNodeWithText("工具脚本").performClick()
        compose.onNodeWithTag("recent:tools").assertDoesNotExist()
        compose.onNodeWithText("工具脚本").performClick()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("recent:tools"))
    }

    /** 按日期分组时，行里写项目名。 */
    @Test fun otherGroupingsShowTheProjectInTheRow() {
        render(grouping = HomeGrouping.DATE)
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("recent:tools"))
        compose.onNodeWithText("工具脚本").assertIsDisplayed()
        compose.onNodeWithTag("group:project:p2").assertDoesNotExist()
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
     * 新任务的模型、审批方式在输入框下面：点了打开选项面板（只列这一种），写的字不变；
     * 点输入框面板让位；返回键先收起面板，再按一次才收起输入框。
     */
    @Test fun newTaskChoicesOpenInThePanelBelow() {
        val chosen = mutableListOf<String>()
        render(
            newTask = {
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
                )
            },
            newTaskActions =
                NewTaskActions(
                    onChooseModel = { chosen += it.model },
                    onChoosePreset = { chosen += it },
                ),
        )
        compose.onNodeWithTag("home-new-session").performClick()
        compose.onNodeWithTag("new-task-input").performTextInput("给通知加按钮")
        compose.onNodeWithTag("new-task-preset").performClick()
        compose.onNodeWithTag(KEYBOARD_PANEL_TAG).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.design_permission_workspace_hint)).assertIsDisplayed()
        // 只列审批方式
        compose.onNodeWithText("模型二").assertDoesNotExist()
        save("new-task-preset")
        // 换成模型：还是同一块面板
        compose.onNodeWithTag("new-task-model").performClick()
        compose.onAllNodesWithTag(KEYBOARD_PANEL_TAG).assertCountEquals(1)
        compose.onNodeWithText("模型二").performClick()
        compose.onNodeWithTag(KEYBOARD_PANEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag("new-task-input").assertTextContains("给通知加按钮")
        // 点输入框：面板让位
        compose.onNodeWithTag("new-task-model").performClick()
        compose.onNodeWithTag("new-task-input").performClick()
        compose.onNodeWithTag(KEYBOARD_PANEL_TAG).assertDoesNotExist()
        // 返回键：先收起面板，再收起输入框
        compose.onNodeWithTag("new-task-model").performClick()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.onNodeWithTag(KEYBOARD_PANEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag("new-task").assertIsDisplayed()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.onNodeWithTag("new-task").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("m2"), chosen) }
    }

    /** 收起的输入框下面也有模型和审批方式：点了直接打开面板，不先进入输入。 */
    @Test fun optionsUnderTheCollapsedBarOpenThePanel() {
        render(
            newTask = {
                NewTaskState(
                    presets = PermissionPresetList("read-only", listOf(PermissionPreset("read-only", "r"))),
                    models = ModelList(ModelRef("p", "m1"), listOf(ModelOption("p", "来源", "m1", "模型一"))),
                )
            },
        )
        compose.onNodeWithTag("new-task").assertDoesNotExist()
        compose.onNodeWithTag("new-task-preset").performClick()
        compose.onNodeWithTag("new-task").assertIsDisplayed()
        compose.onNodeWithTag(KEYBOARD_PANEL_TAG).assertIsDisplayed()
        // 没点输入框：占位文字还在
        compose.onNodeWithTag("new-task-hint").assertIsDisplayed()
    }

    /**
     * 落地方案 B2：没看过的结果标题加粗、标为「未读」，失败的排在最前；「N 条未读」点了只看未读。
     */
    @Test fun unreadResultsAreMarkedAndCanBeShownAlone() {
        var toggled = 0
        render(unread = setOf("tools"), onToggleUnread = { toggled++ })
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-unread"))
        compose.onNodeWithTag("home-unread").assertTextContains(context.getString(R.string.home_unread_count, 1)).performClick()
        compose.runOnIdle { assertEquals(1, toggled) }
        compose
            .onNode(
                hasAnyAncestor(hasTestTag("recent:tools")) and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.recent_unread)),
            ).assertExists()
        compose
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.recent_unread)))
            .assertCountEquals(1)
        save("unread")
    }

    @Test fun unreadOnlyShowsJustTheUnreadSessions() {
        render(unread = setOf("tools"), unreadOnly = true)
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("recent:tools"))
        compose.onNodeWithTag("recent:recent").assertDoesNotExist()
        compose.onNodeWithTag("home-unread").assertIsOn()
    }

    /** 落地方案 B3：有没发出去的草稿时，最近列表第二行是「草稿：…」。 */
    @Test fun draftShowsInTheRecentRow() {
        render(drafts = mapOf("tools" to "再清一遍日志"))
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("recent:tools"))
        compose
            .onNodeWithTag(
                "recent-line:tools",
                useUnmergedTree = true,
            ).assertTextEquals(context.getString(R.string.recent_draft, "再清一遍日志"))
    }

    /** 重连提示在卡片下面；用户滑动列表后消失（设计规格 7.1）。 */
    @Test fun reconnectNoticeShowsAndScrollingDismissesIt() {
        var dismissed = 0
        render(reconnect = ReconnectLine(offlineMillis = 2 * 3_600_000L, results = 2), onDismissReconnect = { dismissed++ })
        compose.onNodeWithTag("home-reconnect").assertTextContains(context.getString(R.string.home_reconnected, "2 小时", 2))
        save("reconnect")
        compose.runOnIdle { assertEquals(0, dismissed) }
        compose.onNodeWithTag("home-list").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(1, dismissed) }
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

    /**
     * 点项目组名右边的加号：输入框提示「在 <项目> 工作区新建任务」；点输入框提示消失，写好后建在这个项目里。
     * 没点加号时用上一次的项目；返回键取消这次的项目。
     */
    @Test fun plusOnAProjectGroupTargetsThatProject() {
        val started = mutableListOf<Pair<String, ProjectPick>>()
        var task by mutableStateOf(NewTaskState())
        render(
            newTask = { task },
            newTaskActions =
                NewTaskActions(
                    onChooseProject = { task = task.copy(project = it) },
                    onClearProject = { task = task.copy(project = null) },
                    onStart = { text, pick ->
                        started += text to pick
                        true
                    },
                ),
        )
        compose.onNodeWithTag("home-new-session-text").assertTextEquals(text(R.string.home_new_task))
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("group:project:p2"))
        compose.onNodeWithTag("group-new-task:project:p2").performClick()
        compose.onNodeWithTag("home-new-session-text").assertTextEquals(context.getString(R.string.new_task_in_project, "工具脚本"))
        // 返回键取消，再点一次加号
        compose.runOnIdle { back!!.onBackPressed() }
        compose.onNodeWithTag("home-new-session-text").assertTextEquals(text(R.string.home_new_task))
        compose.onNodeWithTag("group-new-task:project:p2").performClick()
        save("new-task-target")
        compose.onNodeWithTag("home-new-session").performClick()
        compose.onNodeWithTag("new-task-input").performClick()
        compose.onNodeWithTag("new-task-hint").assertDoesNotExist()
        compose.onNodeWithTag("new-task-start").assertIsNotEnabled()
        compose.onNodeWithTag("new-task-input").performTextInput("清理脚本")
        compose.onNodeWithTag("new-task-start").performClick()
        compose.runOnIdle { assertEquals(listOf("清理脚本" to ProjectPick.Existing(workspaces[1])), started) }
    }

    /** 没点加号：用上一次的项目。 */
    @Test fun startWithoutPlusUsesTheLastProject() {
        val started = mutableListOf<ProjectPick>()
        render(
            newTaskActions =
                NewTaskActions(
                    onStart = { _, pick ->
                        started += pick
                        true
                    },
                ),
        )
        compose.onNodeWithTag("home-new-session").performClick()
        compose.onNodeWithTag("new-task-input").performTextInput("看一下日志")
        compose.onNodeWithTag("new-task-start").performClick()
        compose.runOnIdle { assertEquals(listOf<ProjectPick>(ProjectPick.Existing(workspaces[0])), started) }
    }

    /** 「⋯」里的新建项目和导入已有项目进入现有的创建／导入流程。 */
    @Test fun moreMenuOffersCreateAndImport() {
        val modes = mutableListOf<ProjectMode>()
        render(onProject = { modes += it })
        compose.onNodeWithTag("home-more").performClick()
        compose.onNodeWithTag("home-project-import").performClick()
        compose.onNodeWithTag("home-more").performClick()
        compose.onNodeWithTag("home-project-create").performClick()
        compose.runOnIdle { assertEquals(listOf(ProjectMode.IMPORT, ProjectMode.CREATE), modes) }
    }

    /** 会话没能创建：留在首页，提示原因，写的字还在。 */
    @Test fun createFailureIsShownInTheComposer() {
        render(newTask = { NewTaskState(error = CreateError.OFFLINE) })
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
        grouping: HomeGrouping = HomeGrouping.PROJECT,
        actions: AttentionCardActions = AttentionCardActions(),
        onSession: (String) -> Unit = {},
        onSwitch: (String) -> Unit = {},
        onGrouping: (HomeGrouping) -> Unit = {},
        newTask: () -> NewTaskState = { NewTaskState() },
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
        unread: Set<String> = emptySet(),
        unreadOnly: Boolean = false,
        reconnect: ReconnectLine? = null,
        onToggleUnread: () -> Unit = {},
        onDismissReconnect: () -> Unit = {},
        drafts: Map<String, String> = emptyMap(),
    ) {
        val attention = mapOf("waiting" to shell, "asking" to ask)
        val state =
            SessionListState(
                hostName = "开发电脑",
                status = if (offline) LinkStatus.Stopped else online,
                sessions = sessions,
                sections =
                    homeSections(sessions, emptyList(), online = !offline, unread = unread, unreadOnly = unreadOnly) {
                        attention[it.sessionId]
                    },
                workspaces = workspaces,
                grouping = grouping,
                hostId = "first",
                reconnect = reconnect,
                drafts = drafts,
            )
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            DshRemoteTheme {
                SessionListContent(
                    state = state,
                    newTask = newTask(),
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
                    onGrouping = onGrouping,
                    onToggleUnread = onToggleUnread,
                    onDismissReconnect = onDismissReconnect,
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
