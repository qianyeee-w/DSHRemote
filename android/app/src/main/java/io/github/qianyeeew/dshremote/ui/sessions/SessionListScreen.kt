package io.github.qianyeeew.dshremote.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.common.relativeTime
import io.github.qianyeeew.dshremote.ui.common.serverNow
import io.github.qianyeeew.dshremote.ui.hosts.PairMethodDialog
import io.github.qianyeeew.dshremote.ui.hosts.StatusBadge
import io.github.qianyeeew.dshremote.ui.hosts.offersRepair
import io.github.qianyeeew.dshremote.ui.hosts.offersRetry
import io.github.qianyeeew.dshremote.ui.hosts.statusDetail

/** 页面上的导航操作。 */
class SessionListNavigation(
    /** 不为 null 时显示返回按钮（有多台主机时从主机列表进入）。只有一台主机时这一页就是首页，没有返回。 */
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
)

@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    navigation: SessionListNavigation,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val newSession by viewModel.newSession.collectAsStateWithLifecycle()
    val project by viewModel.projects.state.collectAsStateWithLifecycle()
    LaunchedEffect(newSession) {
        (newSession as? NewSession.Created)?.let {
            navigation.onOpenSession(it.sessionId)
            viewModel.dismissNewSession()
        }
    }
    val current = state ?: return
    SessionListContent(
        state = current,
        newSession = newSession,
        navigation = navigation,
        onNewSession = viewModel::startNewSession,
        onChooseWorkspace = viewModel::create,
        onDismissNewSession = viewModel::dismissNewSession,
        onProject = { mode ->
            viewModel.dismissNewSession()
            viewModel.projects.open(mode)
        },
    )
    project?.let { selection ->
        ProjectPickerDialog(
            state = selection,
            hostName = current.hostName,
            onBrowse = viewModel.projects::browse,
            onName = viewModel.projects::name,
            onSubmit = viewModel.projects::submit,
            onDismiss = viewModel.projects::dismiss,
            onStartSession = { selection.workspace?.let(viewModel::startProjectSession) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListContent(
    state: SessionListState,
    newSession: NewSession,
    navigation: SessionListNavigation,
    onNewSession: () -> Unit,
    onChooseWorkspace: (workspaceId: String?) -> Unit,
    onDismissNewSession: () -> Unit,
    onProject: (ProjectMode) -> Unit = {},
) {
    var choosingRepair by remember { mutableStateOf(false) }
    var choosingAdd by remember { mutableStateOf(false) }
    val online = state.status is LinkStatus.Online
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.hostName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    navigation.onBack?.let { back ->
                        IconButton(onClick = back) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = { OverflowMenu(navigation, onAddHost = { choosingAdd = true }) },
            )
        },
        floatingActionButton = {
            if (online) {
                ExtendedFloatingActionButton(
                    onClick = onNewSession,
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.sessions_new)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            navigation.hint?.let { hint -> item(key = "hint") { hint() } }
            if (!online) {
                item(key = "status") {
                    StatusBanner(state.status, hasSessions = state.sessions.isNotEmpty(), navigation, onRepair = { choosingRepair = true })
                }
            }
            if (state.otherAttention.isNotEmpty() && online) {
                item(key = "other-attention") { OtherAttention(state.otherAttention, navigation.onOpenAttention) }
            }
            if (state.sessions.isEmpty() && online) {
                item(key = "empty") { EmptySessions(onNewSession) }
            }
            val now = serverNow(System.currentTimeMillis(), state.clockOffsetMillis)
            items(state.sessions, key = { it.sessionId }) { session ->
                SessionRow(session, now, stale = !online) { navigation.onOpenSession(session.sessionId) }
            }
        }
    }
    if (newSession != NewSession.Closed && newSession !is NewSession.Created) {
        NewSessionDialog(newSession, onChooseWorkspace, onRetry = onNewSession, onDismiss = onDismissNewSession, onProject = onProject)
    }
    if (choosingAdd) {
        PairMethodDialog(
            title = stringResource(R.string.pair_method_title),
            onChoose = { manual ->
                choosingAdd = false
                navigation.onAddHost?.invoke(manual)
            },
            onDismiss = { choosingAdd = false },
        )
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

@Composable
private fun OverflowMenu(
    navigation: SessionListNavigation,
    onAddHost: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_manage_host)) }, onClick = {
                open = false
                navigation.onManageHost()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_settings)) }, onClick = {
                open = false
                navigation.onOpenSettings()
            })
            if (navigation.onAddHost != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.hosts_add)) }, onClick = {
                    open = false
                    onAddHost()
                })
            }
        }
    }
}

