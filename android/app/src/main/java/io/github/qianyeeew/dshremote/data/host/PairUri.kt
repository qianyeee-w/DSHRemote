package io.github.qianyeeew.dshremote.data.host

import io.github.qianyeeew.dshremote.data.net.Fingerprint
import java.net.URLDecoder

/**
 * 配对二维码的内容（protocol.md 2.1）：
 * `dshremote://pair?v=1&h=<IPv4>&p=<端口>&fp=<指纹>&c=<配对码>&n=<电脑名>`
 *
 * 解析是严格的：任何一项不合法、缺少字段、多出字段或字段重复，都拒绝整个二维码。
 * 二维码可能来自任何地方，所以这里不尝试「修正」内容。
 *
 * [toString] 不输出配对码（红线第 5 条）。
 */
class PairUri private constructor(
    val address: HostAddress,
    val fingerprint: Fingerprint,
    val code: String,
    /** 清理后的电脑名（[HostNames.clean]）；清理后为空时用地址代替。 */
    val name: String,
) {
    override fun toString(): String = "PairUri(address=$address, fingerprint=$fingerprint, code=***, name=$name)"

    companion object {
        private const val PREFIX = "dshremote://pair?"
        private const val MAX_URI_LENGTH = 1024
        private val FIELDS = setOf("v", "h", "p", "fp", "c", "n")

        /** 和插件 `src/pairing/codes.js` 的 CODE_ALPHABET、CODE_LENGTH 一致：8 位，不含 0/O/1/I/L。 */
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 8

        /** 解析扫到的二维码文本。不是合法的配对二维码时返回 null。 */
        fun parse(text: String): PairUri? {
            if (text.length > MAX_URI_LENGTH || !text.startsWith(PREFIX)) return null
            val query = text.substring(PREFIX.length)
            if ('#' in query) return null
            val fields = parseQuery(query) ?: return null
            if (fields.keys != FIELDS) return null

            if (fields["v"] != "1") return null
            val host = fields.getValue("h")
            // protocol.md 2.1：二维码中的地址只会是一个 IPv4
            if (!HostAddress.isIpv4(host)) return null
            val address = HostAddress.of(host, fields.getValue("p")) ?: return null
            val fingerprint = Fingerprint.parse(fields.getValue("fp")) ?: return null
            val code = fields.getValue("c")
            if (!isValidCode(code)) return null
            val name = HostNames.clean(fields.getValue("n")).ifEmpty { host }
            return PairUri(address, fingerprint, code, name)
        }

        /** 配对码只接受插件生成的格式（大写，字符集见 [CODE_ALPHABET]）。 */
        fun isValidCode(code: String): Boolean = code.length == CODE_LENGTH && code.all { it in CODE_ALPHABET }

        /**
         * 拆分 `k=v&k=v`，按 `application/x-www-form-urlencoded` 解码（插件用 URLSearchParams 生成，空格编码为 `+`）。
         * 有重复的键、空的片段、没有 `=` 或编码错误时返回 null。
         */
        private fun parseQuery(query: String): Map<String, String>? {
            val result = mutableMapOf<String, String>()
            for (part in query.split('&')) {
                val eq = part.indexOf('=')
                if (eq <= 0) return null
                val key = decode(part.substring(0, eq)) ?: return null
                val value = decode(part.substring(eq + 1)) ?: return null
                if (result.put(key, value) != null) return null
            }
            return result
        }

        private fun decode(text: String): String? =
            try {
                URLDecoder.decode(text, Charsets.UTF_8.name())
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
