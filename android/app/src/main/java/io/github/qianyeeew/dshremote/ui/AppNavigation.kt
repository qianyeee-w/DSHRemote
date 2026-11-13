package io.github.qianyeeew.dshremote.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.qianyeeew.dshremote.AppContainer
import io.github.qianyeeew.dshremote.service.NotificationTarget
import io.github.qianyeeew.dshremote.service.VisibleSession
import io.github.qianyeeew.dshremote.ui.attention.AttentionScreen
import io.github.qianyeeew.dshremote.ui.attention.AttentionViewModel
import io.github.qianyeeew.dshremote.ui.attention.LiveAttentionResponder
import io.github.qianyeeew.dshremote.ui.attention.attentionSession
import io.github.qianyeeew.dshremote.ui.chat.ChatScreen
import io.github.qianyeeew.dshremote.ui.chat.ChatViewModel
import io.github.qianyeeew.dshremote.ui.chat.LiveChatBackend
import io.github.qianyeeew.dshremote.ui.chat.PlanScreen
import io.github.qianyeeew.dshremote.ui.hosts.HostDetailScreen
import io.github.qianyeeew.dshremote.ui.hosts.HostDetailViewModel
import io.github.qianyeeew.dshremote.ui.hosts.HostListViewModel
import io.github.qianyeeew.dshremote.ui.hosts.HostRow
import io.github.qianyeeew.dshremote.ui.hosts.NoHostsContent
import io.github.qianyeeew.dshremote.ui.pairing.PairingScreen
import io.github.qianyeeew.dshremote.ui.pairing.PairingViewModel
import io.github.qianyeeew.dshremote.ui.sessions.PluginSessionCreator
import io.github.qianyeeew.dshremote.ui.sessions.SessionListNavigation
import io.github.qianyeeew.dshremote.ui.sessions.SessionListScreen
import io.github.qianyeeew.dshremote.ui.sessions.SessionListViewModel
import io.github.qianyeeew.dshremote.ui.sessions.homeHost
import io.github.qianyeeew.dshremote.ui.settings.BackgroundHint
import io.github.qianyeeew.dshremote.ui.settings.ComputersSettingsScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/*
 * 导航路由（m2-android.md 3.7）。路由参数会进入 SavedState，所以只放 id 之类的非秘密信息：
 * 配对码只保存在 PairingViewModel 的内存中。
 */

/** 首页：没有电脑时是配对引导，否则是上次用的那台电脑的会话列表（多台时从左上角切换）。 */
@Serializable
object HomeRoute

@Serializable
data class SessionRoute(
    val hostId: String,
    val sessionId: String,
)

/** 会话的计划页（设计规格 7.12）：和会话页共用一个视图模型。 */
@Serializable
data class PlanRoute(
    val hostId: String,
    val sessionId: String,
)

/** 独立的处理页面（子代理发起的事项、从通知进入）。 */
@Serializable
data class AttentionRoute(
    val hostId: String,
    val attentionId: String,
)

/** 电脑与设置（设计规格 7.8）。 */
@Serializable
object SettingsRoute

/** 主机管理。 */
@Serializable
data class HostRoute(
    val hostId: String,
)

@Serializable
data class PairRoute(
    val manual: Boolean,
    /** 不为 null 时是重新配对这台主机。 */
    val replaceHostId: String? = null,
)

