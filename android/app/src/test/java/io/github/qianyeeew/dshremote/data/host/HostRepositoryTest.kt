package io.github.qianyeeew.dshremote.data.host

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * HostRepository 在 JVM 上的测试：DataStore 写真实的临时文件，SecretBox 用软件密钥。
 * 真机上用 Keystore 的测试见 androidTest 的 HostRepositoryDeviceTest。
 */
class HostRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val tokenText = "tok3n_SECRET-abcdefghijklmnopqrstuvwxyz0120"
    private val fpText = "Oaj69GfnJH_P-CmHX0B6Vxe-YBlVHoveBj7rSEae14M"
    private val credentials = Credentials(Fingerprint.parse(fpText)!!, DeviceToken.parse(tokenText)!!)
    private val otherCredentials =
        Credentials(
            Fingerprint.parse("E9JMy_oGlrnfgc22ShLnbW3szeR8nFwHFvPZ7TCjzPw")!!,
            DeviceToken.parse("another_token-0123456789abcdefghijklmnopqrs")!!,
        )
    private val address = HostAddress("192.168.3.26", 3190)
    private val key = newKey()
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var file: File

    @After
    fun closeScopes() = scopes.forEach { it.cancel() }

    @Test
    fun addsAndReadsBackHost() =
        runBlocking {
            val repo = open()
            val added = repo.add("  MY-PC\n", address, "d_abc", credentials)
            assertEquals("MY-PC", added.name)
            assertEquals(listOf(address), added.addresses)
            assertEquals(address, added.lastGoodAddress)
            assertEquals(credentials, added.credentials)
            assertEquals(listOf(added), repo.hosts.first())
            assertEquals(added, repo.get(added.hostId))
            assertNull(repo.get("missing"))
        }

    @Test
    fun survivesReopen() =
        runBlocking {
            val added = open().add("PC", address, "d_abc", credentials)
            closeAll()
            assertEquals(listOf(added), open(reuseFile = true).hosts.first())
        }

    @Test
    fun fileContainsNoTokenOrFingerprintInPlaintext() =
        runBlocking {
            open().add("PC", address, "d_abc", credentials)
            val raw = file.readText()
            assertFalse("token 以明文保存了", raw.contains(tokenText))
            assertFalse("指纹以明文保存了", raw.contains(fpText))
            // 其他字段明文保存
            assertTrue(raw.contains("192.168.3.26"))
            assertTrue(raw.contains("d_abc"))
        }

    @Test
    fun credentialsBecomeNullWhenKeyIsGone() =
        runBlocking {
            open().add("PC", address, "d_abc", credentials)
            closeAll()
            // 卸载重装后 Keystore 中是一把新密钥：主机仍在列表中，但需要重新配对
            val host = open(reuseFile = true, secretKey = newKey()).hosts.first().single()
            assertNull(host.credentials)
            assertEquals("PC", host.name)
        }

    @Test
    fun secretCannotBeMovedToAnotherHost() =
        runBlocking {
            val repo = open()
            val a = repo.add("A", address, "d_a", credentials)
            repo.add("B", HostAddress("192.168.3.27", 3190), "d_b", otherCredentials)
            closeAll()
            // 把 A 的密文复制到 B 的记录上（模拟文件被改动）：hostId 作为附加认证数据，B 无法解密
            val stored = HostsFileSerializer.readFrom(file.inputStream())
            val swapped = stored.copy(hosts = stored.hosts.map { it.copy(secret = stored.hosts[0].secret) })
            file.outputStream().use { HostsFileSerializer.writeTo(swapped, it) }
            val hosts = open(reuseFile = true).hosts.first()
            assertEquals(credentials, hosts.single { it.hostId == a.hostId }.credentials)
            assertNull(hosts.single { it.hostId != a.hostId }.credentials)
        }

    @Test
    fun corruptFileIsReplacedWithEmptyList() =
        runBlocking {
            open().add("PC", address, "d_abc", credentials)
            closeAll()
            file.writeText("{not json")
            assertEquals(emptyList<Host>(), open(reuseFile = true).hosts.first())
        }

    @Test
    fun unsupportedVersionIsTreatedAsCorrupt() =
        runBlocking {
            file = temp.newFile("hosts.json")
            file.writeText("""{"version":2,"hosts":[]}""")
            assertEquals(emptyList<Host>(), open(reuseFile = true).hosts.first())
        }

    @Test
    fun renamesWithCleaning() =
        runBlocking {
            val repo = open()
            val host = repo.add("PC", address, "d_abc", credentials)
            repo.rename(host.hostId, "  新名字\u0007 ")
            assertEquals("新名字", repo.get(host.hostId)!!.name)
            assertThrows { repo.rename(host.hostId, " \n ") }
            assertThrows { repo.rename("missing", "x") }
        }

    @Test
    fun editsAddressesWithoutTouchingCredentials() =
        runBlocking {
            val repo = open()
            val host = repo.add("PC", address, "d_abc", credentials)
            val tailscale = HostAddress("100.64.0.5", 3190)
            repo.setAddresses(host.hostId, listOf(address, tailscale, address))
            var updated = repo.get(host.hostId)!!
            assertEquals(listOf(address, tailscale), updated.addresses)
            assertEquals(address, updated.lastGoodAddress)
            assertEquals(credentials, updated.credentials)

            repo.markGoodAddress(host.hostId, tailscale)
            assertEquals(tailscale, repo.get(host.hostId)!!.lastGoodAddress)
            // 不在列表中的地址不会被记为 lastGoodAddress
            repo.markGoodAddress(host.hostId, HostAddress("10.0.0.1", 3190))
            assertEquals(tailscale, repo.get(host.hostId)!!.lastGoodAddress)

            // 删掉 lastGoodAddress 对应的地址后，它被清空
            repo.setAddresses(host.hostId, listOf(address))
            updated = repo.get(host.hostId)!!
            assertEquals(listOf(address), updated.addresses)
            assertNull(updated.lastGoodAddress)

            assertThrows { repo.setAddresses(host.hostId, emptyList()) }
            assertThrows { repo.setAddresses(host.hostId, (1..9).map { HostAddress("10.0.0.$it", 3190) }) }
        }

    @Test
    fun replacesCredentialsOnRepair() =
        runBlocking {
            val repo = open()
            val host = repo.add("PC", address, "d_abc", credentials)
            repo.replaceCredentials(host.hostId, "d_new", otherCredentials)
            val updated = repo.get(host.hostId)!!
            assertEquals("d_new", updated.deviceId)
            assertEquals(otherCredentials, updated.credentials)
            assertEquals("PC", updated.name)
            assertThrows { repo.replaceCredentials(host.hostId, "bad id!", credentials) }
        }

    @Test
    fun removesHost() =
        runBlocking {
            val repo = open()
            val a = repo.add("A", address, "d_a", credentials)
            val b = repo.add("B", address, "d_b", otherCredentials)
            repo.remove(a.hostId)
            assertEquals(listOf(b.hostId), repo.hosts.first().map { it.hostId })
            repo.remove("missing")
            assertEquals(1, repo.hosts.first().size)
        }

    @Test
    fun rejectsInvalidDeviceIdOrName() =
        runBlocking {
            val repo = open()
            assertThrows { repo.add("PC", address, "", credentials) }
            assertThrows { repo.add("PC", address, "d/../x", credentials) }
            assertThrows { repo.add("\n", address, "d_abc", credentials) }
            assertTrue(repo.hosts.first().isEmpty())
        }

    // ---- 工具 ----

    private fun open(
        reuseFile: Boolean = false,
        secretKey: SecretKey = key,
    ): HostRepository {
        if (!reuseFile) file = File(temp.root, "hosts-${scopes.size}.json")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val store =
            DataStoreFactory.create(
                serializer = HostsFileSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { HostsFile() },
                scope = scope,
                produceFile = { file },
            )
        var counter = 0
        return HostRepository(store, AesGcmSecretBox { secretKey }) { "host-${++counter}" }
    }

    /** DataStore 要求同一个文件同时只有一个实例：重新打开前先关掉之前的。 */
    private suspend fun closeAll() {
        scopes.forEach { it.cancel() }
        scopes.forEach { it.coroutineContext[kotlinx.coroutines.Job]!!.join() }
    }

    private suspend fun assertThrows(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertNotNull("应当抛出 IllegalArgumentException", error)
        assertTrue("实际是 $error", error is IllegalArgumentException)
    }

    private companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
