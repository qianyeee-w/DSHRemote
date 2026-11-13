package io.github.qianyeeew.dshremote.data.seen

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.qianyeeew.dshremote.data.api.LastTurn
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.TurnResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 未读的判断和「看过」的存储（落地方案 B2）。 */
class SeenTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun unreadMeansANewerResultThatIsNotRunning() {
        val record = SeenRecord(since = 100, sessions = mapOf("seen" to 300))
        // 起点之前结束的都算看过（升级后不会全部变成未读）
        assertFalse(record.isUnread(session("old", endedAt = 90)))
        assertTrue(record.isUnread(session("new", endedAt = 150)))
        // 看过的那一轮之后又结束了一轮
        assertFalse(record.isUnread(session("seen", endedAt = 300)))
        assertTrue(record.isUnread(session("seen", endedAt = 301)))
        // 运行中和等你处理的在进行中卡片里，不算未读
        assertFalse(record.isUnread(session("new", endedAt = 150, status = SessionStatus.RUNNING)))
        assertFalse(record.isUnread(session("new", endedAt = 150, status = SessionStatus.WAITING)))
        // 没有结束过的一轮、还没连上过这台电脑
        assertFalse(record.isUnread(session("none", endedAt = null)))
        assertFalse((null as SeenRecord?).isUnread(session("new", endedAt = 150)))
    }

    @Test
    fun offlineResultsCountOnlyUnreadResultsThatEndedWhileOffline() {
        val record = SeenRecord(since = 0, sessions = mapOf("read" to 2_000))
        val sessions =
            listOf(
                session("before", endedAt = 900),
                session("during", endedAt = 1_500),
                session("during-2", endedAt = 1_600),
                session("read", endedAt = 2_000),
            )
        // 手机的 1_000 是电脑的 1_100
        assertEquals(2, offlineResults(sessions, record, Reconnect(offlineFrom = 1_000, offlineMillis = 10_000), clockOffsetMillis = 100))
        assertEquals(1, offlineResults(sessions, record, Reconnect(offlineFrom = 1_000, offlineMillis = 10_000), clockOffsetMillis = 550))
    }

    @Test
    fun storeKeepsOneRecordPerHostAndDeletesOnNull() =
        runTest {
            val store =
                SeenStore(
                    PreferenceDataStoreFactory.create(
                        scope = backgroundScope,
                    ) { folder.newFile("seen.preferences_pb").also { it.delete() } },
                )
            assertEquals(emptyMap<String, SeenRecord>(), store.records.first())
            store.update("h1") { SeenRecord(since = 10) }
            store.update("h2") { SeenRecord(since = 20, lastOnlineAt = 5) }
            store.update("h1") { it?.copy(sessions = it.sessions + ("s1" to 30L)) }
            assertEquals(
                mapOf("h1" to SeenRecord(since = 10, sessions = mapOf("s1" to 30)), "h2" to SeenRecord(since = 20, lastOnlineAt = 5)),
                store.records.first(),
            )
            store.update("h2") { null }
            assertEquals(setOf("h1"), store.records.first().keys)
        }

    private fun session(
        id: String,
        endedAt: Long?,
        status: SessionStatus = SessionStatus.IDLE,
    ) = SessionSummary(id, status = status, updatedAt = endedAt ?: 0, lastTurn = endedAt?.let { LastTurn(TurnResult.COMPLETED, it) })
}
