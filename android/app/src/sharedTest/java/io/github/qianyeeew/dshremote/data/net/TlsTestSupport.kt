package io.github.qianyeeew.dshremote.data.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 证书校验测试共用的工具。src/sharedTest 同时加入 JVM 单元测试和真机测试，
 * 同一组测试在电脑的 JDK 和手机的 Conscrypt 上各跑一遍。
 */
internal object TlsTestSupport {
    val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

    /**
     * 测试用的服务器证书。证书上的名字故意和连接地址（127.0.0.1）不一致，
     * 用来证明 App 不比对主机名、只认指纹。
     */
    fun serverCertificate(keyPair: java.security.KeyPair? = null): HeldCertificate =
        HeldCertificate
            .Builder()
            .commonName("DSHRemote")
            .addSubjectAlternativeName("example.invalid")
            .apply { if (keyPair != null) keyPair(keyPair) }
            .build()

    fun serverHandshake(cert: HeldCertificate): HandshakeCertificates = HandshakeCertificates.Builder().heldCertificate(cert).build()

    /** 启动只提供 HTTPS 的 MockWebServer，监听 127.0.0.1。 */
    fun httpsServer(
        cert: HeldCertificate,
        responses: Int = 1,
    ): MockWebServer =
        MockWebServer().apply {
            useHttps(serverHandshake(cert).sslSocketFactory())
            repeat(responses) { enqueue(MockResponse.Builder().body("ok").build()) }
            start(LOOPBACK, 0)
        }

    fun MockWebServer.loopbackUrl(path: String = "/"): String = "https://127.0.0.1:$port$path"

    /** 插件生成的证书（src/sharedTest/resources/plugin-certs/，说明见该目录的 README.md）。 */
    fun pluginCertificate(name: String): X509Certificate {
        val bytes = resource("plugin-certs/$name")
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    /** 插件算出的指纹：fingerprints.json 中「文件名 → 指纹」的映射。格式固定，用正则读取，避免为测试引入 JSON 库。 */
    fun pluginFingerprints(): Map<String, String> {
        val json = resource("plugin-certs/fingerprints.json").decodeToString()
        return Regex("\"([^\"]+\\.crt)\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"")
            .findAll(json)
            .associate { it.groupValues[1] to it.groupValues[2] }
            .also { check(it.size >= 3) { "fingerprints.json 格式不对" } }
    }

    private fun resource(path: String): ByteArray =
        checkNotNull(TlsTestSupport::class.java.classLoader?.getResourceAsStream(path)) { "缺少测试资源 $path" }
            .use { it.readBytes() }
}
