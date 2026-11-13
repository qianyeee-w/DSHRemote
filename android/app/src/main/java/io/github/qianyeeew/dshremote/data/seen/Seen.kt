package io.github.qianyeeew.dshremote.data.seen

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// ---------------------------------------------------------------------------
// 未读结果与离线补看（设计规格 7.1，落地方案 B2）。「看过」只记在这台手机上，不含任何秘密，明文保存。
// ---------------------------------------------------------------------------

/** 一台电脑上，哪些结果在这台手机上看过。除了 [lastOnlineAt]，时间都按电脑的时钟（和 `lastTurn.endedAt` 比）。 */
@Serializable
data class SeenRecord(
    /** 第一次连上这台电脑时电脑的时间：在它之前结束的结果都算看过，升级后不会一下子全部变成未读。 */
    val since: Long,
    /** 会话 → 看过的那一轮的结束时间。只保留电脑上还在的会话。 */
    val sessions: Map<String, Long> = emptyMap(),
    /** 上次在线的时间（手机的时钟），算离线了多久。 */
    val lastOnlineAt: Long? = null,
)

/**
 * 未读：最近一轮的结束时间比看过的新，而且现在不在运行（运行中和等你处理的会话在进行中卡片里）。
 * 还没有记录（从没连上过这台电脑）时都不算未读。
 */
fun SeenRecord?.isUnread(session: SessionSummary): Boolean {
    val record = this ?: return false
    if (session.status == SessionStatus.RUNNING || session.status == SessionStatus.WAITING) return false
    val ended = session.lastTurn?.endedAt ?: return false
    return ended > maxOf(record.since, record.sessions[session.sessionId] ?: Long.MIN_VALUE)
}

/** 断线一段时间后重新连上（[ReadTracker] 判断）。[offlineFrom] 是手机的时钟。 */
data class Reconnect(
    val offlineFrom: Long,
    val offlineMillis: Long,
)

/** 离线期间结束、现在还没看过的结果数。[clockOffsetMillis] 把手机的时间换成电脑的。 */
fun offlineResults(
    sessions: Collection<SessionSummary>,
    record: SeenRecord?,
    reconnect: Reconnect,
    clockOffsetMillis: Long,
): Int {
    val from = reconnect.offlineFrom + clockOffsetMillis
    return sessions.count { record.isUnread(it) && (it.lastTurn?.endedAt ?: Long.MIN_VALUE) > from }
}

/** 存「看过」的记录。测试里换成内存实现。 */
interface SeenRecords {
    val records: Flow<Map<String, SeenRecord>>

    /** 原子地修改一台电脑的记录；[change] 返回 null 表示删掉。 */
    suspend fun update(
        hostId: String,
        change: (SeenRecord?) -> SeenRecord?,
    )
}

/** 存在单独的 DataStore 文件 `seen` 里，每台电脑一项 JSON。 */
class SeenStore internal constructor(
    private val store: DataStore<Preferences>,
) : SeenRecords {
    override val records: Flow<Map<String, SeenRecord>> =
        store.data.map { prefs ->
            prefs
                .asMap()
                .mapNotNull { (key, value) ->
                    val hostId = key.name.removePrefix(PREFIX).takeIf { key.name.startsWith(PREFIX) } ?: return@mapNotNull null
                    decode(value as? String)?.let { hostId to it }
                }.toMap()
        }

    override suspend fun update(
        hostId: String,
        change: (SeenRecord?) -> SeenRecord?,
    ) {
        store.edit { prefs ->
            val key = stringPreferencesKey(PREFIX + hostId)
            val next = change(decode(prefs[key]))
            if (next == null) prefs.remove(key) else prefs[key] = json.encodeToString(SeenRecord.serializer(), next)
        }
    }

    companion object {
        private const val PREFIX = "seen:"
        private val json = Json { ignoreUnknownKeys = true }

        /** 读不懂的记录（格式变了）当作没有：最多是这台电脑的结果重新从「现在」算起。 */
        private fun decode(text: String?): SeenRecord? =
            text?.let {
                try {
                    json.decodeFromString(SeenRecord.serializer(), it)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        /** 每个进程只调用一次（DataStore 的要求）。 */
        fun create(context: Context): SeenStore =
            SeenStore(PreferenceDataStoreFactory.create { context.applicationContext.preferencesDataStoreFile("seen") })
    }
}
