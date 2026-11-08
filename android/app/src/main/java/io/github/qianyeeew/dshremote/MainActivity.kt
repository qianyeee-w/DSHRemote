package io.github.qianyeeew.dshremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.qianyeeew.dshremote.ui.DshRemoteTheme
import io.github.qianyeeew.dshremote.ui.SkeletonScreen

/** 单 Activity：所有页面都是 Compose。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 起系统强制全面屏，边距由 Scaffold 处理（ADR 0010、0011）
        enableEdgeToEdge()
        setContent {
            DshRemoteTheme {
                SkeletonScreen(versionName = BuildConfig.VERSION_NAME)
            }
        }
    }
}
