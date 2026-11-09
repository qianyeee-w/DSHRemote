package io.github.qianyeeew.dshremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.qianyeeew.dshremote.data.live.ConnectionManager
import io.github.qianyeeew.dshremote.ui.AppNavigation
import io.github.qianyeeew.dshremote.ui.DshRemoteTheme

/** 单 Activity：所有页面都是 Compose。 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as DshRemoteApp).container

    /** 界面可见时保持连接（m2-android.md 3.3「启停」）；前台服务另外持有一个（T11）。 */
    private var visibleHold: ConnectionManager.Hold? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 起系统强制全面屏，边距由 Scaffold 处理（ADR 0010、0011）
        enableEdgeToEdge()
        setContent {
            DshRemoteTheme {
                AppNavigation(container, versionName = BuildConfig.VERSION_NAME)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (visibleHold == null) visibleHold = container.connections.hold()
    }

    override fun onStop() {
        visibleHold?.release()
        visibleHold = null
        super.onStop()
    }
}
