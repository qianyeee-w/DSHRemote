package io.github.qianyeeew.dshremote.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.CodeTextStyles
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.attention.ApprovalAction
import io.github.qianyeeew.dshremote.ui.attention.AttentionCardActions
import io.github.qianyeeew.dshremote.ui.attention.AttentionError
import io.github.qianyeeew.dshremote.ui.attention.AttentionUi
import io.github.qianyeeew.dshremote.ui.attention.Resolution
import io.github.qianyeeew.dshremote.ui.attention.approvalAction
import io.github.qianyeeew.dshremote.ui.attention.approvalTarget
import io.github.qianyeeew.dshremote.ui.common.DesignDivider
import io.github.qianyeeew.dshremote.ui.common.DesignPopup
import io.github.qianyeeew.dshremote.ui.common.PillButton
import io.github.qianyeeew.dshremote.ui.common.PillPair
import io.github.qianyeeew.dshremote.ui.common.PillSize
import io.github.qianyeeew.dshremote.ui.common.StatusMark
import io.github.qianyeeew.dshremote.ui.common.StatusMarker
import io.github.qianyeeew.dshremote.ui.common.designPanel
import io.github.qianyeeew.dshremote.ui.common.relativeTime
import io.github.qianyeeew.dshremote.ui.common.serverNow
import io.github.qianyeeew.dshremote.ui.common.sessionTitleText
import io.github.qianyeeew.dshremote.ui.hosts.HostRow
import io.github.qianyeeew.dshremote.ui.hosts.PairMethodDialog
import io.github.qianyeeew.dshremote.ui.hosts.color
import io.github.qianyeeew.dshremote.ui.hosts.menuStatus
import io.github.qianyeeew.dshremote.ui.hosts.needsAttention
import io.github.qianyeeew.dshremote.ui.hosts.offersRepair
import io.github.qianyeeew.dshremote.ui.hosts.offersRetry
import io.github.qianyeeew.dshremote.ui.hosts.statusDetail

/** 页面上的导航操作。 */
class SessionListNavigation(
    /** 不为 null 时显示返回按钮（从别的页面进入一台电脑的会话列表）。首页没有返回。 */
    val onBack: (() -> Unit)?,
    val onOpenSession: (sessionId: String) -> Unit,
    val onOpenAttention: (attentionId: String) -> Unit,
    val onManageHost: () -> Unit,
    /** 不为 null 时菜单中有「添加电脑」（首页）。 */
    val onAddHost: ((manual: Boolean) -> Unit)?,
    val onRepair: (manual: Boolean) -> Unit,
    val onRetry: () -> Unit,
    val onOpenSettings: () -> Unit = {},
    /** 列表最上方的提示（例如后台运行的引导）。 */
    val hint: (@Composable () -> Unit)? = null,
    /** 已配对的电脑，左上角菜单里切换用。 */
    val hosts: List<HostRow> = emptyList(),
    val onSwitchHost: (hostId: String) -> Unit = {},
    /** 电脑菜单里点了有问题的电脑：进它的详情页（设计规格 7.7）。 */
    val onOpenHostDetail: (hostId: String) -> Unit = {},
    /** 新任务已经开始（会话已创建）：把没完成的步骤交给会话页，然后进入会话。 */
    val onTaskStarted: (StartedTask) -> Unit = { onOpenSession(it.sessionId) },
)

@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    navigation: SessionListNavigation,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val newTask by viewModel.newTask.state.collectAsStateWithLifecycle()
    val defaultProject by viewModel.defaultProject.collectAsStateWithLifecycle()
    val project by viewModel.projects.state.collectAsStateWithLifecycle()
    val attentionNotice by viewModel.attention.notice.collectAsStateWithLifecycle()
    // 项目名来自电脑上登记的项目：页面可见并且在线时刷新一次
    val online = state?.status is LinkStatus.Online
    LifecycleStartEffect(online) {
        if (online) viewModel.catalog.refresh()
        onStopOrDispose {}
    }
    val current = state ?: return
    // 卡片上显示过的事项在别处被处理时，提示「已在其他地方处理」。放在界面里做：视图模型的状态没人看时就停
    LaunchedEffect(current.sections.active) {
        viewModel.attention.shown(current.sections.active.mapNotNull(::attentionIdOf))
    }
    SessionListContent(
        state = current,
        navigation = navigation,
        newTask = newTask,
        defaultProject = defaultProject,
        newTaskActions =
            NewTaskActions(
                onExpand = {
                    viewModel.newTask.loadOptions()
                    viewModel.catalog.refresh()
                },
                onChooseProject = viewModel.newTask::chooseProject,
                onChooseModel = viewModel.newTask::chooseModel,
                onChoosePreset = viewModel.newTask::choosePreset,
                onStart = viewModel.newTask::start,
                onStarted = {
                    navigation.onTaskStarted(it)
                    viewModel.newTask.consumeStarted()
                },
            ),
        onProject = viewModel.projects::open,
        attentionActions = AttentionCardActions.of(viewModel.attention),
        onFilter = viewModel::chooseProjectFilter,
        attentionNotice = attentionNotice,
        onDismissAttentionNotice = viewModel.attention::dismissNotice,
    )
    project?.let { selection ->
        ProjectPickerDialog(
            state = selection,
            hostName = current.hostName,
            onBrowse = viewModel.projects::browse,
            onName = viewModel.projects::name,
            onSubmit = viewModel.projects::submit,
            onDismiss = viewModel.projects::dismiss,
            onStartSession = { selection.workspace?.let(viewModel::useProject) },
        )
    }
}

