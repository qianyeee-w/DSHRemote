package io.github.qianyeeew.dshremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 安全红线第 4 条 / ADR 0003：App 不得发出明文 HTTP。
 * 直接检查源码中的 manifest 和 network_security_config，防止以后有人「为了调试」放开明文。
 */
class CleartextPolicyTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    // Gradle 运行单元测试时，工作目录是模块目录（android/app）
    private fun parse(path: String): Element {
        val file = File(path)
        assertTrue("找不到 ${file.absolutePath}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    @Test
    fun `manifest 禁止明文流量并引用 network_security_config`() {
        val application = parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttributeNS(androidNs, "usesCleartextTraffic"))
        assertEquals("@xml/network_security_config", application.getAttributeNS(androidNs, "networkSecurityConfig"))
        assertEquals("false", application.getAttributeNS(androidNs, "allowBackup"))
    }

    @Test
    fun `network_security_config 只有一个禁止明文的 base-config`() {
        val root = parse("src/main/res/xml/network_security_config.xml")
        val base = root.getElementsByTagName("base-config")
        assertEquals(1, base.length)
        assertEquals("false", (base.item(0) as Element).getAttribute("cleartextTrafficPermitted"))
        // 【反向】不允许按域名或调试构建放开明文，也不允许在这里配置信任锚（指纹校验在 data/net/）
        for (tag in listOf("domain-config", "debug-overrides", "trust-anchors", "pin-set")) {
            assertEquals("network_security_config 中不应出现 <$tag>", 0, root.getElementsByTagName(tag).length)
        }
    }

    @Test
    fun `没有针对特定构建类型的 manifest 或 network_security_config 覆盖`() {
        // debug / release 目录中的同名文件会覆盖 main 中的配置
        for (variant in listOf("debug", "release")) {
            assertTrue(!File("src/$variant/AndroidManifest.xml").exists())
            assertTrue(!File("src/$variant/res/xml/network_security_config.xml").exists())
        }
    }
}