/** 不在线时显示连接状态和对应的操作。 */
@Composable
private fun StatusBanner(
    status: LinkStatus,
    hasSessions: Boolean,
    navigation: SessionListNavigation,
    onRepair: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusBadge(status)
            Text(statusDetail(status), style = MaterialTheme.typography.bodyMedium)
            if (hasSessions) {
                Text(
                    stringResource(R.string.sessions_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status.offersRepair) {
                Button(onClick = onRepair) { Text(stringResource(R.string.host_repair)) }
            } else if (status.offersRetry) {
                OutlinedButton(onClick = navigation.onRetry) { Text(stringResource(R.string.host_retry)) }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    now: Long,
    stale: Boolean,
    onClick: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .alpha(if (stale) STALE_ALPHA else 1f)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sessions_untitled),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SessionStatusLabel(session, Modifier.padding(start = 8.dp))
            }
            val details =
                listOfNotNull(
                    session.cwd?.let(::shortPath),
                    relativeTime(session.updatedAt, now),
                )
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SessionStatusLabel(
    session: SessionSummary,
    modifier: Modifier = Modifier,
) {
    val (text, color) =
        when (session.status) {
            SessionStatus.WAITING ->
                (
                    if (session.attention.size > 1) {
                        stringResource(R.string.session_status_waiting_count, session.attention.size)
                    } else {
                        stringResource(R.string.session_status_waiting)
                    }
                ) to MaterialTheme.colorScheme.error
            SessionStatus.RUNNING -> stringResource(R.string.session_status_running) to MaterialTheme.colorScheme.primary
            SessionStatus.IDLE -> stringResource(R.string.session_status_idle) to MaterialTheme.colorScheme.onSurfaceVariant
            SessionStatus.UNKNOWN -> stringResource(R.string.session_status_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
        }
    Text(text, style = MaterialTheme.typography.labelLarge, color = color, modifier = modifier)
}

/** 不属于任何会话的待处理事项，点击进入独立的处理页面。 */
@Composable
private fun OtherAttention(
    attention: List<Attention>,
    onOpen: (attentionId: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.sessions_other_attention),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        attention.forEach { item ->
            Card(
                Modifier.fillMaxWidth().clickable { onOpen(item.attentionId) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(
                            if (item.kind ==
                                AttentionKind.QUESTION
                            ) {
                                R.string.attention_question_title
                            } else {
                                R.string.attention_approval_title
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val detail =
                        item.approval?.let { it.summary ?: stringResource(R.string.attention_tool, it.toolName) }
                            ?: item.question
                                ?.questions
                                ?.firstOrNull()
                                ?.question
                    detail?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun EmptySessions(onNewSession: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.sessions_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.sessions_empty_body), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onNewSession) { Text(stringResource(R.string.sessions_new)) }
    }
}

@Composable
private fun NewSessionDialog(
    state: NewSession,
    onChoose: (workspaceId: String?) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onProject: (ProjectMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_session_title)) },
        text = {
            when (state) {
                NewSession.Loading -> Progress(stringResource(R.string.new_session_loading))
                NewSession.Creating -> Progress(stringResource(R.string.new_session_creating))
                is NewSession.Choose ->
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        TextButton(onClick = { onProject(ProjectMode.CREATE) }) { Text(stringResource(R.string.project_create)) }
                        TextButton(onClick = { onProject(ProjectMode.IMPORT) }) { Text(stringResource(R.string.project_import)) }
                        Text(stringResource(R.string.new_session_choose), style = MaterialTheme.typography.bodyMedium)
                        state.workspaces.forEach { workspace ->
                            ListItem(
                                headlineContent = { Text(workspace.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(workspace.path, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable { onChoose(workspace.workspaceId) },
                            )
                            HorizontalDivider()
                        }
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.new_session_no_workspace)) },
                            supportingContent = { Text(stringResource(R.string.new_session_no_workspace_hint)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onChoose(null) },
                        )
                    }
                is NewSession.Failed ->
                    Text(
                        stringResource(
                            when (state.error) {
                                CreateError.OFFLINE -> R.string.new_session_offline
                                CreateError.DSH_UNAVAILABLE -> R.string.new_session_dsh_unavailable
                                CreateError.NEEDS_PAIRING -> R.string.new_session_needs_pairing
                                CreateError.FAILED -> R.string.new_session_failed
                            },
                        ),
                    )
                NewSession.Closed, is NewSession.Created -> Unit
            }
        },
        confirmButton = {
            if (state is NewSession.Failed && state.error != CreateError.NEEDS_PAIRING) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        },
        dismissButton = {
            if (state != NewSession.Creating) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(text)
    }
}

/** 只显示路径的最后两级，例如 `/home/me/work/app` → `work/app`。 */
internal fun shortPath(path: String): String {
    val parts = path.trimEnd('/', '\\').split('/', '\\').filter { it.isNotEmpty() }
    return if (parts.size <= 2) path else parts.takeLast(2).joinToString("/")
}

private const val STALE_ALPHA = 0.6f