/**
 * 首页（设计规格 7.1）：顶栏是电脑名；进行中卡片（等你处理的在前，然后是运行中的）；「最近」一行和项目筛选；
 * 最近的会话；底部收起的输入框「新任务」。页面不加大标题，没有搜索图标（搜索还没做）。
 */
@Composable
fun SessionListContent(
    state: SessionListState,
    navigation: SessionListNavigation,
    newTask: NewTaskState = NewTaskState(),
    defaultProject: ProjectPick = ProjectPick.None,
    newTaskActions: NewTaskActions = NewTaskActions(),
    onProject: (ProjectMode) -> Unit = {},
    attentionActions: AttentionCardActions = AttentionCardActions(),
    onFilter: (String?) -> Unit = {},
    attentionNotice: Resolution? = null,
    onDismissAttentionNotice: () -> Unit = {},
) {
    var choosingRepair by remember { mutableStateOf(false) }
    var hostMenu by remember { mutableStateOf(false) }
    // 新任务：写过的字在收起后保留（设计规格 7.6）
    var taskText by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(newTask.started) {
        newTask.started?.let {
            taskText = ""
            expanded = false
            newTaskActions.onStarted(it)
        }
    }
    BackHandler(enabled = expanded) { expanded = false }
    val online = state.status is LinkStatus.Online
    val colors = DesignTheme.colors
    val snackbar = remember { SnackbarHostState() }
    val elsewhere = stringResource(R.string.attention_elsewhere)
    LaunchedEffect(attentionNotice) {
        if (attentionNotice == Resolution.ELSEWHERE) {
            snackbar.showSnackbar(elsewhere)
            onDismissAttentionNotice()
        }
    }
    Scaffold(
        topBar = { HomeTopBar(state, navigation, menu = hostMenu, onMenu = { hostMenu = it }) },
        bottomBar = {
            if (!expanded) {
                NewTaskBar(taskText, enabled = online) {
                    expanded = true
                    newTaskActions.onExpand()
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = colors.page,
    ) { padding ->
        val sections = state.sections
        val now = serverNow(System.currentTimeMillis(), state.clockOffsetMillis)
        val workspaces = remember(state.workspaces) { state.workspaces.associateBy { it.workspaceId } }
        // 电脑菜单弹出时，后面的页面盖一层页面底色（设计规格第 3 节）
        if (hostMenu) {
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(colors.page.copy(alpha = MENU_SCRIM_ALPHA))
                    .zIndex(1f),
            )
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().testTag("home-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
        ) {
            navigation.hint?.let { hint -> item(key = "hint") { Box(Modifier.padding(bottom = 12.dp)) { hint() } } }
            if (!online) {
                item(key = "status") {
                    StatusBanner(state.status, hasSessions = state.sessions.isNotEmpty(), navigation, onRepair = { choosingRepair = true })
                }
            }
            if (sections.active.isNotEmpty()) {
                item(key = "active") {
                    ActiveCard(
                        sections.active,
                        state.attentionUi,
                        now,
                        enabled = online,
                        attentionActions,
                        navigation,
                        Modifier.alpha(if (online) 1f else STALE_ALPHA),
                    )
                }
            }
            if (sections.recent.isNotEmpty() || sections.projects.isNotEmpty()) {
                item(key = "recent-heading") { RecentHeading(sections, onFilter) }
                items(sections.recent, key = { "recent:${it.sessionId}" }) { session ->
                    RecentRow(
                        session,
                        project = if (sections.filter == null) projectNameOf(session, workspaces) else null,
                        now = now,
                        modifier = Modifier.alpha(if (online) 1f else STALE_ALPHA),
                    ) { navigation.onOpenSession(session.sessionId) }
                }
            }
            if (state.sessions.isEmpty() && state.otherAttention.isEmpty() && online) {
                item(key = "empty") { EmptySessions() }
            }
        }
    }
    if (expanded) {
        // 展开时后面的首页盖一层页面底色，点外面收起，已输入的内容保留
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.page.copy(alpha = SCRIM_ALPHA))
                    .clickable(interactionSource = null, indication = null, onClickLabel = stringResource(R.string.new_task_collapse)) {
                        expanded =
                            false
                    }.testTag("new-task-scrim"),
            )
            NewTaskComposer(
                text = taskText,
                onText = { taskText = it },
                state = newTask,
                project = newTask.project ?: defaultProject,
                workspaces = state.workspaces,
                online = online,
                actions = newTaskActions,
                onProject = onProject,
                modifier =
                    Modifier
                        .align(
                            Alignment.BottomCenter,
                        ).navigationBarsPadding()
                        .imePadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
    if (choosingRepair) {
        PairMethodDialog(
            title = stringResource(R.string.repair_method_title),
            onChoose = { manual ->
                choosingRepair = false
                navigation.onRepair(manual)
            },
            onDismiss = { choosingRepair = false },
        )
    }
}

/**
 * 顶栏：电脑名（17 号 600）和一个小箭头，点了弹出电脑菜单（设计规格 7.7）。每行高 48：当前电脑加粗带勾；
 * 其他正常的电脑点了切换过去；有问题的电脑右边一个短状态，点了不切换，进它的详情页；分隔线下面是「电脑与设置」。
 */
@Composable
private fun HomeTopBar(
    state: SessionListState,
    navigation: SessionListNavigation,
    menu: Boolean,
    onMenu: (Boolean) -> Unit,
) {
    val colors = DesignTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.page)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = if (navigation.onBack != null) 4.dp else 20.dp, end = 20.dp, top = 8.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigation.onBack?.let { back ->
            IconButton(onClick = back) {
                Icon(
                    painterResource(R.drawable.ic_dsh_chevron_left),
                    stringResource(R.string.action_back),
                    Modifier.size(20.dp),
                    tint = colors.text,
                )
            }
        }
        Box {
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = stringResource(R.string.home_switch_host), role = Role.Button) { onMenu(true) }
                    .testTag("home-host"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    state.hostName,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(painterResource(R.drawable.ic_dsh_chevron_down), null, Modifier.size(14.dp), tint = colors.textSecondary)
            }
            if (menu) {
                DesignPopup(onDismiss = { onMenu(false) }, modifier = Modifier.width(300.dp)) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        navigation.hosts.forEach { host ->
                            val current = host.hostId == state.hostId
                            val problem = if (current) null else menuStatus(host.status)
                            MenuRow(
                                host.name,
                                bold = current,
                                testTag = "home-host:${host.hostId}",
                                trailing =
                                    problem?.let { p ->
                                        {
                                            Text(
                                                stringResource(p.text),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = p.tone.color(),
                                            )
                                        }
                                    },
                            ) {
                                onMenu(false)
                                when {
                                    current -> Unit
                                    problem != null -> navigation.onOpenHostDetail(host.hostId)
                                    else -> navigation.onSwitchHost(host.hostId)
                                }
                            }
                        }
                        if (navigation.hosts.isNotEmpty()) DesignDivider(Modifier.padding(vertical = 4.dp))
                        MenuRow(stringResource(R.string.computers_and_settings), testTag = "home-computers-settings") {
                            onMenu(false)
                            navigation.onOpenSettings()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    text: String,
    bold: Boolean = false,
    testTag: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp)
            .let { if (testTag != null) it.testTag(testTag) else it },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal),
            color = DesignTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (bold) {
            Icon(
                painterResource(R.drawable.ic_dsh_check),
                stringResource(R.string.home_current_host),
                Modifier.size(18.dp),
                tint = DesignTheme.colors.text,
            )
        }
        trailing?.invoke()
    }
}

/** 不在线时显示连接状态和对应的操作。状态正常时不显示（设计原则 2）。 */
@Composable
private fun StatusBanner(
    status: LinkStatus,
    hasSessions: Boolean,
    navigation: SessionListNavigation,
    onRepair: () -> Unit,
) {
    val colors = DesignTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("home-status"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            statusDetail(status),
            style = MaterialTheme.typography.bodySmall,
            color = if (status.needsAttention) colors.danger else colors.textSecondary,
        )
        if (hasSessions) {
            Text(stringResource(R.string.sessions_stale), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
        if (status.offersRepair) {
            PillButton(stringResource(R.string.host_repair), onRepair, primary = true)
        } else if (status.offersRetry) {
            PillButton(stringResource(R.string.host_retry), navigation.onRetry)
        }
    }
}

/** 进行中卡片：面板底、描边、圆角 20，行与行之间一条分隔线。 */
@Composable
private fun ActiveCard(
    items: List<ActiveItem>,
    attentionUi: Map<String, AttentionUi>,
    now: Long,
    enabled: Boolean,
    actions: AttentionCardActions,
    navigation: SessionListNavigation,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .designPanel(RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp)
            .testTag("home-active"),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) DesignDivider()
            when (item) {
                is ActiveItem.Waiting ->
                    AttentionRow(
                        title = sessionTitleText(item.session.title),
                        time = relativeTime(item.session.updatedAt, now),
                        attention = item.attention,
                        ui = item.attention?.let { attentionUi[it.attentionId] } ?: AttentionUi(),
                        enabled = enabled,
                        actions = actions,
                        tag = item.key,
                        onOpen = { navigation.onOpenSession(item.session.sessionId) },
                    )
                is ActiveItem.Orphan ->
                    AttentionRow(
                        title = orphanTitle(item.attention),
                        time = relativeTime(item.attention.createdAt, now),
                        attention = item.attention,
                        ui = attentionUi[item.attention.attentionId] ?: AttentionUi(),
                        enabled = enabled,
                        actions = actions,
                        tag = item.key,
                        onOpen = { navigation.onOpenAttention(item.attention.attentionId) },
                    )
                is ActiveItem.Running -> RunningRow(item.session, item.key) { navigation.onOpenSession(item.session.sessionId) }
            }
        }
    }
}

