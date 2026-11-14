package io.github.qianyeeew.dshremote.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App 的设置（不含任何秘密，明文保存）。 */
data class Settings(
    /** 通知开关：打开时，有主机就运行前台服务保持连接（m2-android.md 3.6）。 */
    val notificationsEnabled: Boolean = true,
    /** 已经申请过通知权限（Android 13 起只在第一次启动时主动申请一次）。 */
    val notificationPermissionAsked: Boolean = false,
    /** 用户关掉了首页上「后台运行」的提示。 */
    val backgroundHintDismissed: Boolean = false,
    /** 上次在首页用的电脑（App 本地的 hostId）。配对了多台电脑时首页直接显示它。 */
    val lastHostId: String? = null,
)

class SettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
) {
    val settings: Flow<Settings> =
        store.data.map { p ->
            Settings(
                notificationsEnabled = p[NOTIFICATIONS] ?: true,
                notificationPermissionAsked = p[PERMISSION_ASKED] ?: false,
                backgroundHintDismissed = p[HINT_DISMISSED] ?: false,
                lastHostId = p[LAST_HOST],
            )
        }

    /** 首页最近列表的分组方式（所有电脑共用）：项目、日期或状态的名字，null 是还没选过（按项目分组）。 */
    val homeGrouping: Flow<String?> = store.data.map { it[GROUPING] }

    suspend fun setHomeGrouping(grouping: String) {
        store.edit { it[GROUPING] = grouping }
    }

    /** 新任务上一次用的项目（每台电脑各自记）：项目 id，空串是「不指定项目」，null 是还没用过。 */
    fun lastProject(hostId: String): Flow<String?> = store.data.map { it[projectKey(hostId)] }

    suspend fun setLastProject(
        hostId: String,
        workspaceId: String,
    ) {
        store.edit { it[projectKey(hostId)] = workspaceId }
    }

    suspend fun setLastHost(hostId: String) {
        store.edit { it[LAST_HOST] = hostId }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) = set(NOTIFICATIONS, enabled)

    suspend fun markNotificationPermissionAsked() = set(PERMISSION_ASKED, true)

    suspend fun dismissBackgroundHint() = set(HINT_DISMISSED, true)

    private suspend fun set(
        key: Preferences.Key<Boolean>,
        value: Boolean,
    ) {
        store.edit { it[key] = value }
    }

    companion object {
        private val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        private val PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")
        private val HINT_DISMISSED = booleanPreferencesKey("background_hint_dismissed")
        private val LAST_HOST = stringPreferencesKey("last_host_id")
        private val GROUPING = stringPreferencesKey("home_grouping")

        private fun projectKey(hostId: String) = stringPreferencesKey("new_task_project:$hostId")

        /** 每个进程只调用一次（DataStore 的要求）。 */
        fun create(context: Context): SettingsRepository =
            SettingsRepository(PreferenceDataStoreFactory.create { context.applicationContext.preferencesDataStoreFile("settings") })
    }
}
