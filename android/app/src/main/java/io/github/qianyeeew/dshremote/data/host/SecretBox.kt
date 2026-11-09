package io.github.qianyeeew.dshremote.data.host

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 加密保存秘密数据（设备 token 和指纹）。
 *
 * `aad`（附加认证数据）不加密，但参与校验：存储时传入主机的 `hostId`，
 * 这样把一台主机的密文挪到另一台主机的记录上也无法解密。
 */
interface SecretBox {
    fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    /** 密文被改动、`aad` 不对、密钥已经不存在或不对时抛出 [SecretBoxException]。 */
    fun open(
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray
}

class SecretBoxException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * AES-256-GCM。密文格式：`[版本 1 字节 = 1][IV 12 字节][密文 + 16 字节认证标签]`。
 *
 * IV 由 Cipher 随机生成（Android Keystore 要求如此，不允许调用方指定），每次加密都不同。
 */
class AesGcmSecretBox(
    private val key: () -> SecretKey,
) : SecretBox {
    override fun seal(
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "IV 长度不对" }
        return byteArrayOf(FORMAT_VERSION) + iv + ciphertext
    }

    override fun open(
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        if (sealed.size < 1 + IV_BYTES + TAG_BYTES || sealed[0] != FORMAT_VERSION) {
            throw SecretBoxException("密文格式不对")
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, sealed, 1, IV_BYTES))
            cipher.updateAAD(aad)
            return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            // 异常信息只说明类型，不包含任何数据
            throw SecretBoxException("无法解密：${e.javaClass.simpleName}", e)
        } catch (e: ProviderException) {
            // Android Keystore 内部出错时抛出的是 ProviderException（运行时异常）
            throw SecretBoxException("无法解密：${e.javaClass.simpleName}", e)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/**
 * Android Keystore 中的 AES-256 密钥：在 Keystore 内生成，**不可导出**（App 自己也读不到密钥本身）。
 *
 * - 卸载 App 时系统删除密钥，重装后旧的密文无法解密，需要重新配对（数据也不进入备份，见 backup_rules.xml）。
 * - 不要求用户解锁设备或验证身份：前台服务在锁屏时也要读取 token 重连。
 *   代价是手机解锁过一次之后，能在 App 进程里运行代码的人可以用这个密钥解密；这在 security.md 的防御范围之外（root、恶意软件）。
 */
object KeystoreKeys {
    private const val PROVIDER = "AndroidKeyStore"
    private const val HOSTS_ALIAS = "dshremote.hosts.v1"
    private const val KEY_BITS = 256

    @Synchronized
    fun getOrCreate(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    /** 加密主机凭据用的 SecretBox。 */
    fun hostsSecretBox(): SecretBox = AesGcmSecretBox { getOrCreate(HOSTS_ALIAS) }
}
