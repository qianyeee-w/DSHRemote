package io.github.qianyeeew.dshremote.data.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairUriTest {
    private val fp = "Oaj69GfnJH_P-CmHX0B6Vxe-YBlVHoveBj7rSEae14M"
    private val valid = "dshremote://pair?v=1&h=192.168.3.26&p=3290&fp=$fp&c=K7M2Q9XP&n=MY-PC"

    @Test
    fun parsesUriGeneratedByPlugin() {
        // 插件 src/admin/page.js 的 buildPairingUri 生成的原样输出（URLSearchParams 编码：空格为 +，& 和 = 被转义）
        val fromPlugin =
            "dshremote://pair?v=1&h=192.168.3.26&p=3290&fp=$fp&c=K7M2Q9XP&n=MY+PC+%E5%B7%A5%E4%BD%9C%E7%AB%99%26a%3Db"
        val uri = assertNotNull(PairUri.parse(fromPlugin))
        assertEquals(HostAddress("192.168.3.26", 3290), uri.address)
        assertEquals(fp, uri.fingerprint.value)
        assertEquals("K7M2Q9XP", uri.code)
        assertEquals("MY PC 工作站&a=b", uri.name)
    }

    @Test
    fun fieldOrderDoesNotMatter() {
        val uri = PairUri.parse("dshremote://pair?n=MY-PC&c=K7M2Q9XP&fp=$fp&p=3290&h=192.168.3.26&v=1")
        assertEquals("MY-PC", assertNotNull(uri).name)
    }

    @Test
    fun toStringHidesPairingCode() {
        val text = assertNotNull(PairUri.parse(valid)).toString()
        assertFalse(text.contains("K7M2Q9XP"))
        assertTrue(text.contains("192.168.3.26:3290"))
    }

    @Test
    fun cleansName() {
        fun nameOf(encoded: String) = assertNotNull(PairUri.parse(valid.replace("n=MY-PC", "n=$encoded"))).name
        // 控制字符、方向控制字符被去掉
        assertEquals("evilpc", nameOf("evil%0A%E2%80%AEpc%07"))
        // 截断到 64 个字符
        assertEquals("a".repeat(HostNames.MAX_LENGTH), nameOf("a".repeat(100)))
        // 清理后为空时用地址代替
        assertEquals("192.168.3.26", nameOf("%0A%0D+"))
        assertEquals("192.168.3.26", nameOf(""))
    }

    @Test
    fun rejectsWrongSchemeOrShape() {
        val bad =
            listOf(
                "",
                "dshremote://pair",
                "dshremote://pair?",
                valid.replace("dshremote://", "https://"),
                valid.replace("dshremote://pair?", "DSHREMOTE://pair?"),
                valid.replace("://pair?", "://pairing?"),
                valid.replace("://pair?", "://pair/?"),
                "$valid#frag",
                "$valid&",
                valid.replace("&p=", "&&p="),
                valid.replace("&p=3290", "&p"),
                valid + "&x=" + "a".repeat(1100), // 超长
            )
        bad.forEach { assertRejected(it) }
    }

    @Test
    fun rejectsMissingExtraOrDuplicateFields() {
        for (field in listOf("v=1&", "&h=192.168.3.26", "&p=3290", "&fp=$fp", "&c=K7M2Q9XP", "&n=MY-PC")) {
            assertRejected(valid.replace(field, ""))
        }
        assertRejected("$valid&extra=1")
        assertRejected("$valid&c=K7M2Q9XP")
        assertRejected("$valid&v=1")
    }

    @Test
    fun rejectsWrongVersion() {
        for (v in listOf("0", "2", "01", "1.0", "", "v1")) assertRejected(valid.replace("v=1", "v=$v"))
    }

    @Test
    fun rejectsHostThatIsNotIpv4() {
        for (h in listOf("my-pc.local", "fd7a::1", "%5Bfd7a::1%5D", "256.1.1.1", "01.2.3.4", "1.2.3", "1.2.3.4.5", "", "1.2.3.4%20")) {
            assertRejected(valid.replace("h=192.168.3.26", "h=$h"))
        }
    }

    @Test
    fun rejectsBadPort() {
        for (p in listOf("0", "65536", "-1", "abc", "03290", "", "3290.0", "+3290")) {
            assertRejected(valid.replace("p=3290", "p=$p"))
        }
    }

    @Test
    fun rejectsBadFingerprint() {
        val bad = listOf(fp.dropLast(1), fp + "A", "$fp=", fp.replace('-', '+'), fp.replace('_', '/'), "")
        bad.forEach { assertRejected(valid.replace("fp=$fp", "fp=$it")) }
    }

    @Test
    fun rejectsBadCode() {
        // 小写、长度不对、含易混淆字符 0/O/1/I/L、含连字符或空格
        for (c in listOf(
            "k7m2q9xp",
            "K7M2Q9X",
            "K7M2Q9XPA",
            "K7M2Q9X0",
            "K7M2Q9XO",
            "K7M2Q9X1",
            "K7M2Q9XI",
            "K7M2Q9XL",
            "K7M2-Q9XP",
            "K7M2+Q9X",
        )) {
            assertRejected(valid.replace("c=K7M2Q9XP", "c=$c"))
        }
    }

    @Test
    fun rejectsBadPercentEncoding() {
        assertRejected(valid.replace("n=MY-PC", "n=%E5%B7%"))
        assertRejected(valid.replace("n=MY-PC", "n=%ZZ"))
    }

    @Test
    fun codeValidationMatchesPluginAlphabet() {
        assertEquals(31, PairUri.CODE_ALPHABET.length)
        for (ch in "0O1IL") assertFalse(ch in PairUri.CODE_ALPHABET)
        assertTrue(PairUri.isValidCode("ABCDEFGH"))
        assertFalse(PairUri.isValidCode("abcdefgh"))
    }

    private fun assertRejected(text: String) {
        assertNull("应拒绝：$text", PairUri.parse(text))
    }

    private fun <T : Any> assertNotNull(value: T?): T = checkNotNull(value) { "应解析成功" }
}
