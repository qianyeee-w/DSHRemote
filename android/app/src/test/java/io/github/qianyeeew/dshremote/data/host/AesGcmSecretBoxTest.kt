package io.github.qianyeeew.dshremote.data.host

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AesGcmSecretBox 的加密格式和防篡改（JVM 上用软件密钥）。真机上用 Keystore 密钥的测试见 androidTest。
 */
class AesGcmSecretBoxTest {
    private val key = newKey()
    private val box = AesGcmSecretBox { key }
    private val plaintext = "secret-token-value".encodeToByteArray()
    private val aad = "host-1".encodeToByteArray()

    @Test
    fun roundTrips() {
        assertArrayEquals(plaintext, box.open(box.seal(plaintext, aad), aad))
        assertArrayEquals(ByteArray(0), box.open(box.seal(ByteArray(0), aad), aad))
    }

    @Test
    fun formatIsVersionIvCiphertextTag() {
        val sealed = box.seal(plaintext, aad)
        assertEquals(1.toByte(), sealed[0])
        assertEquals(1 + 12 + plaintext.size + 16, sealed.size)
        // 密文中不出现明文
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("secret-token-value"))
    }

    @Test
    fun everySealUsesFreshIv() {
        val a = box.seal(plaintext, aad)
        val b = box.seal(plaintext, aad)
        assertNotEquals(a.copyOfRange(1, 13).toList(), b.copyOfRange(1, 13).toList())
    }

    @Test
    fun rejectsWrongAad() {
        // 把一台主机的密文挪到另一台主机的记录上：无法解密
        val sealed = box.seal(plaintext, aad)
        assertFails { box.open(sealed, "host-2".encodeToByteArray()) }
        assertFails { box.open(sealed, ByteArray(0)) }
    }

    @Test
    fun rejectsAnyModifiedByte() {
        val sealed = box.seal(plaintext, aad)
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFails { box.open(tampered, aad) }
        }
    }

    @Test
    fun rejectsTruncatedOrMalformedInput() {
        val sealed = box.seal(plaintext, aad)
        assertFails { box.open(sealed.copyOf(sealed.size - 1), aad) }
        assertFails { box.open(sealed.copyOf(1 + 12 + 15), aad) }
        assertFails { box.open(ByteArray(0), aad) }
        assertFails { box.open(byteArrayOf(2) + sealed.copyOfRange(1, sealed.size), aad) }
    }

    @Test
    fun rejectsDifferentKey() {
        // 相当于卸载重装后 Keystore 中换了一把新密钥
        val sealed = box.seal(plaintext, aad)
        val other = AesGcmSecretBox { newKey() }
        assertFails { other.open(sealed, aad) }
    }

    @Test
    fun errorMessagesContainNoData() {
        val sealed = box.seal(plaintext, aad)
        val error = assertFails { box.open(sealed, "host-2".encodeToByteArray()) }
        val text = generateSequence<Throwable>(error) { it.cause }.joinToString { "${it.message}" }
        assertFalse(text.contains("secret-token-value"))
        assertFalse(text.contains("host-1"))
    }

    private fun assertFails(block: () -> Unit): SecretBoxException {
        try {
            block()
        } catch (e: SecretBoxException) {
            return e
        }
        fail("应当抛出 SecretBoxException")
        throw IllegalStateException()
    }

    private companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
