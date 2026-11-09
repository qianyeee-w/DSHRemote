package io.github.qianyeeew.dshremote.data.host

import io.github.qianyeeew.dshremote.data.net.Fingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HostModelTest {
    private val tokenText = "tok3n_SECRET-abcdefghijklmnopqrstuvwxyz0120"
    private val token = checkNotNull(DeviceToken.parse(tokenText))
    private val fingerprint = checkNotNull(Fingerprint.parse("Oaj69GfnJH_P-CmHX0B6Vxe-YBlVHoveBj7rSEae14M"))

    // ---- DeviceToken：不出现在 toString() 中 ----

    @Test
    fun tokenNeverAppearsInToString() {
        val credentials = Credentials(fingerprint, token)
        val host = Host("h1", "PC", listOf(HostAddress("192.168.1.2", 3190)), null, "d_1", credentials)
        for (text in listOf(token.toString(), credentials.toString(), host.toString(), listOf(host).toString())) {
            assertFalse("token 泄露：$text", text.contains(tokenText))
            assertFalse(text.contains("SECRET"))
        }
        assertEquals(tokenText, token.value)
    }

    @Test
    fun tokenParseIsStrict() {
        assertEquals(43, tokenText.length)
        for (bad in listOf("", tokenText.dropLast(1), tokenText + "A", "$tokenText=", tokenText.replace('_', '/'), " $tokenText")) {
            assertNull(bad, DeviceToken.parse(bad))
        }
        assertEquals(token, DeviceToken.parse(tokenText))
    }

    // ---- HostAddress ----

    @Test
    fun parsesAddresses() {
        assertEquals(HostAddress("192.168.1.5", 3190), HostAddress.parse("192.168.1.5:3190"))
        assertEquals(HostAddress("my-pc.tailnet.ts.net", 3190), HostAddress.parse(" my-pc.tailnet.ts.net:3190 "))
        assertEquals(HostAddress("fd7a:115c::1", 443), HostAddress.parse("[fd7a:115c::1]:443"))
        assertEquals(HostAddress("localhost", 1), HostAddress.parse("localhost:1"))
        assertEquals(HostAddress("100.64.0.1", 65535), HostAddress.of("100.64.0.1", "65535"))
    }

    @Test
    fun formatsAddresses() {
        assertEquals("192.168.1.5:3190", HostAddress("192.168.1.5", 3190).toString())
        assertEquals("[fd7a::1]:3190", HostAddress("fd7a::1", 3190).toString())
        // toString 和 parse 互为逆运算（编辑地址时显示的文字可以原样保存）
        for (text in listOf("192.168.1.5:3190", "[fd7a::1]:3190", "my-pc:8443")) {
            assertEquals(text, HostAddress.parse(text).toString())
        }
    }

    @Test
    fun rejectsBadAddresses() {
        val bad =
            listOf(
                "",
                "192.168.1.5",
                "192.168.1.5:",
                ":3190",
                "192.168.1.5:0",
                "192.168.1.5:65536",
                "192.168.1.5:03190",
                "192.168.1.5:31a0",
                "256.1.1.1:3190",
                "999.1.1.1:3190",
                "1.2.3:3190",
                "fd7a::1:3190", // IPv6 必须加方括号
                "[fd7a::1]",
                "[fd7a::1:3190",
                "[zz::1]:3190",
                "my pc:3190",
                "-pc:3190",
                "pc-:3190",
                "a..b:3190",
                "http://pc:3190",
                "pc/path:3190",
                "user@pc:3190",
                "${"a".repeat(64)}.com:3190",
            )
        for (text in bad) assertNull("应拒绝：$text", HostAddress.parse(text))
    }

    // ---- HostNames ----

    @Test
    fun cleansNames() {
        assertEquals("工作站", HostNames.clean("  工作站\n"))
        assertEquals("ab", HostNames.clean("a\u0000\u200Fb\u2028"))
        assertEquals("", HostNames.clean("\t\r\n"))
        // 按 Unicode 字符截断，不切开代理对
        val emoji = "😀".repeat(70)
        val cleaned = HostNames.clean(emoji)
        assertEquals(HostNames.MAX_LENGTH, cleaned.codePointCount(0, cleaned.length))
    }
}
