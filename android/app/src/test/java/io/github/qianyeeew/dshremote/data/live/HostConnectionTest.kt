package io.github.qianyeeew.dshremote.data.live

import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.host.Credentials
import io.github.qianyeeew.dshremote.data.host.DeviceToken
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.FakePlugin.Companion.approval
import io.github.qianyeeew.dshremote.data.live.FakePlugin.Companion.event
import io.github.qianyeeew.dshremote.data.live.FakePlugin.Companion.message
import io.github.qianyeeew.dshremote.data.live.FakePlugin.Companion.session
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * HostConnection 的连接状态机（m2-android.md 3.3、protocol.md 第 6 节）。服务器是 [FakePlugin]：
 * 带自签证书的 MockWebServer，客户端来自 ClientFactory，和正式使用时一样经过指纹校验。
 */
class HostConnectionTest {
    private val plugin = FakePlugin()
    private val others = mutableListOf<FakePlugin>()
    private val tokenText = "tok3n_SECRET-abcdefghijklmnopqrstuvwxyz0120"
    private val token = DeviceToken.parse(tokenText)!!
    private val pin = Fingerprint.of(plugin.cert.certificate)
    private val factory = ClientFactory()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logs = CopyOnWriteArrayList<String>()
    private val goodAddresses = CopyOnWriteArrayList<HostAddress>()

    /** 每次连接尝试（connectOnce）创建一次客户端，用它来数连接尝试的次数。 */
    private val attempts = AtomicInteger()
    private val liveEvents = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    private val connections = mutableListOf<HostConnection>()

    @After
    fun tearDown() {
        connections.forEach { it.stop() }
        scope.cancel()
        plugin.close()
        others.forEach { it.close() }
        // 红线第 5 条：任何日志中都没有 token
        logs.forEach { assertFalse("日志中不能有 token：$it", it.contains(tokenText)) }
    }

    // ---------------------------------------------------------------- 连接和 hello

    @Test
    fun connectsWithTokenOnlyOnStreamAndGoesOnlineOnHello() =
        runBlocking<Unit> {
            val connection = connection(host())
            connection.start()
            val server = plugin.nextConnection()
            assertEquals(LinkStatus.Connecting, connection.state.value.status)

            // /v1/info 不带 token；WebSocket 握手带 token，不带 Origin
            val info = plugin.requests.first { it.url.encodedPath == "/v1/info" }
            assertNull(info.headers["Authorization"])
            assertEquals("Bearer $tokenText", server.request.headers["Authorization"])
            assertNull("不能发送 Origin", server.request.headers["Origin"])

            server.hello(sessions = listOf(session("s1"), session("s2", status = "running")), attention = listOf(approval("a1")))
            val state = connection.awaitStatus { it is LinkStatus.Online }
            assertEquals(LinkStatus.Online(plugin.address), state.status)
            assertEquals(setOf("s1", "s2"), state.sessions.keys)
            assertEquals(setOf("a1"), state.attention.keys)
            waitUntil { goodAddresses == listOf(plugin.address) }
        }

    @Test
    fun eventsBeforeHelloAreIgnored() =
        runBlocking<Unit> {
            val connection = connection(host())
            connection.start()
            val server = plugin.nextConnection()
            server.send(event("session.updated", """"session":${session("early")}"""))
            server.hello(sessions = listOf(session("s1")))
            val state = connection.awaitStatus { it is LinkStatus.Online }
            assertEquals(setOf("s1"), state.sessions.keys)
        }

