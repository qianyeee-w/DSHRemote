package io.github.qianyeeew.dshremote.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
            )
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

        /** 每个进程只调用一次（DataStore 的要求）。 */
        fun create(context: Context): SettingsRepository =
            SettingsRepository(PreferenceDataStoreFactory.create { context.applicationContext.preferencesDataStoreFile("settings") })
    }
}
