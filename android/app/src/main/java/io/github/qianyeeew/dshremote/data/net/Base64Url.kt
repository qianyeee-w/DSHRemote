package io.github.qianyeeew.dshremote.data.net

import java.util.Base64

/** 32 字节值的 base64url（无填充，43 个字符）编解码。指纹和设备 token 都是这种格式。 */
internal object Base64Url {
    const val LENGTH_32 = 43
    private const val BYTES = 32

    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val FORMAT = Regex("^[A-Za-z0-9_-]{$LENGTH_32}$")

    fun encode(bytes: ByteArray): String = ENCODER.encodeToString(bytes)

    /**
     * 解码 43 个字符的 base64url。格式不对时返回 null：长度不对、带填充 `=`、
     * 含标准 base64 的 `+` `/` 或其他字符、最后一个字符的多余位不为零（非规范编码）。
     */
    fun decode32(text: String): ByteArray? {
        if (!FORMAT.matches(text)) return null
        val decoded =
            try {
                Base64.getUrlDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (decoded.size != BYTES) return null
        // 同一组字节只接受一种写法，避免两个不同的字符串代表同一个值
        if (ENCODER.encodeToString(decoded) != text) return null
        return decoded
    }
}