@Composable
fun AppNavigation(
    container: AppContainer,
    versionName: String,
) {
    val nav = rememberNavController()
    // 点了通知：打开对应的处理页面或会话（MainActivity 把令牌换成页面后放在这里）
    val target by container.pendingTarget.collectAsStateWithLifecycle()
    LaunchedEffect(target) {
        when (val t = target) {
            // 事项所在的会话在列表里时打开会话，面板就在底部（设计规格 7.4、用户确认）；
            // 子代理发起、或者还没连上时找不到会话，用独立的处理页
            is NotificationTarget.AttentionPage -> {
                val sessionId = attentionSession(container.connections.states.value[t.hostId], t.attentionId)
                if (sessionId != null) {
                    nav.navigate(SessionRoute(t.hostId, sessionId)) { launchSingleTop = true }
                } else {
                    nav.navigate(AttentionRoute(t.hostId, t.attentionId)) { launchSingleTop = true }
                }
            }
            is NotificationTarget.SessionPage -> nav.navigate(SessionRoute(t.hostId, t.sessionId)) { launchSingleTop = true }
            null -> return@LaunchedEffect
        }
        container.pendingTarget.value = null
    }
    val hint: @Composable () -> Unit = { BackgroundHint(container.settings) { nav.navigate(SettingsRoute) } }
    NavHost(nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            val hosts = viewModel { HostListViewModel(container.hosts.hosts, container.connections.states) }
            val rows by hosts.rows.collectAsStateWithLifecycle()
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val scope = rememberCoroutineScope()
            // 配对了多台电脑时直接显示上次用的那台，从左上角切换（用户确认）
            val hostId = rows?.let { list -> settings?.let { homeHost(list.map(HostRow::hostId), it.lastHostId) } }
            if (rows != null && settings == null) return@composable
            if (hostId != null) {
                SessionList(
                    container,
                    nav,
                    hostId,
                    home = true,
                    hint = hint,
                    hosts = rows.orEmpty(),
                    onSwitchHost = { scope.launch { container.settings.setLastHost(it) } },
                )
            } else if (rows != null) {
                NoHostsContent(versionName, onPair = { manual -> nav.navigate(PairRoute(manual)) })
            }
        }
        composable<SessionRoute> { entry ->
            val route = entry.toRoute<SessionRoute>()
            // 正在看这个会话：通知规则不为它发通知，并撤回它的「任务完成」通知
            LifecycleStartEffect(route) {
                val release = container.holdVisible(VisibleSession(route.hostId, route.sessionId))
                onStopOrDispose { release() }
            }
            ChatScreen(
                viewModel = viewModel { chatViewModel(container, route.hostId, route.sessionId) },
                onBack = { nav.popBackStack() },
                onOpenPlan = { nav.navigate(PlanRoute(route.hostId, route.sessionId)) },
            )
        }
        composable<PlanRoute> { entry ->
            val route = entry.toRoute<PlanRoute>()
            LifecycleStartEffect(route) {
                val release = container.holdVisible(VisibleSession(route.hostId, route.sessionId))
                onStopOrDispose { release() }
            }
            // 计划页从会话页进入，用会话页的那一个视图模型（计划和待处理事项都在里面）
            val parent = remember(entry) { nav.getBackStackEntry(SessionRoute(route.hostId, route.sessionId)) }
            PlanScreen(
                viewModel = viewModel(viewModelStoreOwner = parent) { chatViewModel(container, route.hostId, route.sessionId) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<AttentionRoute> { entry ->
            val route = entry.toRoute<AttentionRoute>()
            AttentionScreen(
                viewModel =
                    viewModel {
                        AttentionViewModel(
                            hostId = route.hostId,
                            attentionId = route.attentionId,
                            states = container.connections.states,
                            responder = LiveAttentionResponder(container.connections, route.hostId),
                            resolvedEvents = container.connections.resolvedEvents(route.hostId),
                        )
                    },
                onBack = { nav.popBackStack() },
                onOpenSession = { nav.navigate(SessionRoute(route.hostId, it)) },
            )
        }
        composable<SettingsRoute> {
            val hosts = viewModel { HostListViewModel(container.hosts.hosts, container.connections.states) }
            val rows by hosts.rows.collectAsStateWithLifecycle()
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val list = rows.orEmpty()
            ComputersSettingsScreen(
                settings = container.settings,
                hosts = list,
                currentHostId = homeHost(list.map(HostRow::hostId), settings?.lastHostId),
                versionName = versionName,
                onBack = { nav.popBackStack() },
                onOpenHost = { nav.navigate(HostRoute(it)) },
                onAddHost = { manual -> nav.navigate(PairRoute(manual)) },
                onPermissionGranted = container.linkController::refresh,
            )
        }
        composable<HostRoute> { entry ->
            val hostId = entry.toRoute<HostRoute>().hostId
            val hostList by container.hosts.hosts.collectAsStateWithLifecycle(initialValue = emptyList())
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            HostDetailScreen(
                currentHost = homeHost(hostList.map { it.hostId }, settings?.lastHostId) == hostId,
                viewModel =
                    viewModel {
                        HostDetailViewModel(
                            hostId = hostId,
                            hosts = container.hosts.hosts,
                            states = container.connections.states,
                            repository = container.hosts,
                            pairer = container.pairing,
                            retry = { container.connections.retry(it) },
                        )
                    },
                onBack = { nav.popBackStack() },
                onRepair = { manual -> nav.navigate(PairRoute(manual, hostId)) },
            )
        }
        composable<PairRoute> { entry ->
            val route = entry.toRoute<PairRoute>()
            val scope = rememberCoroutineScope()
            PairingScreen(
                viewModel = viewModel { PairingViewModel(route.manual, route.replaceHostId, container.pairing) },
                replacing = route.replaceHostId != null,
                onClose = { nav.popBackStack() },
                // 新配对的电脑：首页切到这台（刚配对完通常就是要用它）
                onPaired = { hostId -> scope.launch { container.settings.setLastHost(hostId) } },
            )
        }
    }
}

@Composable
private fun SessionList(
    container: AppContainer,
    nav: NavController,
    hostId: String,
    home: Boolean,
    hint: (@Composable () -> Unit)? = null,
    hosts: List<HostRow> = emptyList(),
    onSwitchHost: (String) -> Unit = {},
) {
    SessionListScreen(
        // 首页上的会话列表跟着唯一的主机变化，所以按 hostId 区分 ViewModel
        viewModel =
            viewModel(key = "sessions:$hostId") {
                SessionListViewModel(
                    hostId = hostId,
                    hosts = container.hosts.hosts,
                    states = container.connections.states,
                    creator = PluginSessionCreator { container.connections.api(hostId) },
                    projectBackend =
                        io.github.qianyeeew.dshremote.ui.sessions
                            .PluginProjectBackend { container.connections.api(hostId) },
                    responder = LiveAttentionResponder(container.connections, hostId),
                    resolvedEvents = container.connections.resolvedEvents(hostId),
                    projectFilter = container.settings.projectFilter(hostId),
                    saveProjectFilter = { container.settings.setProjectFilter(hostId, it) },
                    lastProject = container.settings.lastProject(hostId),
                    saveLastProject = { container.settings.setLastProject(hostId, it) },
                    seen = container.reads.records.map { it[hostId] },
                    reconnect = container.reads.reconnects.map { it[hostId] },
                    onDismissReconnect = { container.reads.dismiss(hostId) },
                    drafts = container.drafts.drafts(hostId),
                )
            },
        navigation =
            SessionListNavigation(
                onBack = if (home) null else ({ nav.popBackStack() }),
                onOpenSession = { nav.navigate(SessionRoute(hostId, it)) },
                onOpenAttention = { nav.navigate(AttentionRoute(hostId, it)) },
                onManageHost = { nav.navigate(HostRoute(hostId)) },
                onAddHost = if (home) ({ manual -> nav.navigate(PairRoute(manual)) }) else null,
                onRepair = { manual -> nav.navigate(PairRoute(manual, hostId)) },
                onRetry = { container.connections.retry(hostId) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onOpenHostDetail = { nav.navigate(HostRoute(it)) },
                hint = hint,
                hosts = hosts,
                onSwitchHost = onSwitchHost,
                onTaskStarted = { started ->
                    started.handoff?.let { container.handoffs.put(hostId, started.sessionId, it) }
                    nav.navigate(SessionRoute(hostId, started.sessionId))
                },
            ),
    )
}

/** 会话页的视图模型（计划页也用会话页的这一个）。 */
private fun chatViewModel(
    container: AppContainer,
    hostId: String,
    sessionId: String,
) = ChatViewModel(
    hostId = hostId,
    sessionId = sessionId,
    states = container.connections.states,
    backend = LiveChatBackend(container.connections, hostId, sessionId),
    responder = LiveAttentionResponder(container.connections, hostId),
    resolvedEvents = container.connections.resolvedEvents(hostId),
    hostName = container.hosts.hosts.map { list -> list.firstOrNull { it.hostId == hostId }?.name },
    handoff = container.handoffs.take(hostId, sessionId),
    storedDraft = container.drafts.drafts(hostId).map { it[sessionId] },
    saveDraft = { container.saveDraft(hostId, sessionId, it) },
)
