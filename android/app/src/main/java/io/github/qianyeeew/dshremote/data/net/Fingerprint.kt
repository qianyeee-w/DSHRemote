package io.github.qianyeeew.dshremote.data.net

import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * 插件证书的公钥指纹（protocol.md 第 1 节）：证书公钥（SubjectPublicKeyInfo，DER 编码）的 SHA-256，
 * base64url 编码，不加填充，固定 43 个字符。
 *
 * 固定的是公钥而不是整张证书：插件用同一把私钥重新签发证书时，指纹不变。
 * 计算方法必须和插件的 `plugin/src/tls/certificate.js` 中的 `spkiFingerprint` 完全一致，
 * 由测试中的插件证书固定数据（`src/sharedTest/resources/plugin-certs/`）保证。
 *
 * 指纹不是秘密（配对页上会显示给用户核对），所以 [toString] 直接输出指纹。
 */
class Fingerprint private constructor(
    private val bytes: ByteArray,
) {
    /** base64url（无填充）形式，和插件、二维码中的 `fp` 字段一致。 */
    val value: String = Base64Url.encode(bytes)

    /** 恒定时间比较：比较所用的时间不随第一个不同字节的位置变化。 */
    override fun equals(other: Any?): Boolean = other is Fingerprint && constantTimeEquals(bytes, other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = value

    companion object {
        /** SHA-256 为 32 字节，base64url 不加填充是 43 个字符。 */
        const val LENGTH = Base64Url.LENGTH_32

        /**
         * 解析 base64url 形式的指纹。格式不对时返回 null：长度不是 43、带填充 `=`、
         * 含标准 base64 的 `+` `/` 或其他字符、最后一个字符的多余位不为零（非规范编码）。
         */
        fun parse(text: String): Fingerprint? = Base64Url.decode32(text)?.let { Fingerprint(it) }

        /** 计算证书公钥的指纹。 */
        fun of(certificate: X509Certificate): Fingerprint = ofSpki(certificate.publicKey.encoded)

        /** 计算 SubjectPublicKeyInfo（DER）的指纹。 */
        fun ofSpki(spki: ByteArray): Fingerprint = Fingerprint(MessageDigest.getInstance("SHA-256").digest(spki))

        private fun constantTimeEquals(
            a: ByteArray,
            b: ByteArray,
        ): Boolean {
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) {
                diff = diff or (a[i].toInt() xor b[i].toInt())
            }
            return diff == 0
        }
    }
}
