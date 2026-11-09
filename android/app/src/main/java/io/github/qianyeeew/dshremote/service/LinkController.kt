package io.github.qianyeeew.dshremote.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 决定前台服务是否运行（m2-android.md 3.6）：有已配对的主机并且通知开关打开时运行，否则停止。
 * 前台服务不能从后台启动（Android 12 起），所以只在 App 处于前台时启动；停止则随时可以。
 */
class LinkController(
    private val context: Context,
    scope: CoroutineScope,
    hasHosts: Flow<Boolean>,
    notificationsEnabled: Flow<Boolean>,
    foreground: Flow<Boolean>,
    private val log: (String) -> Unit,
) {
    @Volatile
    private var running = false

    init {
        scope.launch {
            combine(hasHosts, notificationsEnabled, foreground) { hosts, enabled, fg -> (hosts && enabled) to fg }
                .distinctUntilChanged()
                .collect { (wanted, fg) ->
                    when {
                        wanted && fg -> start()
                        !wanted -> {
                            running = false
                            LinkService.stop(context)
                        }
                    }
                }
        }
    }

    /** 刚授予通知权限：之前发出的常驻通知没有显示，让服务重新显示一次（只在 App 处于前台时调用）。 */
    fun refresh() {
        if (running) start()
    }

    private fun start() {
        try {
            LinkService.start(context)
            running = true
        } catch (e: IllegalStateException) {
            // 例如 ForegroundServiceStartNotAllowedException（App 刚好退到后台）。下次回到前台时再启动
            log("前台服务启动失败：${e.javaClass.simpleName}")
        }
    }
}