/** 子代理发起的事项没有会话名，标题用事项本身的说明。 */
@Composable
private fun orphanTitle(attention: Attention): String =
    attention.approval?.let { it.summary ?: it.reason.ifBlank { it.toolName } }
        ?: attention.question
            ?.questions
            ?.firstOrNull()
            ?.question
        ?: stringResource(R.string.home_waiting)

/**
 * 等你处理的一行。审批：「想运行」加命令，右边「拒绝」「允许」。提问：第二行是问题；只有一个问题而且是单选时，
 * 第三行是前两个选项和「其他」，点选项直接回答，点「其他」进会话；多选和多个问题只显示问题，点了进会话。
 */
@Composable
private fun AttentionRow(
    title: String,
    time: String,
    attention: Attention?,
    ui: AttentionUi,
    enabled: Boolean,
    actions: AttentionCardActions,
    tag: String,
    onOpen: () -> Unit,
) {
    val colors = DesignTheme.colors
    val busy = ui.submitting || !enabled
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp).testTag(tag)) {
        TitleLine(title, time, StatusMark.WAITING, onOpen)
        val approval = attention?.approval
        val question = attention?.question
        when {
            approval != null -> {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp).heightIn(min = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val target = approvalTarget(approval)
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            when (approvalAction(approval.toolName)) {
                                ApprovalAction.RUN -> stringResource(R.string.home_wants_run)
                                ApprovalAction.EDIT -> stringResource(R.string.home_wants_edit)
                                ApprovalAction.OTHER -> stringResource(R.string.attention_use, approval.toolName)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                        )
                        if (target != null) {
                            Text(
                                target.lineSequence().first(),
                                style = CodeTextStyles.command,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    PillPair(
                        stringResource(R.string.attention_reject),
                        { actions.onDecide(attention, false) },
                        stringResource(R.string.attention_allow),
                        { actions.onDecide(attention, true) },
                        enabled = !busy,
                        size = PillSize.COMPACT,
                    )
                }
            }
            question != null -> {
                val single = question.questions.singleOrNull()
                Text(
                    single?.question ?: question.questions
                        .firstOrNull()
                        ?.question
                        .orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                )
                if (single != null && !single.multiSelect && single.options.isNotEmpty()) {
                    Row(
                        Modifier.padding(start = 16.dp, top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        single.options.take(2).forEach { option ->
                            PillButton(
                                option.label,
                                { actions.onAnswerOption(attention, single.id, option.label) },
                                Modifier.weight(1f, fill = false),
                                enabled = !busy,
                            )
                        }
                        Text(
                            stringResource(R.string.home_other_answer),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            modifier =
                                Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable(role = Role.Button, onClick = onOpen)
                                    .padding(horizontal = 8.dp, vertical = 14.dp),
                        )
                    }
                }
            }
            else ->
                Text(
                    stringResource(R.string.home_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.waitingText,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                )
        }
        when {
            ui.submitting ->
                Text(
                    stringResource(R.string.attention_submitting),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
            ui.error != null ->
                Text(
                    stringResource(
                        when (ui.error) {
                            AttentionError.OFFLINE -> R.string.attention_error_offline
                            AttentionError.REJECTED -> R.string.attention_error_rejected
                            else -> R.string.attention_error_failed
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.danger,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
        }
    }
}

/** 运行中的一行：转圈标记、会话名；第二行是在做什么（拿到轮次数据之前写「运行中」，落地方案 B1）。 */
@Composable
private fun RunningRow(
    session: SessionSummary,
    tag: String,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(vertical = 14.dp)
            .testTag(tag),
    ) {
        TitleLine(sessionTitleText(session.title), null, StatusMark.RUNNING, onOpen = null)
        Text(
            stringResource(R.string.home_running),
            style = MaterialTheme.typography.bodySmall,
            color = DesignTheme.colors.textSecondary,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp),
        )
    }
}

/** 卡片里每一行的第一行：标记、会话名（15 号 500）、右边的时间。 */
@Composable
private fun TitleLine(
    title: String,
    time: String?,
    mark: StatusMark,
    onOpen: (() -> Unit)?,
) {
    val colors = DesignTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onOpen != null) it.clickable(role = Role.Button, onClick = onOpen) else it },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(8.dp), contentAlignment = Alignment.Center) { StatusMarker(mark) }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (time != null) Text(time, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
    }
}

/** 「最近」一行：左边「最近」，右边项目筛选（默认「全部项目」）。选择记在本地设置里。 */
@Composable
private fun RecentHeading(
    sections: HomeSections,
    onFilter: (String?) -> Unit,
) {
    val colors = DesignTheme.colors
    var open by remember { mutableStateOf(false) }
    val current = sections.projects.firstOrNull { it.key == sections.filter }?.name ?: stringResource(R.string.home_all_projects)
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.design_recent),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (sections.projects.isNotEmpty()) {
            Box {
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { open = true }
                        .testTag("home-filter"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        current,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(painterResource(R.drawable.ic_dsh_chevron_down), null, Modifier.size(12.dp), tint = colors.textSecondary)
                }
                if (open) {
                    DesignPopup(onDismiss = { open = false }, alignment = Alignment.TopEnd, modifier = Modifier.width(240.dp)) {
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                            MenuRow(stringResource(R.string.home_all_projects), bold = sections.filter == null) {
                                open = false
                                onFilter(null)
                            }
                            sections.projects.forEach { project ->
                                MenuRow(project.name, bold = project.key == sections.filter, testTag = "home-filter:${project.key}") {
                                    open = false
                                    onFilter(project.key)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 最近的一行：会话名（拿到未读数据之前都按已读，400）、右边时间；第二行靠左是项目名，筛选到单个项目时不显示。
 * 最后一句回复、失败、已停止等落地方案 B1。
 */
@Composable
private fun RecentRow(
    session: SessionSummary,
    project: String?,
    now: Long,
    modifier: Modifier,
    onOpen: () -> Unit,
) {
    val colors = DesignTheme.colors
    Column(modifier.fillMaxWidth().testTag("recent:${session.sessionId}")) {
        DesignDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    sessionTitleText(session.title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(relativeTime(session.updatedAt, now), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            }
            if (project != null) {
                Text(
                    project,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptySessions() {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.sessions_empty_title), style = MaterialTheme.typography.titleMedium, color = DesignTheme.colors.text)
        Text(
            stringResource(R.string.sessions_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = DesignTheme.colors.textSecondary,
        )
    }
}

private const val STALE_ALPHA = 0.6f

/** 电脑菜单弹出时盖在首页上的那层页面底色。 */
private const val MENU_SCRIM_ALPHA = 0.6f

/** 输入框展开时盖在首页上的那层页面底色（设计规格第 3 节：60%–78%）。 */
private const val SCRIM_ALPHA = 0.78f
