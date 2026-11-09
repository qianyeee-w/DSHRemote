package io.github.qianyeeew.dshremote.data.host

import io.github.qianyeeew.dshremote.data.net.Base64Url
import io.github.qianyeeew.dshremote.data.net.Fingerprint

/**
 * 已配对的一台电脑。
 *
 * [credentials] 为 null 表示本机保存的凭据无法解密（例如 Keystore 中的密钥已经不存在），
 * 这台电脑需要重新配对。
 */
data class Host(
    /** App 本地生成的 id，和插件无关。 */
    val hostId: String,
    /** 电脑名，配对时取自插件，用户可以重命名。 */
    val name: String,
    /** 至少一个地址；连接时先试 [lastGoodAddress]，再同时试其他地址（m2-android.md 3.3）。 */
    val addresses: List<HostAddress>,
    val lastGoodAddress: HostAddress?,
    /** 插件分配的设备 id（`d_...`），不是秘密。 */
    val deviceId: String,
    val credentials: Credentials?,
)

/** 连接这台电脑需要的秘密信息：证书指纹和设备 token。只保存在 Keystore 加密的存储中。 */
data class Credentials(
    val fingerprint: Fingerprint,
    val token: DeviceToken,
)

/**
 * 设备 token（protocol.md 2.4：随机 32 字节，base64url）。
 *
 * [toString] 不输出 token（红线第 5 条）：包含它的 [Credentials]、[Host] 等 data class 打印时也不会泄露。
 * 只有组装 `Authorization` 请求头时才读取 [value]。
 */
class DeviceToken private constructor(
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is DeviceToken && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "DeviceToken(***)"

    companion object {
        /** 格式不对（不是 43 个字符的规范 base64url）时返回 null。错误信息中不包含输入的内容。 */
        fun parse(text: String): DeviceToken? = if (Base64Url.decode32(text) != null) DeviceToken(text) else null
    }
}

/** 电脑名的清理规则：二维码中的 `n` 和用户重命名都用它。 */
object HostNames {
    const val MAX_LENGTH = 64

    /** 去掉控制字符和首尾空白，按 Unicode 字符截断到 [MAX_LENGTH] 个。结果可能为空字符串。 */
    fun clean(raw: String): String {
        val withoutControls =
            buildString {
                var i = 0
                while (i < raw.length) {
                    val codePoint = raw.codePointAt(i)
                    if (!isControlOrFormat(codePoint)) appendCodePoint(codePoint)
                    i += Character.charCount(codePoint)
                }
            }.trim()
        if (withoutControls.codePointCount(0, withoutControls.length) <= MAX_LENGTH) return withoutControls
        return withoutControls.substring(0, withoutControls.offsetByCodePoints(0, MAX_LENGTH)).trim()
    }

    /** 控制字符，以及方向控制等不可见的格式字符（防止名字在界面上显示成别的样子）。 */
    private fun isControlOrFormat(codePoint: Int): Boolean =
        when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
            Character.SURROGATE,
            -> true

            else -> false
        }
}