    @Test
    fun helloReplacesStateAfterReconnect() =
        runBlocking<Unit> {
            val connection = connection(host())
            connection.start()
            val first = plugin.nextConnection()
            first.hello(sessions = listOf(session("s1"), session("s2")), attention = listOf(approval("a1")))
            connection.awaitStatus { it is LinkStatus.Online }
            first.send(event("session.updated", """"session":${session("s3")}"""))
            waitUntil { "s3" in connection.state.value.sessions }

            first.kill()
            // 断开期间保留最后的状态
            connection.awaitStatus { it !is LinkStatus.Online }
            assertEquals(setOf("s1", "s2", "s3"), connection.state.value.sessions.keys)

            // 断线期间 s1、s3 被删除，a1 被处理掉：新的 hello 整体覆盖，不和旧状态合并
            val second = plugin.nextConnection()
            second.hello(sessions = listOf(session("s2")))
            val state = connection.awaitStatus { it is LinkStatus.Online }
            assertEquals(setOf("s2"), state.sessions.keys)
            assertTrue(state.attention.isEmpty())
        }

    @Test
    fun incrementalEventsUpdateStateAndDuplicatesAreIgnored() =
        runBlocking<Unit> {
            val received = CopyOnWriteArrayList<LiveEvent>()
            scope.launch { liveEvents.collect { received += it } }
            val connection = connection(host())
            connection.start()
            val server = plugin.nextConnection()
            server.hello(sessions = listOf(session("s1")))
            connection.awaitStatus { it is LinkStatus.Online }

            server.send(event("session.updated", """"session":${session("s1", status = "running")}"""))
            server.send(event("session.updated", """"session":${session("s2")}"""))
            server.send(event("session.removed", """"sessionId":"s2""""))
            val requested = event("attention.requested", """"attention":${approval("a1")}""", eventId = "e-a1")
            server.send(requested)
            server.send(event("attention.resolved", """"attentionId":"a1","sessionId":"s1","by":"other""""))
            // 同一个 eventId 再来一次：忽略，不会让 a1 重新出现
            server.send(requested)
            server.send(event("session.idle", """"sessionId":"s1","title":"完成""""))

            waitUntil { received.any { it is LiveEvent.SessionIdle } }
            val state = connection.state.value
            assertEquals(setOf("s1"), state.sessions.keys)
            assertEquals(
                io.github.qianyeeew.dshremote.data.api.SessionStatus.RUNNING,
                state.sessions
                    .getValue("s1")
                    .status,
            )
            assertTrue(state.attention.isEmpty())
            assertEquals(
                listOf("AttentionRequested", "AttentionResolved", "SessionIdle"),
                received.map { it.javaClass.simpleName },
            )
            val resolved = received[1] as LiveEvent.AttentionResolved
            assertEquals(ResolvedBy.OTHER, resolved.by)
            assertEquals("h1", resolved.hostId)
            assertEquals(LiveEvent.SessionIdle("h1", "s1", "完成"), received[2])
        }

    @Test
    fun malformedAndUnknownMessagesAreIgnoredWithoutDisconnecting() =
        runBlocking<Unit> {
            val connection = connection(host())
            connection.start()
            val server = plugin.nextConnection()
            server.hello()
            connection.awaitStatus { it is LinkStatus.Online }
            server.send("not json")
            server.send(event("future.thing", """"x":1"""))
            server.send(event("session.updated", """"session":{"sessionId":"broken"}"""))
            server.send(event("error", """"code":"BAD_REQUEST","message":"m""""))
            server.send(event("session.updated", """"session":${session("s9")}"""))
            waitUntil { "s9" in connection.state.value.sessions }
            assertEquals(LinkStatus.Online(plugin.address), connection.state.value.status)
            assertEquals(1, attempts.get())
        }

    // ---------------------------------------------------------------- 停止重连（安全相关的反向用例）

    @Test
    fun handshake401StopsReconnecting() =
        runBlocking<Unit> {
            plugin.handshake = { FakePlugin.error(401, "TOKEN_INVALID") }
            val connection = connection(host())
            connection.start()
            connection.awaitStatus { it is LinkStatus.NeedsPairing }
            assertEquals(LinkStatus.NeedsPairing(PairingReason.TOKEN_REVOKED), connection.state.value.status)

            // 退避间隔是几十毫秒：等足够久，确认没有再试；网络变化也不会让它重试
            connection.retryNow(afterNetworkChange = true)
            delay(400)
            assertEquals(1, attempts.get())
            assertEquals(1, plugin.streamRequests.size)
        }

    @Test
    fun close4401StopsReconnecting() =
        runBlocking<Unit> {
            val connection = connection(host())
            connection.start()
            val server = plugin.nextConnection()
            server.hello()
            connection.awaitStatus { it is LinkStatus.Online }
            server.close(HostConnection.CLOSE_REVOKED)
            connection.awaitStatus { it is LinkStatus.NeedsPairing }
            assertEquals(LinkStatus.NeedsPairing(PairingReason.TOKEN_REVOKED), connection.state.value.status)
            connection.retryNow(afterNetworkChange = true)
            plugin.assertNoNewConnection(400)
            assertEquals(1, attempts.get())
        }

    @Test
    fun fingerprintMismatchStopsBeforeAnyRequest() =
        runBlocking<Unit> {
            val impostor = other()
            val connection = connection(host(addresses = listOf(impostor.address)))
            connection.start()
            connection.awaitStatus { it == LinkStatus.FingerprintMismatch }
            delay(300)
            assertEquals("指纹不匹配后不再重连", 1, attempts.get())
            // TLS 握手就失败了：服务器没有收到任何 HTTP 请求，token 不可能被发出
            assertTrue(impostor.requests.isEmpty())

            // 换了网络后再试一次（同一个地址可能已经指向别的设备），仍然拒绝
            connection.retryNow(afterNetworkChange = true)
            waitUntil { attempts.get() == 2 }
            connection.awaitStatus { it == LinkStatus.FingerprintMismatch }
            assertTrue(impostor.requests.isEmpty())
        }

    @Test
    fun missingCredentialsNeedPairingWithoutAnyConnection() =
        runBlocking<Unit> {
            val connection = connection(host().copy(credentials = null))
            connection.start()
            connection.awaitStatus { it is LinkStatus.NeedsPairing }
            assertEquals(LinkStatus.NeedsPairing(PairingReason.CREDENTIALS_LOST), connection.state.value.status)
            assertEquals(0, attempts.get())
            assertTrue(plugin.requests.isEmpty())
        }

    @Test
    fun unknownProtocolInInfoMeansAppTooOld() =
        runBlocking<Unit> {
            plugin.info = FakePlugin.info(protocol = 2)
            val connection = connection(host())
            connection.start()
            connection.awaitStatus { it is LinkStatus.AppTooOld }
            assertEquals(LinkStatus.AppTooOld(2), connection.state.value.status)
            assertTrue("不打开 WebSocket", plugin.streamRequests.isEmpty())
        }

    @Test
    fun unknownProtocolInHelloMeansAppTooOld() =
        runBlocking<Unit> {
            val connection = connection(host())
            connection.start()
            plugin.nextConnection().hello(protocol = 2)
            connection.awaitStatus { it is LinkStatus.AppTooOld }
            plugin.assertNoNewConnection()
        }

    // ---------------------------------------------------------------- 按退避重连

    @Test
    fun close1013RetriesAsDshUnavailable() =
        runBlocking<Unit> {
            val connection = connection(host(), backoff = Backoff(initialMillis = 400, maxMillis = 400))
            connection.start()
            val first = plugin.nextConnection()
            first.send(event("error", """"code":"DSH_UNAVAILABLE","message":"m""""))
            first.close(HostConnection.CLOSE_TRY_AGAIN)
            val offline = connection.awaitStatus { it is LinkStatus.Offline }.status as LinkStatus.Offline
            assertEquals(OfflineReason.DSH_UNAVAILABLE, offline.reason)
            assertTrue(offline.retryInMillis in 200..400)

            plugin.nextConnection().hello()
            connection.awaitStatus { it is LinkStatus.Online }
            assertTrue(logs.any { it.contains("DSH_UNAVAILABLE") })
        }

    @Test
    fun close1008IsLoggedAsAppBugAndRetried() =
        runBlocking<Unit> {
            val connection = connection(host())
            connection.start()
            val first = plugin.nextConnection()
            first.hello()
            connection.awaitStatus { it is LinkStatus.Online }
            first.close(HostConnection.CLOSE_POLICY)
            plugin.nextConnection().hello()
            waitUntil { attempts.get() == 2 && connection.state.value.status is LinkStatus.Online }
            assertTrue(logs.any { it.contains("1008") })
        }

    @Test
    fun handshake429IsRetriedNotFatal() =
        runBlocking<Unit> {
            val connection = connection(host(), backoff = Backoff(initialMillis = 400, maxMillis = 400))
            val handshakes = AtomicInteger()
            plugin.handshake = { if (handshakes.incrementAndGet() == 1) FakePlugin.error(429, "BAD_REQUEST") else null }
            connection.start()
            val offline = connection.awaitStatus { it is LinkStatus.Offline }.status as LinkStatus.Offline
            assertEquals(OfflineReason.TOO_MANY_CONNECTIONS, offline.reason)
            plugin.nextConnection().hello()
            connection.awaitStatus { it is LinkStatus.Online }
        }

    @Test
    fun helloTimeoutReconnects() =
        runBlocking<Unit> {
            val connection = connection(host(), timing = LiveTiming(helloTimeoutMillis = 300))
            connection.start()
            val silent = plugin.nextConnection()
            // 一直不发 hello：超时后 App 断开并重连
            withTimeout(5_000) { silent.gone.await() }
            plugin.nextConnection().hello()
            connection.awaitStatus { it is LinkStatus.Online }
            assertTrue(logs.any { it.contains("hello") })
        }

    @Test
    fun networkChangeSkipsBackoffWait() =
        runBlocking<Unit> {
            val connection = connection(host(), backoff = Backoff(initialMillis = 60_000, maxMillis = 60_000))
            connection.start()
            val first = plugin.nextConnection()
            first.hello()
            connection.awaitStatus { it is LinkStatus.Online }
            first.kill()
            val offline = connection.awaitStatus { it is LinkStatus.Offline }.status as LinkStatus.Offline
            assertEquals(OfflineReason.DISCONNECTED, offline.reason)
            assertTrue(offline.retryInMillis >= 30_000)

            connection.retryNow(afterNetworkChange = true)
            plugin.nextConnection(timeoutMillis = 3_000).hello()
            connection.awaitStatus { it is LinkStatus.Online }
        }

    @Test
    fun unreachableHostIsOfflineAndRetried() =
        runBlocking<Unit> {
            val connection = connection(host(addresses = listOf(deadAddress())), backoff = Backoff(initialMillis = 400, maxMillis = 400))
            connection.start()
            val offline = connection.awaitStatus { it is LinkStatus.Offline }.status as LinkStatus.Offline
            assertEquals(OfflineReason.UNREACHABLE, offline.reason)
            waitUntil { attempts.get() >= 2 }
        }

    @Test
    fun incompatibleDshIsReportedWithoutOpeningStream() =
        runBlocking<Unit> {
            plugin.info = FakePlugin.info(compatible = false, version = "0.3.0")
            val connection = connection(host())
            connection.start()
            connection.awaitStatus { it is LinkStatus.DshIncompatible }
            assertEquals(LinkStatus.DshIncompatible("0.3.0"), connection.state.value.status)
            assertTrue(plugin.streamRequests.isEmpty())
        }

    // ---------------------------------------------------------------- 多地址

    @Test
    fun racesOtherAddressesWhenLastGoodFails() =
        runBlocking<Unit> {
            val dead = deadAddress()
            val connection = connection(host(addresses = listOf(dead, plugin.address), lastGood = dead))
            connection.start()
            plugin.nextConnection().hello()
            val state = connection.awaitStatus { it is LinkStatus.Online }
            assertEquals(LinkStatus.Online(plugin.address), state.status)
            waitUntil { goodAddresses == listOf(plugin.address) }
        }

    @Test
    fun goodAddressWinsOverMismatchedOne() =
        runBlocking<Unit> {
            val impostor = other()
            val connection = connection(host(addresses = listOf(impostor.address, plugin.address)))
            connection.start()
            plugin.nextConnection().hello()
            assertEquals(LinkStatus.Online(plugin.address), connection.awaitStatus { it is LinkStatus.Online }.status)
            assertTrue(impostor.requests.isEmpty())
        }

    @Test
    fun lastGoodAddressIsTriedFirst() =
        runBlocking<Unit> {
            val second = other(cert = plugin.cert)
            val connection = connection(host(addresses = listOf(plugin.address, second.address), lastGood = second.address))
            connection.start()
            second.nextConnection().hello()
            assertEquals(LinkStatus.Online(second.address), connection.awaitStatus { it is LinkStatus.Online }.status)
            assertTrue("lastGoodAddress 成功时不试其他地址", plugin.requests.isEmpty())
            // lastGoodAddress 本来就是它，不需要再记录
            assertTrue(goodAddresses.isEmpty())
        }

    @Test
    fun allAddressesFailingWithOneMismatchReportsMismatch() =
        runBlocking<Unit> {
            val connection = connection(host(addresses = listOf(deadAddress(), other().address)))
            connection.start()
            connection.awaitStatus { it == LinkStatus.FingerprintMismatch }
        }

    // ---------------------------------------------------------------- 订阅

    @Test
    fun subscriptionsAreRefCountedAndRoutedBySession() =
        runBlocking<Unit> {
            val connection = connection(host())
            connection.start()
            val server = plugin.nextConnection()
            server.hello()
            connection.awaitStatus { it is LinkStatus.Online }

            val a = collect(connection, "s1")
            val b = collect(connection, "s1")
            val other = collect(connection, "s2")
            assertEquals(SessionEvent.Resync("s1"), a.next())
            assertEquals(SessionEvent.Resync("s1"), b.next())
            assertEquals(SessionEvent.Resync("s2"), other.next())
            val subscribes = listOf(server.nextReceived(), server.nextReceived())
            assertEquals(
                setOf("""{"type":"subscribe","sessionId":"s1"}""", """{"type":"subscribe","sessionId":"s2"}"""),
                subscribes.toSet(),
            )

            server.send(event("message.upsert", """"sessionId":"s1","rev":3,"message":${message("m1", "你好")},"replaces":"draft:1""""))
            val upsert = a.next() as SessionEvent.Upsert
            assertEquals(3L, upsert.rev)
            assertEquals("draft:1", upsert.replaces)
            assertEquals(MessageRole.ASSISTANT, upsert.message.role)
            assertEquals(listOf(TextPart("你好")), upsert.message.parts)
            assertEquals(upsert, b.next())
            assertNull("别的会话收不到", other.nextOrNull())

            // 第一个收集者离开：还有一个在看，不取消订阅
            a.cancel()
            assertNull(withTimeoutOrNull(300) { server.received.receive() })
            b.cancel()
            assertEquals("""{"type":"unsubscribe","sessionId":"s1"}""", server.nextReceived())
            other.cancel()
            assertEquals("""{"type":"unsubscribe","sessionId":"s2"}""", server.nextReceived())
        }

    @Test
    fun subscriptionsAreResentAfterReconnect() =
        runBlocking<Unit> {
            val connection = connection(host())
            // 连接之前就开始收集：hello 之后才发送 subscribe
            val events = collect(connection, "s1")
            connection.start()
            val first = plugin.nextConnection()
            assertNull(withTimeoutOrNull(200) { first.received.receive() })
            first.hello()
            assertEquals("""{"type":"subscribe","sessionId":"s1"}""", first.nextReceived())
            assertEquals(SessionEvent.Resync("s1"), events.next())

            first.kill()
            val second = plugin.nextConnection()
            second.hello()
            assertEquals("""{"type":"subscribe","sessionId":"s1"}""", second.nextReceived())
            // 重连后让界面重新读取最新一页历史
            assertEquals(SessionEvent.Resync("s1"), events.next())
            second.send(event("message.removed", """"sessionId":"s1","messageId":"draft:2""""))
            assertEquals(SessionEvent.Removed("s1", "draft:2"), events.next())
            events.cancel()
        }

    @Test
    fun invalidSessionIdIsRejected() {
        val connection = connection(host())
        assertTrue(runCatching { connection.subscribe("../x") }.exceptionOrNull() is IllegalArgumentException)
    }

    // ---------------------------------------------------------------- 启停

    @Test
    fun stopClosesNormallyAndStartReconnects() =
        runBlocking<Unit> {
            val connection = connection(host())
            connection.start()
            val first = plugin.nextConnection()
            first.hello()
            connection.awaitStatus { it is LinkStatus.Online }
            connection.stop()
            assertEquals(1000, withTimeout(5_000) { first.closedByApp.await() })
            connection.awaitStatus { it == LinkStatus.Stopped }

            connection.start()
            plugin.nextConnection().hello()
            connection.awaitStatus { it is LinkStatus.Online }
        }

    @Test
    fun stopKeepsTerminalStatusAndStartDoesNotRetryIt() =
        runBlocking<Unit> {
            plugin.handshake = { FakePlugin.error(401, "TOKEN_INVALID") }
            val connection = connection(host())
            connection.start()
            connection.awaitStatus { it is LinkStatus.NeedsPairing }
            connection.stop()
            connection.start()
            delay(200)
            assertTrue(connection.state.value.status is LinkStatus.NeedsPairing)
            assertEquals(1, attempts.get())

            // 用户手动重试：再试一次
            connection.retryNow()
            waitUntil { attempts.get() == 2 }
        }

    // ---------------------------------------------------------------- 工具

    private fun host(
        addresses: List<HostAddress> = listOf(plugin.address),
        lastGood: HostAddress? = null,
    ) = Host("h1", "PC", addresses, lastGood, "d_1", Credentials(pin, token))

    private fun connection(
        host: Host,
        backoff: Backoff = Backoff(initialMillis = 20, maxMillis = 100),
        timing: LiveTiming = LiveTiming(helloTimeoutMillis = 3_000),
    ): HostConnection =
        HostConnection(
            host = host,
            scope = scope,
            clientFor = {
                attempts.incrementAndGet()
                factory.forPin(it)
            },
            markGoodAddress = { goodAddresses += it },
            liveEvents = liveEvents,
            log = { logs += it },
            timing = timing,
            backoff = backoff,
        ).also { connections += it }

    /** 另一个假插件（默认是另一张证书，即指纹不同的「冒充者」）。 */
    private fun other(
        cert: okhttp3.tls.HeldCertificate =
            io.github.qianyeeew.dshremote.data.net.TlsTestSupport
                .serverCertificate(),
    ) = FakePlugin(cert).also { others += it }

    /** 一个没有人监听的端口：连接会被拒绝。 */
    private fun deadAddress(): HostAddress = ServerSocket(0).use { HostAddress("127.0.0.1", it.localPort) }

    private suspend fun HostConnection.awaitStatus(check: (LinkStatus) -> Boolean): HostLiveState =
        withTimeout(5_000) { state.first { check(it.status) } }

    private suspend fun waitUntil(check: () -> Boolean) {
        withTimeout(5_000) { while (!check()) delay(20) }
    }

    private fun collect(
        connection: HostConnection,
        sessionId: String,
    ): Collector {
        val channel = Channel<SessionEvent>(Channel.UNLIMITED)
        val job = scope.launch { connection.subscribe(sessionId).collect { channel.send(it) } }
        return Collector(channel, job)
    }

    private class Collector(
        private val channel: Channel<SessionEvent>,
        private val job: kotlinx.coroutines.Job,
    ) {
        suspend fun next(): SessionEvent = withTimeout(5_000) { channel.receive() }

        suspend fun nextOrNull(): SessionEvent? = withTimeoutOrNull(300) { channel.receive() }

        suspend fun cancel() {
            job.cancel()
            job.join()
        }
    }
}
