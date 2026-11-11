package io.github.qianyeeew.dshremote.ui.sessions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.LocalStatusColors
import io.github.qianyeeew.dshremote.ui.common.DesignTopBar
import io.github.qianyeeew.dshremote.ui.common.RoundIconButton
import io.github.qianyeeew.dshremote.ui.common.relativeTime
import io.github.qianyeeew.dshremote.ui.common.serverNow
import io.github.qianyeeew.dshremote.ui.hosts.HostRow
import io.github.qianyeeew.dshremote.ui.hosts.StatusBadge

/** 首页的纯布局。主机、事项和项目操作均使用已有业务回调。 */
@Composable
internal fun SessionHomeLayout(
    state: SessionListState,
    navigation: SessionListNavigation,
    workspaces: List<Workspace>,
    hosts: List<HostRow>,
    selectedHostId: String?,
    onSelectHost: (String) -> Unit,
    onManageHosts: () -> Unit,
    onAddHost: () -> Unit,
    onNewSession: () -> Unit,
    onProjectSession: (String) -> Unit,
    onProject: (ProjectMode) -> Unit,
    statusContent: @Composable () -> Unit,
    attentionContent: @Composable () -> Unit,
    emptyContent: @Composable () -> Unit,
    catalogLoading: Boolean = false,
    catalogFailed: Boolean = false,
    onReloadProjects: () -> Unit = {},
) {
    val online = state.status is LinkStatus.Online
    val grouped = remember(state.sessions, workspaces) { groupSessions(state.sessions, workspaces) }
    var collapsed by rememberSaveable(selectedHostId) { mutableStateOf(emptyList<String>()) }
    var recentOpen by rememberSaveable(selectedHostId) { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val now = serverNow(System.currentTimeMillis(), state.clockOffsetMillis)
    Scaffold(
        topBar = {
            DesignTopBar(
                title = stringResource(R.string.app_name),
                navigation = {
                    navigation.onBack?.let {
                        RoundIconButton(R.drawable.ic_arrow_back, stringResource(R.string.action_back), it)
                    } ?: Box {
                        RoundIconButton(R.drawable.ic_dsh_menu, stringResource(R.string.design_navigation_menu), { menu = true })
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.design_manage_hosts)) }, onClick = {
                                menu = false
                                onManageHosts()
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_manage_host)) }, onClick = {
                                menu = false
                                navigation.onManageHost()
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_settings)) }, onClick = {
                                menu = false
                                navigation.onOpenSettings()
                            })
                            if (navigation.onAddHost != null) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.hosts_add)) }, onClick = {
                                    menu = false
                                    onAddHost()
                                })
                            }
                        }
                    }
                },
                actions = {
                    RoundIconButton(R.drawable.ic_add, stringResource(R.string.sessions_new), onNewSession, enabled = online)
                },
            )
        },
        bottomBar = {
            Surface(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(enabled = online, onClick = onNewSession)
                        .padding(horizontal = 16.dp)
                        .testTag("home-new-session"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add), null, Modifier.size(22.dp))
                    Text(
                        stringResource(R.string.design_new_conversation),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().testTag("home-list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            item(key = "hosts") {
                if (hosts.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        items(hosts, key = { it.hostId }) { host ->
                            FilterChip(
                                selected = host.hostId == selectedHostId,
                                onClick = {
                                    if (host.hostId !=
                                        selectedHostId
                                    ) {
                                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        onSelectHost(host.hostId)
                                    }
                                },
                                label = {
                                    Column(Modifier.widthIn(max = 200.dp)) {
                                        Text(host.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        StatusBadge(host.status)
                                    }
                                },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_dsh_computer), null, Modifier.size(17.dp)) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("host-chip:${host.hostId}"),
                            )
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.hostName, style = MaterialTheme.typography.labelLarge)
                        StatusBadge(state.status)
                    }
                }
            }
            navigation.hint?.let { item(key = "hint") { it() } }
            if (!online) item(key = "connection") { statusContent() }
            if (online && state.otherAttention.isNotEmpty()) item(key = "other-attention") { attentionContent() }
            if (grouped.waiting.isNotEmpty()) {
                item(key = "waiting-heading") {
                    Text(
                        stringResource(R.string.design_attention_count, grouped.waiting.size),
                        color = LocalStatusColors.current.waiting,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    )
                }
                items(grouped.waiting, key = { "waiting:${it.sessionId}" }) {
                    HomeSessionRow(it, now, !online, Modifier.testTag("waiting:${it.sessionId}")) { navigation.onOpenSession(it.sessionId) }
                }
            }
            item(key = "projects-heading") {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.design_projects),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onProject(ProjectMode.CREATE) },
                        enabled = online,
                    ) { Text(stringResource(R.string.project_create)) }
                    TextButton(
                        onClick = { onProject(ProjectMode.IMPORT) },
                        enabled = online,
                    ) { Text(stringResource(R.string.project_import)) }
                }
            }
            if (catalogLoading) {
                item(key = "project-loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
            }
            if (catalogFailed) {
                item(key = "project-error") {
                    Column {
                        Text(stringResource(R.string.design_project_load_failed), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onReloadProjects, enabled = online) { Text(stringResource(R.string.action_retry)) }
                    }
                }
            }
            grouped.projects.forEach { project ->
                val open = project.workspaceId !in collapsed
                item(key = "project:${project.workspaceId}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExpandHeading(
                            project.title ?: stringResource(R.string.design_project_untitled),
                            open,
                            Modifier.weight(1f).testTag("project:${project.workspaceId}"),
                            icon = R.drawable.ic_dsh_folder,
                        ) { collapsed = if (open) collapsed + project.workspaceId else collapsed - project.workspaceId }
                        IconButton(onClick = { onProjectSession(project.workspaceId) }, enabled = online) {
                            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.sessions_new), Modifier.size(19.dp))
                        }
                    }
                }
                if (open) {
                    items(project.sessions, key = { "project-session:${it.sessionId}" }) {
                        HomeSessionRow(it, now, !online, Modifier.padding(start = 30.dp)) { navigation.onOpenSession(it.sessionId) }
                    }
                }
            }
            if (grouped.recent.isNotEmpty()) {
                item(key = "recent-heading") {
                    ExpandHeading(stringResource(R.string.design_recent), recentOpen, Modifier.padding(top = 16.dp)) {
                        recentOpen =
                            !recentOpen
                    }
                }
                if (recentOpen) {
                    items(grouped.recent, key = { "recent:${it.sessionId}" }) {
                        HomeSessionRow(it, now, !online) { navigation.onOpenSession(it.sessionId) }
                    }
                }
            }
            if (state.sessions.isEmpty() && online) item(key = "empty") { emptyContent() }
        }
    }
}

