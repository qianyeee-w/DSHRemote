package io.github.qianyeeew.dshremote.data.pairing

import io.github.qianyeeew.dshremote.data.host.Credentials
import io.github.qianyeeew.dshremote.data.host.DeviceToken
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.host.HostRepository
import io.github.qianyeeew.dshremote.data.host.testHostRepository
import io.github.qianyeeew.dshremote.data.live.FakePlugin
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import io.github.qianyeeew.dshremote.data.net.PairingProbe
import io.github.qianyeeew.dshremote.data.net.TlsTestSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PairingService：配对、重新配对、取消配对。服务器是带自签证书的假插件，主机列表写在临时文件里。
 * 安全相关的反向用例：指纹不对时一个请求都不发；协议版本不认识时不发送配对码；日志中没有 token 和配对码。
 */
class PairingServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val plugin = FakePlugin()
    private val others = mutableListOf<FakePlugin>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logs = CopyOnWriteArrayList<String>()
    private val tokens = mutableListOf<String>()
    private lateinit var repository: HostRepository
    private lateinit var service: PairingService

    private val pin = Fingerprint.of(plugin.cert.certificate)

    private fun setUp() {
        repository = testHostRepository(File(temp.root, "hosts.json"), scope)
        service =
            PairingService(
                repository = repository,
                clientFactory = ClientFactory(),
                prober = PairingProbe(connectTimeoutMillis = 2_000, handshakeTimeoutMillis = 2_000),
                deviceName = { "Test Phone" },
                log = { logs += it },
                revokeTimeoutMillis = 5_000,
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
        plugin.close()
        others.forEach { it.close() }
        // 红线第 5 条：日志中没有 token 和配对码
        val all = logs.joinToString("\n")
        (tokens + CODE).forEach { assertFalse("日志中出现了秘密", all.contains(it)) }
    }

    @Test
    fun pairsWithQrTargetAndSavesHost() =
        runBlocking<Unit> {
            setUp()
            val token = plugin.acceptPairing()
            val result = service.pair(PairingTarget(plugin.address, pin, CODE, suggestedName = "二维码里的名字")) as PairingResult.Paired

            assertFalse(result.replaced)
            val host = repository.hosts.first().single()
            assertEquals(host, result.host)
            // 名称以插件返回的为准
            assertEquals("MY-PC", host.name)
            assertEquals(listOf(plugin.address), host.addresses)
            assertEquals(plugin.address, host.lastGoodAddress)
            assertEquals("d_1", host.deviceId)
            assertEquals(Credentials(pin, DeviceToken.parse(token)!!), host.credentials)

            // 先检查协议版本，再发送配对码；两个请求都不带 token
            assertEquals(listOf("GET /v1/info", "POST /v1/pair"), plugin.requests.map { "${it.method} ${it.url.encodedPath}" })
            plugin.requests.forEach { assertNull(it.headers["Authorization"]) }
            val body =
                plugin.requests
                    .last()
                    .body!!
                    .utf8()
            assertTrue(body.contains("\"code\":\"$CODE\""))
            assertTrue(body.contains("\"deviceName\":\"Test Phone\""))
        }

    @Test
    fun wrongFingerprintSendsNothing() =
        runBlocking<Unit> {
            setUp()
            plugin.acceptPairing()
            val wrong = Fingerprint.of(TlsTestSupport.serverCertificate().certificate)
            assertEquals(PairingResult.FingerprintMismatch, service.pair(PairingTarget(plugin.address, wrong, CODE)))
            assertTrue("指纹不对时服务器不应收到任何请求", plugin.requests.isEmpty())
            assertTrue(repository.hosts.first().isEmpty())
        }

    @Test
    fun unknownProtocolDoesNotSendCode() =
        runBlocking<Unit> {
            setUp()
            plugin.acceptPairing()
            plugin.info = FakePlugin.info(protocol = 2)
            assertEquals(PairingResult.AppTooOld(2), service.pair(PairingTarget(plugin.address, pin, CODE)))
            assertEquals(listOf("/v1/info"), plugin.requests.map { it.url.encodedPath })
            assertTrue(repository.hosts.first().isEmpty())
        }

    @Test
    fun mapsPairingErrors() =
        runBlocking<Unit> {
            setUp()
            val cases =
                listOf(
                    FakePlugin.error(400, "PAIRING_CODE_INVALID") to PairingResult.CodeInvalid,
                    FakePlugin.error(409, "PAIRING_DEVICE_LIMIT") to PairingResult.DeviceLimit,
                    FakePlugin.error(500, "INTERNAL") to PairingResult.Failed,
                    FakePlugin.json("""{"unexpected":true}""") to PairingResult.Failed,
                    FakePlugin
                        .error(429, "PAIRING_LOCKED")
                        .newBuilder()
                        .addHeader("Retry-After", "42")
                        .build() to PairingResult.Locked(42),
                )
            for ((response, expected) in cases) {
                plugin.routes = { if (it.url.encodedPath == "/v1/pair") response else null }
                assertEquals(expected, service.pair(PairingTarget(plugin.address, pin, CODE)))
            }
            assertTrue(repository.hosts.first().isEmpty())
        }

    @Test
    fun unreachableAddress() =
        runBlocking<Unit> {
            setUp()
            val closed = FakePlugin().also { it.close() }
            assertEquals(PairingResult.Unreachable, service.pair(PairingTarget(closed.address, pin, CODE)))
        }

    @Test
    fun pairingSameComputerAgainUpdatesHostAndRevokesOldDevice() =
        runBlocking<Unit> {
            setUp()
            val oldToken = plugin.acceptPairing(deviceId = "d_old")
            val first = (service.pair(PairingTarget(plugin.address, pin, CODE)) as PairingResult.Paired).host
            repository.rename(first.hostId, "我的电脑")

            // 同一台电脑（同一个证书）在另一个地址上，例如 Tailscale
            val sameComputer = FakePlugin(cert = plugin.cert).also { others += it }
            val newToken = sameComputer.acceptPairing(deviceId = "d_new")
            val result = service.pair(PairingTarget(sameComputer.address, pin, CODE)) as PairingResult.Paired

            assertTrue(result.replaced)
            val host = repository.hosts.first().single()
            assertEquals(first.hostId, host.hostId)
            assertEquals("我的电脑", host.name)
            assertEquals("d_new", host.deviceId)
            assertEquals(DeviceToken.parse(newToken), host.credentials?.token)
            assertEquals(listOf(sameComputer.address, plugin.address), host.addresses)
            assertEquals(sameComputer.address, host.lastGoodAddress)
            // 旧的设备记录被撤销（用旧 token 调用 DELETE /v1/me）
            val delete = sameComputer.requests.single { it.method == "DELETE" }
            assertEquals("/v1/me", delete.url.encodedPath)
            assertEquals("Bearer $oldToken", delete.headers["Authorization"])
        }

    @Test
    fun repairingByHostIdAcceptsNewFingerprint() =
        runBlocking<Unit> {
            setUp()
            plugin.acceptPairing()
            val first = (service.pair(PairingTarget(plugin.address, pin, CODE)) as PairingResult.Paired).host

            // 电脑上的插件重新生成了密钥：用户从配对页扫码重新配对
            val reinstalled = FakePlugin().also { others += it }
            val newPin = Fingerprint.of(reinstalled.cert.certificate)
            reinstalled.acceptPairing(deviceId = "d_new")
            val result =
                service.pair(
                    PairingTarget(reinstalled.address, newPin, CODE),
                    replaceHostId = first.hostId,
                ) as PairingResult.Paired

            assertTrue(result.replaced)
            val host = repository.hosts.first().single()
            assertEquals(newPin, host.credentials?.fingerprint)
            // 旧 token 只会通过旧指纹的连接发出，新的服务器收不到
            assertTrue(reinstalled.requests.none { it.method == "DELETE" })
            assertTrue(plugin.requests.none { it.method == "DELETE" })
        }

    @Test
    fun savingFailureRevokesTheNewDevice() =
        runBlocking<Unit> {
            setUp()
            val token = plugin.acceptPairing()
            assertEquals(PairingResult.Failed, service.pair(PairingTarget(plugin.address, pin, CODE), replaceHostId = "missing"))
            val delete = plugin.requests.single { it.method == "DELETE" }
            assertEquals("Bearer $token", delete.headers["Authorization"])
            assertTrue(repository.hosts.first().isEmpty())
        }

    @Test
    fun unpairRevokesOnComputerAndRemovesHost() =
        runBlocking<Unit> {
            setUp()
            val token = plugin.acceptPairing()
            val host = (service.pair(PairingTarget(plugin.address, pin, CODE)) as PairingResult.Paired).host
            assertTrue(service.unpair(host))
            assertTrue(repository.hosts.first().isEmpty())
            val delete = plugin.requests.single { it.method == "DELETE" }
            assertEquals("/v1/me", delete.url.encodedPath)
            assertEquals("Bearer $token", delete.headers["Authorization"])
        }

    @Test
    fun unpairTriesOtherAddressesAndTreats401AsRevoked() =
        runBlocking<Unit> {
            setUp()
            plugin.acceptPairing()
            val host = (service.pair(PairingTarget(plugin.address, pin, CODE)) as PairingResult.Paired).host
            val dead = FakePlugin().also { it.close() }.address
            repository.setAddresses(host.hostId, listOf(plugin.address, dead))
            // 先试 lastGoodAddress（已经连不上），再试其他地址；插件返回 401（已经被撤销）也算撤销成功
            repository.markGoodAddress(host.hostId, dead)
            plugin.routes = { if (it.method == "DELETE") FakePlugin.error(401, "TOKEN_INVALID") else null }
            assertTrue(service.unpair(repository.get(host.hostId)!!))
            assertTrue(repository.hosts.first().isEmpty())
        }

    @Test
    fun unpairWhileOfflineStillRemovesLocally() =
        runBlocking<Unit> {
            setUp()
            plugin.acceptPairing()
            val host = (service.pair(PairingTarget(plugin.address, pin, CODE)) as PairingResult.Paired).host
            plugin.close()
            assertFalse(service.unpair(host))
            assertTrue(repository.hosts.first().isEmpty())
        }

    @Test
    fun probeReturnsServerFingerprint() =
        runBlocking<Unit> {
            setUp()
            assertEquals(PairingProbe.Result.Fingerprinted(pin), service.probe(plugin.address))
            assertTrue("探测不发送任何请求", plugin.requests.isEmpty())
        }

    @Test
    fun pairingCodeInputIsNormalized() {
        assertEquals("K7M2Q9XP", normalizePairingCode(" k7m2-q9xp "))
        assertEquals("K7M2Q9XP", normalizePairingCode("K7M2 Q9XP"))
        assertNull(normalizePairingCode("K7M2Q9X0"))
        assertNull(normalizePairingCode("K7M2Q9X"))
        assertNull(normalizePairingCode(""))
    }

    @Test
    fun targetHidesCode() {
        val target = PairingTarget(HostAddress("192.168.1.5", 3190), pin, CODE)
        assertFalse(target.toString().contains(CODE))
    }

    /** 让假插件接受配对，返回它发出的 token。 */
    private fun FakePlugin.acceptPairing(deviceId: String = "d_1"): String {
        val token = newToken().also { tokens += it }
        routes = { request: RecordedRequest ->
            when {
                request.url.encodedPath == "/v1/pair" ->
                    FakePlugin.json("""{"deviceId":"$deviceId","token":"$token","host":{"name":"MY-PC","pluginVersion":"0.1.0"}}""")
                request.method == "DELETE" && request.url.encodedPath == "/v1/me" -> MockResponse.Builder().code(204).build()
                else -> null
            }
        }
        return token
    }

    private companion object {
        const val CODE = "K7M2Q9XP"

        fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    }
}
