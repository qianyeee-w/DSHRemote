package io.github.qianyeeew.dshremote.devserver

import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PluginApi
import io.github.qianyeeew.dshremote.data.api.PromptMode
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.host.Credentials
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.live.Backoff
import io.github.qianyeeew.dshremote.data.live.ConnectionManager
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.LiveEvent
import io.github.qianyeeew.dshremote.data.live.PairingReason
import io.github.qianyeeew.dshremote.data.live.SessionEvent
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 实时连接对真实 dev-server 的集成测试（m2-android.md T6）：流式消息、重启 DSH 后自动恢复、撤销后停止重连。
 *
 * 只由 `./gradlew devServerTest` 运行。三个测试按名字顺序执行：最后一个会撤销这台测试设备。
 * 第二个测试会重启 dev-server 中的 DSH（不影响端口 3190 上的 dsh-web.service）。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveDevServerTest {
    @Test
    fun t1_streamDeliversStateAndMessages() =
        runBlocking<Unit> {
            awaitStatus { it is LinkStatus.Online }
            val created = api.createSession()
            sessionId = created
            // 新会话通过 session.updated 出现在状态中
            awaitState("新会话出现在状态中") { created in it.sessions }

            val events = subscribe(created)
            assertEquals(SessionEvent.Resync(created), events.next())

            api.prompt(created, OutgoingMessage("STREAM ${System.nanoTime()}", PromptMode.QUEUE))
            val drafts = mutableSetOf<String>()
            val revs = mutableMapOf<String, Long>()
            var final: SessionEvent.Upsert? = null
            while (final == null) {
                val event = events.next(timeoutMillis = 30_000) as? SessionEvent.Upsert ?: continue
                revs[event.message.id]?.let { assertTrue("同一条消息的 rev 递增", event.rev > it) }
                revs[event.message.id] = event.rev
                if (event.message.draft) {
                    assertTrue(event.message.id.startsWith("draft:"))
                    drafts += event.message.id
                } else if (event.message.role == MessageRole.ASSISTANT) {
                    final = event
                }
            }
            assertTrue("STREAM 应该有流式草稿", drafts.isNotEmpty())
            assertTrue("正式消息替换掉草稿", final.replaces in drafts)
            assertEquals(listOf(TextPart(STREAM_REPLY)), final.message.parts.filterIsInstance<TextPart>())
            events.cancel()

            // 运行结束并持续空闲 8 秒后，插件发 session.idle（任务完成通知用）
            withTimeout(40_000) { while (liveEvents.none { it is LiveEvent.SessionIdle && it.sessionId == created }) delay(200) }
        }

    @Test
    fun t2_recoversAfterDshRestartWithoutRepairing() =
        runBlocking<Unit> {
            val id = checkNotNull(sessionId) { "t1 没有成功" }
            awaitStatus { it is LinkStatus.Online }
            val events = subscribe(id)
            assertEquals(SessionEvent.Resync(id), events.next())

            val statuses = CopyOnWriteArrayList<LinkStatus>()
            val recorder = scope.launch { manager.states.collect { s -> s[HOST_ID]?.status?.let(statuses::add) } }
            withContext(Dispatchers.IO) { DevServer.restartDsh() }
            awaitStatus(timeoutMillis = 60_000) { it is LinkStatus.Online && statuses.any { s -> s !is LinkStatus.Online } }
            recorder.cancel()
            assertFalse("重启后不需要重新配对", statuses.any { it is LinkStatus.NeedsPairing || it.isTerminal })

            // 重连后重新订阅，让界面重新读取历史；hello 中仍然有这个会话
            assertEquals(SessionEvent.Resync(id), events.next(timeoutMillis = 30_000))
            assertTrue(
                id in
                    manager.states.value
                        .getValue(HOST_ID)
                        .sessions,
            )
            assertTrue(api.messages(id).messages.isNotEmpty())
            events.cancel()
        }

    @Test
    fun t3_revokedDeviceStopsReconnecting() =
        runBlocking<Unit> {
            awaitStatus { it is LinkStatus.Online }
            DevServer.revoke(deviceId)
            revoked = true
            awaitStatus { it == LinkStatus.NeedsPairing(PairingReason.TOKEN_REVOKED) }
            val before = attempts.get()
            delay(3_000)
            assertEquals("撤销后不再重连", before, attempts.get())
            assertEquals(
                LinkStatus.NeedsPairing(PairingReason.TOKEN_REVOKED),
                manager.states.value
                    .getValue(HOST_ID)
                    .status,
            )
        }

    private class Events(
        private val channel: Channel<SessionEvent>,
        private val job: kotlinx.coroutines.Job,
    ) {
        suspend fun next(timeoutMillis: Long = 10_000): SessionEvent = withTimeout(timeoutMillis) { channel.receive() }

        fun cancel() = job.cancel()
    }

    companion object {
        private const val HOST_ID = "dev"

        /** 和 plugin/test/contract/fake-llm.mjs 的 STREAM_REPLY 一致。 */
        private const val STREAM_REPLY = "这是一段分块慢慢输出的回复，用来验证手机上能实时看到模型正在输出的内容。"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val attempts = AtomicInteger()
        private val liveEvents = CopyOnWriteArrayList<LiveEvent>()
        private lateinit var manager: ConnectionManager
        private lateinit var api: PluginApi
        private lateinit var deviceId: String
        private var sessionId: String? = null
        private var revoked = false

        @BeforeClass
        @JvmStatic
        fun connect() =
            runBlocking<Unit> {
                val status = DevServer.status()
                val paired = DevServer.pair(status, "LiveDevServerTest")
                deviceId = paired.deviceId
                val factory = ClientFactory()
                api = PluginApi(factory.forPin(status.pin), status.address, paired.token)
                val host = Host(HOST_ID, "dev-server", listOf(status.address), null, paired.deviceId, Credentials(status.pin, paired.token))
                manager =
                    ConnectionManager(
                        hosts = MutableStateFlow(listOf(host)),
                        scope = scope,
                        clientFor = {
                            attempts.incrementAndGet()
                            factory.forPin(it)
                        },
                        markGoodAddress = { _, _ -> },
                        log = { println("[live] $it") },
                        newBackoff = { Backoff(initialMillis = 200, maxMillis = 2_000) },
                    )
                scope.launch { manager.events.collect { liveEvents += it } }
                manager.hold()
            }

        @AfterClass
        @JvmStatic
        fun disconnect() =
            runBlocking<Unit> {
                scope.cancel()
                if (!revoked && ::api.isInitialized) api.deleteMe()
            }

        private suspend fun awaitState(
            what: String,
            timeoutMillis: Long = 20_000,
            check: (HostLiveState) -> Boolean,
        ): HostLiveState {
            val state =
                kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
                    manager.states.first { it[HOST_ID]?.let(check) == true }
                }
            assertNotNull("等待超时：$what（当前状态 ${manager.states.value[HOST_ID]?.status}）", state)
            return state!!.getValue(HOST_ID)
        }

        private suspend fun awaitStatus(
            timeoutMillis: Long = 20_000,
            check: (LinkStatus) -> Boolean,
        ) = awaitState("连接状态", timeoutMillis) { check(it.status) }

        private fun subscribe(sessionId: String): Events {
            val channel = Channel<SessionEvent>(Channel.UNLIMITED)
            val connection = checkNotNull(manager.connection(HOST_ID))
            return Events(channel, scope.launch { connection.subscribe(sessionId).collect { channel.send(it) } })
        }
    }
}
