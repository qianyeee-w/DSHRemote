package io.github.qianyeeew.dshremote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.settings.SettingsRepository
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.common.PillPair
import kotlinx.coroutines.launch

/**
 * 首页上的提示：通知打开了，但 App 还在电池优化名单里（锁屏后连接可能被系统断开，m2-android.md 3.6）。
 * 可以「不再提示」，设置页里随时能看到同样的引导。
 */
@Composable
fun BackgroundHint(
    settings: SettingsRepository,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var status by remember { mutableStateOf(DeviceStatus.of(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = DeviceStatus.of(context) }
    val s = current ?: return
    if (!s.notificationsEnabled || s.backgroundHintDismissed || status.ignoringBatteryOptimizations) return
    // 不用卡片：只有「等你处理」的东西带框（设计原则 4）。还差一步的提示用等你（字）的颜色
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.background_hint), style = MaterialTheme.typography.bodySmall, color = DesignTheme.colors.waitingText)
        PillPair(
            stringResource(R.string.background_hint_dismiss),
            { scope.launch { settings.dismissBackgroundHint() } },
            stringResource(R.string.background_hint_go),
            onOpenSettings,
        )
    }
}
