package io.github.qianyeeew.dshremote.data.host

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * 真机上的 Android Keystore 测试（m2-android.md T4）：密钥属性、加解密、
 * 删除密钥（相当于卸载重装）后旧数据无法解密。只使用测试专用的别名，不碰 App 正式使用的密钥。
 */
@RunWith(AndroidJUnit4::class)
class KeystoreDeviceTest {
    private val box = AesGcmSecretBox { KeystoreKeys.getOrCreate(ALIAS) }
    private val plaintext = "secret-token-value".encodeToByteArray()
    private val aad = "host-1".encodeToByteArray()
    private val scopes = mutableListOf<CoroutineScope>()
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "keystore-device-test")

    @After
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        deleteKey()
        dir.deleteRecursively()
    }

    @Test
    fun keyIsNonExportableAesGcmWithoutUserAuthentication() {
        val key = KeystoreKeys.getOrCreate(ALIAS)
        // 不可导出：拿不到密钥本身
        assertNull(key.encoded)
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, info.purposes)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), info.blockModes)
        assertEquals(KeyProperties.ORIGIN_GENERATED, info.origin)
        // 锁屏时前台服务也要能读取 token
        assertFalse(info.isUserAuthenticationRequired)
        Log.i(TAG, "Keystore 安全级别：${securityLevel(info)}")
    }

    @Test
    fun callerCannotChooseIv() {
        // setRandomizedEncryptionRequired(true)：IV 只能由 Keystore 生成，防止重复使用 IV
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, KeystoreKeys.getOrCreate(ALIAS), GCMParameterSpec(128, ByteArray(12)))
            fail("Keystore 应拒绝调用方指定的 IV")
        } catch (_: InvalidAlgorithmParameterException) {
        }
    }

    @Test
    fun roundTripsAndRejectsTampering() {
        val sealed = box.seal(plaintext, aad)
        assertArrayEquals(plaintext, box.open(sealed, aad))
        assertFails { box.open(sealed, "host-2".encodeToByteArray()) }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFails { box.open(tampered, aad) }
    }

    @Test
    fun dataCannotBeDecryptedAfterKeyIsDeleted() {
        // 卸载 App 时系统删除 Keystore 中的密钥；重装后生成的是新密钥
        val sealed = box.seal(plaintext, aad)
        deleteKey()
        assertFails { box.open(sealed, aad) }
    }

    @Test
    fun repositoryWithKeystoreKeepsSecretsEncryptedAndNeedsRepairAfterKeyLoss() =
        runBlocking {
            val tokenText = "tok3n_SECRET-abcdefghijklmnopqrstuvwxyz0120"
            val fpText = "Oaj69GfnJH_P-CmHX0B6Vxe-YBlVHoveBj7rSEae14M"
            val credentials = Credentials(Fingerprint.parse(fpText)!!, DeviceToken.parse(tokenText)!!)
            val file = File(dir, "hosts.json")

            val added = openRepository(file).add("PC", HostAddress("192.168.3.26", 3190), "d_abc", credentials)
            assertEquals(credentials, added.credentials)
            val raw = file.readText()
            assertFalse(raw.contains(tokenText))
            assertFalse(raw.contains(fpText))
            closeAll()

            assertEquals(
                credentials,
                openRepository(file)
                    .hosts
                    .first()
                    .single()
                    .credentials,
            )
            closeAll()

            deleteKey()
            val host = openRepository(file).hosts.first().single()
            assertNull("密钥丢失后应需要重新配对", host.credentials)
            assertEquals("PC", host.name)
        }

    private fun openRepository(file: File): HostRepository {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val store =
            DataStoreFactory.create(
                serializer = HostsFileSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { HostsFile() },
                scope = scope,
                produceFile = { file },
            )
        return HostRepository(store, box)
    }

    private suspend fun closeAll() {
        scopes.forEach { it.cancel() }
        scopes.forEach { it.coroutineContext[Job]!!.join() }
        scopes.clear()
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: SecretBoxException) {
            return
        }
        fail("应当抛出 SecretBoxException")
    }

    private fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
    }

    @Suppress("DEPRECATION")
    private fun securityLevel(info: KeyInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "软件"
                else -> "未知（${info.securityLevel}）"
            }
        } else {
            if (info.isInsideSecureHardware) "安全硬件" else "软件"
        }

    private companion object {
        const val ALIAS = "dshremote.test.keystore-device-test"
        const val TAG = "KeystoreDeviceTest"
    }
}
