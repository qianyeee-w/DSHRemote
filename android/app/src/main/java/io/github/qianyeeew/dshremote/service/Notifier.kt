package io.github.qianyeeew.dshremote.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.qianyeeew.dshremote.MainActivity
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import java.util.UUID

/** 点通知后要打开的页面。 */
sealed interface NotificationTarget {
    data class AttentionPage(
        val hostId: String,
        val attentionId: String,
    ) : NotificationTarget

    data class SessionPage(
        val hostId: String,
        val sessionId: String,
    ) : NotificationTarget
}

/**
 * 点通知后打开哪个页面，只记在这个进程的内存里，通知的 Intent 中只有一个随机的令牌（m2-android.md 3.6）。
 *
 * MainActivity 是对外开放的（桌面启动入口），如果把 hostId、attentionId 直接放进 Intent，其他 App 可以伪造同样的 Intent
 * 把用户带到处理页面。用令牌时，伪造的 Intent 拿不到有效的令牌，会被忽略。进程被杀后令牌失效，点通知只打开首页。
 */
object NotificationTargets {
    const val ACTION_OPEN = "io.github.qianyeeew.dshremote.action.OPEN_NOTIFICATION"
    const val EXTRA_TOKEN = "token"
    private const val MAX = 256

    private val byToken = LinkedHashMap<String, NotificationTarget>()
    private val byTarget = HashMap<NotificationTarget, String>()

    @Synchronized
    fun tokenFor(target: NotificationTarget): String =
        byTarget.getOrPut(target) {
            UUID.randomUUID().toString().also { token ->
                byToken[token] = target
                if (byToken.size > MAX) {
                    val oldest = byToken.keys.first()
                    byTarget.remove(byToken.remove(oldest))
                }
            }
        }

    /** 令牌对应的页面；不认识的令牌（伪造的，或者进程重启前的）返回 null。 */
    @Synchronized
    fun resolve(token: String?): NotificationTarget? = token?.let { byToken[it] }
}

/**
 * 通知渠道和通知的样式（m2-android.md 3.6）。按 [NotificationRules] 给出的动作发出和撤回通知。
 *
 * 锁屏上只显示「需要你处理」之类的标题，不显示命令和问题的内容。没有通知权限时什么也不做。
 */
class Notifier(
    private val context: Context,
    /** hostId → 电脑名（标题中使用）。 */
    private val hostName: (String) -> String?,
) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannels(context)
    }

    fun apply(actions: List<NotificationAction>) = actions.forEach(::apply)

    private fun apply(action: NotificationAction) {
        when (action) {
            is NotificationAction.PostAttention -> post(attentionTag(action.hostId, action.attention.attentionId), attention(action))
            is NotificationAction.CancelAttention -> manager.cancel(attentionTag(action.hostId, action.attentionId), ID)
            is NotificationAction.PostTaskDone -> post(taskTag(action.hostId, action.sessionId), taskDone(action))
            is NotificationAction.CancelTaskDone -> manager.cancel(taskTag(action.hostId, action.sessionId), ID)
        }
    }

    private fun post(
        tag: String,
        notification: Notification,
    ) {
        if (!canPost(context)) return
        try {
            manager.notify(tag, ID, notification)
        } catch (_: SecurityException) {
            // 权限刚好在这一刻被用户收回
        }
    }

    private fun attention(action: NotificationAction.PostAttention): Notification {
        val attention = action.attention
        val title = withHost(titleOf(attention), action.hostId)
        val text = detailOf(attention)
        val public =
            NotificationCompat
                .Builder(context, CHANNEL_ATTENTION)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_attention_public))
                .build()
        return NotificationCompat
            .Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setWhen(attention.createdAt)
            .setContentIntent(open(NotificationTarget.AttentionPage(action.hostId, attention.attentionId)))
            .build()
    }

    private fun taskDone(action: NotificationAction.PostTaskDone): Notification =
        NotificationCompat
            .Builder(context, CHANNEL_TASK_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(withHost(context.getString(R.string.notification_task_done), action.hostId))
            .setContentText(action.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.sessions_untitled))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(open(NotificationTarget.SessionPage(action.hostId, action.sessionId)))
            .build()

    private fun titleOf(attention: Attention): String =
        context.getString(
            if (attention.kind == AttentionKind.QUESTION) R.string.attention_question_title else R.string.attention_approval_title,
        )

    private fun detailOf(attention: Attention): String =
        attention.approval?.let { it.summary ?: context.getString(R.string.attention_tool, it.toolName) }
            ?: attention.question
                ?.questions
                ?.firstOrNull()
                ?.question
            ?: context.getString(R.string.notification_attention_public)

    private fun withHost(
        title: String,
        hostId: String,
    ): String = hostName(hostId)?.let { context.getString(R.string.notification_title_with_host, title, it) } ?: title

    private fun open(target: NotificationTarget): PendingIntent {
        val token = NotificationTargets.tokenFor(target)
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(NotificationTargets.ACTION_OPEN)
                .putExtra(NotificationTargets.EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // 显式 Intent，不可修改（FLAG_IMMUTABLE），其他 App 拿到也改不了目标
        return PendingIntent.getActivity(
            context,
            token.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ATTENTION = "attention"
        const val CHANNEL_TASK_DONE = "task_done"
        const val CHANNEL_LINK = "link"
        private const val ID = 1

        private fun attentionTag(
            hostId: String,
            attentionId: String,
        ) = "attention:$hostId:$attentionId"

        private fun taskTag(
            hostId: String,
            sessionId: String,
        ) = "task:$hostId:$sessionId"

        /** Android 13 起发通知需要权限。 */
        fun canPost(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannels(
                listOf(
                    NotificationChannel(
                        CHANNEL_ATTENTION,
                        context.getString(R.string.channel_attention),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = context.getString(R.string.channel_attention_description) },
                    NotificationChannel(
                        CHANNEL_TASK_DONE,
                        context.getString(R.string.channel_task_done),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = context.getString(R.string.channel_task_done_description) },
                    NotificationChannel(
                        CHANNEL_LINK,
                        context.getString(R.string.channel_link),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = context.getString(R.string.channel_link_description)
                        setShowBadge(false)
                    },
                ),
            )
        }
    }
}
