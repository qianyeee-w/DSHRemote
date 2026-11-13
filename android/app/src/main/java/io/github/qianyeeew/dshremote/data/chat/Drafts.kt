package io.github.qianyeeew.dshremote.data.chat

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 每个会话输入框里没发出去的文字（落地方案 B3）：回到会话时恢复，首页显示「草稿：…」。
 * 没有时间上限；电脑上已经删掉的会话，草稿在下次连上时一起清掉（[prune]）。
 * 首页新任务里写了一半的字不属于任何会话，不算草稿（用户确认）。
 */
interface Drafts {
    /** 一台电脑上所有会话的草稿（会话 → 文字）。 */
    fun drafts(hostId: String): Flow<Map<String, String>>

    /** 空白的文字等于删掉草稿。 */
    suspend fun save(
        hostId: String,
        sessionId: String,
        text: String,
    )

    /** 只保留 [keep] 里的会话的草稿。 */
    suspend fun prune(
        hostId: String,
        keep: Set<String>,
    )
}

/** 存在单独的 DataStore 文件 `drafts` 里，每台电脑一项 JSON。系统备份已经关掉（AndroidManifest 的 allowBackup=false）。 */
class DraftStore internal constructor(
    private val store: DataStore<Preferences>,
) : Drafts {
    override fun drafts(hostId: String): Flow<Map<String, String>> = store.data.map { decode(it[key(hostId)]) }.distinctUntilChanged()

    override suspend fun save(
        hostId: String,
        sessionId: String,
        text: String,
    ) {
        edit(hostId) { if (text.isBlank()) it - sessionId else it + (sessionId to text) }
    }

    override suspend fun prune(
        hostId: String,
        keep: Set<String>,
    ) {
        edit(hostId) { drafts -> drafts.filterKeys { it in keep } }
    }

    private suspend fun edit(
        hostId: String,
        change: (Map<String, String>) -> Map<String, String>,
    ) {
        store.edit { prefs ->
            val current = decode(prefs[key(hostId)])
            val next = change(current)
            if (next == current) return@edit
            if (next.isEmpty()) prefs.remove(key(hostId)) else prefs[key(hostId)] = json.encodeToString(serializer, next)
        }
    }

    companion object {
        private val json = Json
        private val serializer = MapSerializer(String.serializer(), String.serializer())

        private fun key(hostId: String) = stringPreferencesKey("drafts:$hostId")

        private fun decode(text: String?): Map<String, String> =
            text?.let {
                try {
                    json.decodeFromString(serializer, it)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            } ?: emptyMap()

        /** 每个进程只调用一次（DataStore 的要求）。 */
        fun create(context: Context): DraftStore =
            DraftStore(PreferenceDataStoreFactory.create { context.applicationContext.preferencesDataStoreFile("drafts") })
    }
}
