package io.github.qianyeeew.dshremote.devserver

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.PluginApi
import io.github.qianyeeew.dshremote.data.api.isTokenInvalid
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.host.testHostRepository
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import io.github.qianyeeew.dshremote.data.net.PairingProbe
import io.github.qianyeeew.dshremote.data.pairing.PairingResult
import io.github.qianyeeew.dshremote.data.pairing.PairingService
import io.github.qianyeeew.dshremote.data.pairing.PairingTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 配对流程对真实 dev-server 的集成测试（m2-android.md T7）：探测指纹、配对、指纹不对时配对码没有发出、
 * 同一台电脑再次配对时撤销旧设备、取消配对。只由 `./gradlew devServerTest` 运行，结束时不留下测试设备。
 */
class PairingDevServerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val factory = ClientFactory()
    private val logs = CopyOnWriteArrayList<String>()
    private val repository by lazy { testHostRepository(File(temp.root, "hosts.json"), scope) }
    private val service by lazy { PairingService(repository, factory, deviceName = { "PairingDevServerTest" }, log = { logs += it }) }
    private val status = DevServer.status()

    @After
    fun tearDown() =
        runBlocking<Unit> {
            // 万一测试中途失败，也撤销留下的设备
            repository.hosts.first().forEach { service.unpair(it) }
            scope.cancel()
        }

    @Test
    fun probeSeesThePluginFingerprint() =
        runBlocking<Unit> {
            assertEquals(PairingProbe.Result.Fingerprinted(status.pin), service.probe(status.address))
        }

    @Test
    fun wrongFingerprintKeepsTheCodeUnused() =
        runBlocking<Unit> {
            val code = DevServer.newPairingCode()
            val wrong = Fingerprint.parse("E9JMy_oGlrnfgc22ShLnbW3szeR8nFwHFvPZ7TCjzPw")!!
            assertEquals(PairingResult.FingerprintMismatch, service.pair(PairingTarget(status.address, wrong, code)))
            // 配对码没有发出去，所以仍然有效
            assertTrue(service.pair(PairingTarget(status.address, status.pin, code)) is PairingResult.Paired)
        }

    @Test
    fun pairRepairAndUnpair() =
        runBlocking<Unit> {
            assertEquals(PairingResult.CodeInvalid, service.pair(PairingTarget(status.address, status.pin, "AAAAAAAA")))

            val first = (service.pair(PairingTarget(status.address, status.pin, DevServer.newPairingCode())) as PairingResult.Paired)
            assertFalse(first.replaced)
            assertEquals("PairingDevServerTest", api(first.host).me().name)

            // 同一台电脑再配对一次：更新同一条记录，旧设备被撤销
            val second = (service.pair(PairingTarget(status.address, status.pin, DevServer.newPairingCode())) as PairingResult.Paired)
            assertTrue(second.replaced)
            assertEquals(first.host.hostId, second.host.hostId)
            assertEquals(1, repository.hosts.first().size)
            assertRevoked(first.host)
            api(second.host).me()

            // 取消配对：电脑上立即失效，本地记录删除
            assertTrue(service.unpair(second.host))
            assertRevoked(second.host)
            assertTrue(repository.hosts.first().isEmpty())

            val all = logs.joinToString("\n")
            listOf(first.host, second.host).forEach { assertFalse(all.contains(it.credentials!!.token.value)) }
        }

    private fun api(host: Host) = PluginApi(factory.forPin(status.pin), status.address, host.credentials!!.token)

    private suspend fun assertRevoked(host: Host) {
        val error = runCatching { api(host).me() }.exceptionOrNull()
        assertTrue("token 应该已经失效：$error", error is ApiException && error.isTokenInvalid)
    }
}
