package io.github.qianyeeew.dshremote.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.qianyeeew.dshremote.AppContainer
import io.github.qianyeeew.dshremote.ui.hosts.HostDetailScreen
import io.github.qianyeeew.dshremote.ui.hosts.HostDetailViewModel
import io.github.qianyeeew.dshremote.ui.hosts.HostListScreen
import io.github.qianyeeew.dshremote.ui.hosts.HostListViewModel
import io.github.qianyeeew.dshremote.ui.pairing.PairingScreen
import io.github.qianyeeew.dshremote.ui.pairing.PairingViewModel
import kotlinx.serialization.Serializable

/*
 * 导航路由（m2-android.md 3.7）。路由参数会进入 SavedState，所以只放 id 之类的非秘密信息：
 * 配对码只保存在 PairingViewModel 的内存中。
 */

@Serializable
object HostsRoute

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
    NavHost(nav, startDestination = HostsRoute) {
        composable<HostsRoute> {
            HostListScreen(
                viewModel = viewModel { HostListViewModel(container.hosts.hosts, container.connections.states) },
                versionName = versionName,
                onOpenHost = { nav.navigate(HostRoute(it)) },
                onPair = { manual -> nav.navigate(PairRoute(manual)) },
            )
        }
        composable<HostRoute> { entry ->
            val hostId = entry.toRoute<HostRoute>().hostId
            HostDetailScreen(
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
            PairingScreen(
                viewModel = viewModel { PairingViewModel(route.manual, route.replaceHostId, container.pairing) },
                replacing = route.replaceHostId != null,
                onClose = { nav.popBackStack() },
            )
        }
    }
}
