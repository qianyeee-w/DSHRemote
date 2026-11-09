package io.github.qianyeeew.dshremote.data.live

import io.github.qianyeeew.dshremote.data.host.Credentials
import io.github.qianyeeew.dshremote.data.host.DeviceToken
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.live.FakePlugin.Companion.approval
import io.github.qianyeeew.dshremote.data.live.FakePlugin.Companion.event
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** ConnectionManager：跟随主机列表增删连接、按 hold 启停、网络变化时重试、汇总事件。 */
class ConnectionManagerTest {
    private val plugin = FakePlugin()
    private val second = FakePlugin()
    private val token = DeviceToken.parse("tok3n_SECRET-abcdefghijklmnopqrstuvwxyz0120")!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hosts = MutableStateFlow<List<Host>>(emptyList())
    private val factory = ClientFactory()

    /** 和 HostRepository.markGoodAddress 一样：更新主机列表，ConnectionManager 会收到新的列表。 */
    private val manager =
        ConnectionManager(
            hosts = hosts,
            scope = scope,
            clientFor = factory::forPin,
            markGoodAddress = { hostId, address ->
                hosts.update { list -> list.map { if (it.hostId == hostId) it.copy(lastGoodAddress = address) else it } }
            },
            log = {},
            timing = LiveTiming(helloTimeoutMillis = 3_000),
            newBackoff = { Backoff(initialMillis = 20, maxMillis = 100) },
        )

    @After
    fun tearDown() {
        scope.cancel()
        plugin.close()
        second.close()
    }

    @Test
    fun connectsOnlyWhileHeld() =
        runBlocking<Unit> {
            hosts.value = listOf(host("h1", plugin))
            awaitStatus("h1") { it == LinkStatus.Stopped }
            plugin.assertNoNewConnection(300)

            val hold = manager.hold()
            val server = plugin.nextConnection()
            server.hello()
            awaitStatus("h1") { it is LinkStatus.Online }

            // 第二个 hold 不会多开连接；释放一个后仍然连着
            val another = manager.hold()
            hold.release()
            hold.release()
            delay(200)
            assertTrue(
                manager.states.value
                    .getValue("h1")
                    .status is LinkStatus.Online,
            )

            another.release()
            assertEquals(1000, withTimeout(5_000) { server.closedByApp.await() })
            awaitStatus("h1") { it == LinkStatus.Stopped }
            plugin.assertNoNewConnection(200)
        }

    @Test
    fun followsHostListOneConnectionPerHost() =
        runBlocking<Unit> {
            manager.hold()
            hosts.value = listOf(host("h1", plugin), host("h2", second))
            plugin.nextConnection().hello()
            second.nextConnection().hello()
            awaitStatus("h1") { it is LinkStatus.Online }
            awaitStatus("h2") { it is LinkStatus.Online }

            // markGoodAddress 更新了 lastGoodAddress：不重建连接
            withTimeout(5_000) { hosts.first { list -> list.all { it.lastGoodAddress != null } } }
            plugin.assertNoNewConnection(300)

            // 删除 h2：关闭连接，状态中不再有它
            val h2 = second.streamRequests.size
            hosts.update { list -> list.filter { it.hostId == "h1" } }
            withTimeout(5_000) { manager.states.first { "h2" !in it } }
            assertNull(manager.connection("h2"))
            second.assertNoNewConnection(200)
            assertEquals(h2, second.streamRequests.size)
        }

    @Test
    fun changedAddressesReplaceTheConnection() =
        runBlocking<Unit> {
            manager.hold()
            hosts.value = listOf(host("h1", plugin))
            val old = plugin.nextConnection()
            old.hello()
            awaitStatus("h1") { it is LinkStatus.Online }
            val before = manager.connection("h1")

            // 用户把地址改成了另一个端口（同一台电脑、同一个证书）：先关旧连接，再连新地址，不需要重新配对
            val moved = FakePlugin(plugin.cert)
            try {
                hosts.update { list -> list.map { it.copy(addresses = listOf(moved.address), lastGoodAddress = null) } }
                assertEquals(1000, withTimeout(5_000) { old.closedByApp.await() })
                moved.nextConnection().hello()
                assertEquals(LinkStatus.Online(moved.address), awaitStatus("h1") { it == LinkStatus.Online(moved.address) }.status)
                assertTrue(manager.connection("h1") !== before)
            } finally {
                moved.close()
            }
        }

    @Test
    fun repairingRestartsAHostThatNeededPairing() =
        runBlocking<Unit> {
            plugin.handshake = { FakePlugin.error(401, "TOKEN_INVALID") }
            manager.hold()
            hosts.value = listOf(host("h1", plugin))
            awaitStatus("h1") { it is LinkStatus.NeedsPairing }

            // 重新配对：换了 token，连接重建，用新 token 连接成功
            plugin.handshake = { null }
            val newToken = DeviceToken.parse("new_token-0123456789abcdefghijklmnopqrstuvw")!!
            hosts.update { list -> list.map { it.copy(credentials = it.credentials!!.copy(token = newToken)) } }
            val server = plugin.nextConnection()
            assertEquals("Bearer ${newToken.value}", server.request.headers["Authorization"])
            server.hello()
            awaitStatus("h1") { it is LinkStatus.Online }
        }

    @Test
    fun networkChangeRetriesWaitingHosts() =
        runBlocking<Unit> {
            val slow =
                ConnectionManager(
                    hosts = hosts,
                    scope = scope,
                    clientFor = factory::forPin,
                    markGoodAddress = { _, _ -> },
                    log = {},
                    newBackoff = { Backoff(initialMillis = 60_000, maxMillis = 60_000) },
                )
            slow.hold()
            hosts.value = listOf(host("h1", plugin))
            val first = plugin.nextConnection()
            first.close(HostConnection.CLOSE_TRY_AGAIN)
            withTimeout(5_000) { slow.states.first { it["h1"]?.status is LinkStatus.Offline } }
            slow.onNetworkAvailable()
            plugin.nextConnection(timeoutMillis = 3_000).hello()
            withTimeout(5_000) { slow.states.first { it["h1"]?.status is LinkStatus.Online } }
        }

    @Test
    fun eventsFromAllHostsAreMerged() =
        runBlocking<Unit> {
            val received = CopyOnWriteArrayList<LiveEvent>()
            scope.launch { manager.events.collect { received += it } }
            manager.hold()
            hosts.value = listOf(host("h1", plugin), host("h2", second))
            val a = plugin.nextConnection()
            val b = second.nextConnection()
            a.hello()
            b.hello()
            awaitStatus("h1") { it is LinkStatus.Online }
            awaitStatus("h2") { it is LinkStatus.Online }
            a.send(event("attention.requested", """"attention":${approval("a1")}"""))
            b.send(event("session.idle", """"sessionId":"s9","title":null"""))
            withTimeout(5_000) { while (received.size < 2) delay(20) }
            assertEquals(setOf("h1", "h2"), received.map { it.hostId }.toSet())
            assertEquals(
                "a1",
                manager.states.value
                    .getValue("h1")
                    .attention.keys
                    .single(),
            )
        }

    private fun host(
        hostId: String,
        server: FakePlugin,
    ) = Host(hostId, hostId, listOf(server.address), null, "d_$hostId", Credentials(Fingerprint.of(server.cert.certificate), token))

    private suspend fun awaitStatus(
        hostId: String,
        check: (LinkStatus) -> Boolean,
    ): HostLiveState =
        withTimeout(5_000) {
            manager.states.first { states -> states[hostId]?.let { check(it.status) } == true }
        }.getValue(hostId)
}
