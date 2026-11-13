package io.github.qianyeeew.dshremote.data.seen

import io.github.qianyeeew.dshremote.data.api.LastTurn
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.TurnResult
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.OfflineReason
import io.github.qianyeeew.dshremote.service.VisibleSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ReadTracker]：起点、正在看的会话算看过、上次在线的时间、重连提示（落地方案 B2）。 */
class ReadTrackerTest {
    private val online = LinkStatus.Online(HostAddress("192.168.1.5", 3290))
    private val offline = LinkStatus.Offline(OfflineReason.UNREACHABLE, 1_000)
    private val store = MemorySeen()
    private val states = MutableStateFlow<Map<String, HostLiveState>>(emptyMap())
    private val visible = MutableStateFlow<VisibleSession?>(null)
    private var clock = 1_000_000L

    @Test
    fun firstConnectionSetsTheStartingPointOnTheComputerClock() =
        runTest {
            tracker()
            states.value = mapOf("h1" to HostLiveState(online, clockOffsetMillis = 500))
            runCurrent()
            assertEquals(SeenRecord(since = 1_000_500, lastOnlineAt = 1_000_000), store.map["h1"])
            assertNull(tracker.reconnects.value["h1"])
        }

    @Test
    fun theSessionBeingViewedIsSeenIncludingTurnsThatEndWhileItIsOpen() =
        runTest {
            store.seed("h1", SeenRecord(since = 0, lastOnlineAt = clock))
            tracker()
            states.value = mapOf("h1" to state(online, ended = 50))
            visible.value = VisibleSession("h1", "s1")
            runCurrent()
            assertEquals(mapOf("s1" to 50L), store.map["h1"]?.sessions)
            states.value = mapOf("h1" to state(online, ended = 80))
            runCurrent()
            assertEquals(mapOf("s1" to 80L), store.map["h1"]?.sessions)
            // 离开会话页后结束的一轮不算看过
            visible.value = null
            states.value = mapOf("h1" to state(online, ended = 120))
            runCurrent()
            assertEquals(mapOf("s1" to 80L), store.map["h1"]?.sessions)
        }

    @Test
    fun reconnectAfterFiveMinutesGivesANoticeUntilASessionIsOpened() =
        runTest {
            store.seed("h1", SeenRecord(since = 0, lastOnlineAt = clock))
            tracker()
            states.value = mapOf("h1" to state(online))
            runCurrent()
            states.value = mapOf("h1" to state(offline))
            clock += 60_000
            runCurrent()
            assertEquals(clock, store.map["h1"]?.lastOnlineAt)
            // 离线 4 分钟又连上：不提示
            clock += 4 * 60_000
            states.value = mapOf("h1" to state(online))
            runCurrent()
            assertNull(tracker.reconnects.value["h1"])
            // 离线 2 小时
            states.value = mapOf("h1" to state(offline))
            runCurrent()
            val from = clock
            clock += 2 * 3_600_000
            states.value = mapOf("h1" to state(online))
            runCurrent()
            assertEquals(Reconnect(from, 2 * 3_600_000), tracker.reconnects.value["h1"])
            visible.value = VisibleSession("h1", "s1")
            runCurrent()
            assertNull(tracker.reconnects.value["h1"])
        }

    /** App 被杀掉后重新打开：用最后一次心跳记下的时间算离线了多久。 */
    @Test
    fun heartbeatKeepsTheLastOnlineTimeAcrossProcessDeath() =
        runTest {
            store.seed("h1", SeenRecord(since = 0))
            tracker()
            states.value = mapOf("h1" to state(online))
            runCurrent()
            clock += ReadTracker.HEARTBEAT_MILLIS
            advanceTimeBy(ReadTracker.HEARTBEAT_MILLIS + 1)
            assertEquals(clock, store.map["h1"]?.lastOnlineAt)
            // 新进程：没有经过「断开」，直接从上次心跳算
            val lastBeat = clock
            clock += 30 * 60_000
            val again = ReadTracker(backgroundScope, store, MutableStateFlow(mapOf("h1" to state(online))), visible, { clock })
            again.start()
            runCurrent()
            assertEquals(Reconnect(lastBeat, 30 * 60_000), again.reconnects.value["h1"])
            again.dismiss("h1")
            assertNull(again.reconnects.value["h1"])
        }

    @Test
    fun sessionsDeletedOnTheComputerAreForgottenWhenItConnects() =
        runTest {
            store.seed("h1", SeenRecord(since = 0, sessions = mapOf("s1" to 1, "gone" to 2), lastOnlineAt = clock))
            tracker()
            states.value = mapOf("h1" to state(online))
            runCurrent()
            assertEquals(mapOf("s1" to 1L), store.map["h1"]?.sessions)
        }

    private lateinit var tracker: ReadTracker

    private fun TestScope.tracker() {
        tracker = ReadTracker(backgroundScope, store, states, visible, { clock })
        tracker.start()
        runCurrent()
    }

    private fun state(
        status: LinkStatus,
        ended: Long? = null,
    ) = HostLiveState(
        status,
        sessions =
            mapOf(
                "s1" to
                    SessionSummary(
                        "s1",
                        status = SessionStatus.IDLE,
                        updatedAt = 0,
                        lastTurn = ended?.let { LastTurn(TurnResult.COMPLETED, it) },
                    ),
            ),
    )

    /** 内存里的记录：[map] 读，[seed] 准备初始值。 */
    private class MemorySeen : SeenRecords {
        private val flow = MutableStateFlow<Map<String, SeenRecord>>(emptyMap())
        val map: Map<String, SeenRecord> get() = flow.value

        override val records = flow

        fun seed(
            hostId: String,
            record: SeenRecord,
        ) {
            flow.update { it + (hostId to record) }
        }

        override suspend fun update(
            hostId: String,
            change: (SeenRecord?) -> SeenRecord?,
        ) {
            flow.update { current ->
                val next = change(current[hostId])
                if (next == null) current - hostId else current + (hostId to next)
            }
        }
    }
}