@Composable
private fun ExpandHeading(
    title: String,
    open: Boolean,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(if (open) 0f else -90f, tween(180), label = "section-chevron")
    val description = stringResource(if (open) R.string.design_collapse else R.string.design_expand)
    val sectionState = stringResource(if (open) R.string.design_expanded else R.string.design_collapsed)
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClickLabel = description, onClick = onClick).semantics {
            stateDescription =
                sectionState
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon?.let { Icon(painterResource(it), null, Modifier.size(20.dp)) }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(20.dp).rotate(rotation))
    }
}

@Composable
private fun HomeSessionRow(
    session: SessionSummary,
    now: Long,
    stale: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .alpha(if (stale) 0.6f else 1f)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            session.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.sessions_untitled),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val (label, color) =
                when (if (session.attention.isNotEmpty()) SessionStatus.WAITING else session.status) {
                    SessionStatus.WAITING -> R.string.session_status_waiting to LocalStatusColors.current.waiting
                    SessionStatus.RUNNING -> R.string.session_status_running to LocalStatusColors.current.running
                    SessionStatus.UNKNOWN -> R.string.session_status_unknown to MaterialTheme.colorScheme.onSurfaceVariant
                    SessionStatus.IDLE -> R.string.session_status_idle to MaterialTheme.colorScheme.onSurfaceVariant
                }
            Text(stringResource(label), color = color, style = MaterialTheme.typography.labelSmall)
            Text(
                listOfNotNull(session.cwd?.let(::shortPath), relativeTime(session.updatedAt, now)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
