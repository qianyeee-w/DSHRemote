package io.github.qianyeeew.dshremote

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import io.github.qianyeeew.dshremote.data.live.ConnectionManager
import io.github.qianyeeew.dshremote.service.NotificationTargets
import io.github.qianyeeew.dshremote.service.Notifier
import io.github.qianyeeew.dshremote.ui.AppNavigation
import io.github.qianyeeew.dshremote.ui.DshRemoteTheme
import io.github.qianyeeew.dshremote.ui.chat.LocalImagePreparer
import io.github.qianyeeew.dshremote.ui.chat.LocalImageStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 单 Activity：所有页面都是 Compose。 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as DshRemoteApp).container

    /** 界面可见时保持连接（m2-android.md 3.3「启停」）；前台服务另外持有一个（T11）。 */
    private var visibleHold: ConnectionManager.Hold? = null

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) container.linkController.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 起系统强制全面屏，边距由 Scaffold 处理（ADR 0010、0011）
        enableEdgeToEdge()
        handleNotification(intent)
        setContent {
            DshRemoteTheme {
                // 图片的缓存和压缩（落地方案 C2）：会话页和首页的输入框、气泡都要用
                CompositionLocalProvider(LocalImageStore provides container.images, LocalImagePreparer provides container.imagePreparer) {
                    AppNavigation(container, versionName = BuildConfig.VERSION_NAME)
                }
            }
        }
        askForNotificationsOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotification(intent)
    }

    override fun onStart() {
        super.onStart()
        if (visibleHold == null) visibleHold = container.connections.hold()
        container.foreground.value = true
    }

    override fun onStop() {
        container.foreground.value = false
        visibleHold?.release()
        visibleHold = null
        super.onStop()
    }

    /** 点了通知：只认识这个进程发出的令牌，伪造的 Intent 被忽略（见 NotificationTargets）。 */
    private fun handleNotification(intent: Intent?) {
        if (intent?.action != NotificationTargets.ACTION_OPEN) return
        NotificationTargets.resolve(intent.getStringExtra(NotificationTargets.EXTRA_TOKEN))?.let { container.pendingTarget.value = it }
    }

    /** Android 13 起：有了第一台电脑后，申请一次通知权限（之后在设置页中可以再次申请）。 */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        lifecycleScope.launch {
            combine(container.hosts.hosts, container.settings.settings) { hosts, settings -> hosts.isNotEmpty() to settings }
                .first { (hasHosts, settings) -> settings.notificationPermissionAsked || hasHosts }
                .let { (_, settings) ->
                    if (settings.notificationPermissionAsked || Notifier.canPost(this@MainActivity)) return@launch
                    container.settings.markNotificationPermissionAsked()
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
        }
    }
}
