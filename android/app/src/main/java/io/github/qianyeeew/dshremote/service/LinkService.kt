package io.github.qianyeeew.dshremote.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.qianyeeew.dshremote.DshRemoteApp
import io.github.qianyeeew.dshremote.MainActivity
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.live.ConnectionManager
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 前台服务（`specialUse`，ADR 0009）：App 不在前台时保持和电脑的连接，需要审批、回答提问或任务完成时发通知
 * （m2-android.md 3.6）。和界面共用 ConnectionManager 的连接，不另开连接，也不订阅任何会话。
 *
 * 由 [LinkController] 在用户打开 App 时启动（前台服务不能从后台启动），没有主机或者关掉通知时停止。
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hold: ConnectionManager.Hold? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // 必须在启动后几秒内调用 startForeground。再次启动时（例如刚授予通知权限）按当前状态重新显示常驻通知
        val states =
            (application as DshRemoteApp)
                .container.connections.states.value
        showForeground(online = states.values.count { it.status is LinkStatus.Online }, total = states.size)
        if (hold == null) start()
        return START_STICKY
    }

    private fun start() {
        val container = (application as DshRemoteApp).container
        val connections = container.connections
        hold = connections.hold()
        var names = emptyMap<String, String>()
        val rules = NotificationRules()
        val notifier = Notifier(this) { names[it] }

        // 规则只在主线程上串行调用
        scope.launch { container.hosts.hosts.collect { list -> names = list.associate { it.hostId to it.name } } }
        scope.launch { connections.states.collect { notifier.apply(rules.onStates(it, container.visibleSession.value)) } }
        scope.launch { connections.events.collect { notifier.apply(rules.onEvent(it, container.visibleSession.value)) } }
        scope.launch { container.visibleSession.collect { notifier.apply(rules.onVisible(it, connections.states.value)) } }
        scope.launch {
            connections.states
                .map { states -> states.values.count { it.status is LinkStatus.Online } to states.size }
                .distinctUntilChanged()
                .collect { (online, total) -> showForeground(online, total) }
        }
    }

    private fun showForeground(
        online: Int,
        total: Int,
    ) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, FOREGROUND_ID, foregroundNotification(this, online, total), type)
    }

    override fun onDestroy() {
        hold?.release()
        hold = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val FOREGROUND_ID = 1_000

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, LinkService::class.java))

        /** 常驻通知：低优先级，显示连上了几台电脑，点击打开 App。 */
        internal fun foregroundNotification(
            context: Context,
            online: Int,
            total: Int,
        ): Notification {
            Notifier.createChannels(context)
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val text =
                when {
                    total == 0 -> context.getString(R.string.notification_link_starting)
                    online == 0 -> context.getString(R.string.notification_link_none)
                    else -> context.getString(R.string.notification_link_connected, online)
                }
            return NotificationCompat
                .Builder(context, Notifier.CHANNEL_LINK)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text)
                .setContentText(context.getString(R.string.notification_link_detail))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setShowWhen(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
                .build()
        }
    }
}
