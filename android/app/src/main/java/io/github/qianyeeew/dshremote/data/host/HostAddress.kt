package io.github.qianyeeew.dshremote.data.host

/**
 * 电脑的一个地址：IPv4、IPv6 或主机名，加端口。
 *
 * **地址不是信任的依据，指纹才是**（docs/security.md「App 端的证书校验」）：连到错误的地址时指纹校验失败，
 * token 不会发出。所以这里只检查格式，用户可以随时添加和修改地址，不需要重新配对。
 */
data class HostAddress(
    val host: String,
    val port: Int,
) {
    init {
        require(isValidHost(host)) { "地址格式不对" }
        require(port in 1..MAX_PORT) { "端口超出范围" }
    }

    /** IPv6 地址加方括号，例如 `[fd7a::1]:3190`。 */
    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        const val MAX_PORT = 65_535
        private const val MAX_HOSTNAME = 253
        private const val MAX_LABEL = 63
        private const val MAX_IPV6 = 45

        private val IPV4 = Regex("^(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}$")
        private val IPV6 = Regex("^[0-9A-Fa-f:.]+$")
        private val LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$")
        private val PORT = Regex("^[1-9][0-9]{0,4}$")

        /**
         * 解析用户输入的 `地址:端口`，例如 `192.168.1.5:3190`、`my-pc.tailnet.ts.net:3190`、`[fd7a::1]:3190`。
         * 前后的空白会被去掉；格式不对时返回 null。
         */
        fun parse(text: String): HostAddress? {
            val input = text.trim()
            val (host, port) =
                if (input.startsWith("[")) {
                    val end = input.indexOf("]:")
                    if (end < 0) return null
                    input.substring(1, end) to input.substring(end + 2)
                } else {
                    val colon = input.lastIndexOf(':')
                    if (colon < 0) return null
                    val host = input.substring(0, colon)
                    // 不带方括号的 IPv6 无法和端口区分开
                    if (':' in host) return null
                    host to input.substring(colon + 1)
                }
            return of(host, port)
        }

        /** 分别给出地址和端口（例如手动配对时的两个输入框）。格式不对时返回 null。 */
        fun of(
            host: String,
            port: String,
        ): HostAddress? {
            if (!PORT.matches(port)) return null
            val number = port.toInt()
            if (number > MAX_PORT || !isValidHost(host)) return null
            return HostAddress(host, number)
        }

        fun isIpv4(host: String): Boolean = IPV4.matches(host) && host.split('.').all { it.toInt() <= 255 }

        private fun isValidHost(host: String): Boolean =
            when {
                isIpv4(host) -> true
                ':' in host -> host.length <= MAX_IPV6 && IPV6.matches(host) && host.count { it == ':' } >= 2
                else -> isValidHostname(host)
            }

        private fun isValidHostname(host: String): Boolean {
            val name = host.removeSuffix(".")
            if (name.isEmpty() || name.length > MAX_HOSTNAME) return false
            val labels = name.split('.')
            // 全是数字和点、但又不是合法 IPv4 的（例如 999.1.1.1），按非法处理，不当作主机名
            if (labels.all { label -> label.all { it.isDigit() } }) return false
            return labels.all { it.length <= MAX_LABEL && LABEL.matches(it) }
        }
    }
}
