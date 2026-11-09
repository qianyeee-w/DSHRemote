package io.github.qianyeeew.dshremote.data.net

import io.github.qianyeeew.dshremote.data.net.TlsTestSupport.loopbackUrl
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * 两道指纹检查和 ClientFactory 的测试（m2-android.md 第 4 节的网络反向用例）。
 * 在 JVM 和真机上各跑一遍（src/sharedTest）。
 */
class PinnedTlsTest {
    private val serverCert = TlsTestSupport.serverCertificate()
    private val rightPin = Fingerprint.of(serverCert.certificate)
    private val wrongPin = Fingerprint.of(TlsTestSupport.serverCertificate().certificate)

    // ---- PinnedTrustManager：直接调用 ----

    @Test
    fun trustManagerAcceptsMatchingFingerprint() {
        PinnedTrustManager(rightPin).checkServerTrusted(arrayOf(serverCert.certificate), "ECDHE_ECDSA")
    }

    @Test
    fun trustManagerRejectsWrongFingerprint() {
        assertThrows<FingerprintMismatchException> {
            PinnedTrustManager(wrongPin).checkServerTrusted(arrayOf(serverCert.certificate), "ECDHE_ECDSA")
        }
    }

    @Test
    fun trustManagerRejectsMissingCertificate() {
        val tm = PinnedTrustManager(rightPin)
        assertThrows<CertificateException> { tm.checkServerTrusted(arrayOf(), "ECDHE_ECDSA") }
        assertThrows<CertificateException> { tm.checkServerTrusted(null, "ECDHE_ECDSA") }
    }

    @Test
    fun trustManagerOnlyTrustsLeafCertificate() {
        // 指纹对应的证书出现在证书链后面（服务器可以随意附带）不算数，只看第一张
        val attacker = TlsTestSupport.serverCertificate().certificate
        assertThrows<FingerprintMismatchException> {
            PinnedTrustManager(rightPin).checkServerTrusted(arrayOf(attacker, serverCert.certificate), "ECDHE_ECDSA")
        }
    }

    @Test
    fun trustManagerTrustsNoIssuersAndNoClients() {
        val tm = PinnedTrustManager(rightPin)
        assertEquals(0, tm.acceptedIssuers.size)
        assertThrows<CertificateException> { tm.checkClientTrusted(arrayOf(serverCert.certificate), "ECDHE_ECDSA") }
    }

    // ---- PinnedHostnameVerifier：用真实握手得到的 SSLSession ----

    @Test
    fun hostnameVerifierAloneRejectsWrongFingerprint() {
        TlsTestSupport.httpsServer(serverCert).use { server ->
            // TrustManager 放行（指纹正确），只检验 HostnameVerifier 自己的判断
            handshake(server, PinnedTrustManager(rightPin)).use { socket ->
                val session = socket.session
                assertTrue(PinnedHostnameVerifier(rightPin).verify("127.0.0.1", session))
                assertFalse(PinnedHostnameVerifier(wrongPin).verify("127.0.0.1", session))
                // 主机名参数不影响结果
                assertTrue(PinnedHostnameVerifier(rightPin).verify("anything.invalid", session))
                assertFalse(PinnedHostnameVerifier(wrongPin).verify("example.invalid", session))
            }
        }
    }

    @Test
    fun hostnameVerifierRejectsSessionWithoutCertificate() {
        assertFalse(PinnedHostnameVerifier(rightPin).verify("127.0.0.1", null))
        // 还没握手的 SSLEngine 的 session 没有对端证书（getPeerCertificates 抛出 SSLPeerUnverifiedException）。
        // 不用「握手失败的连接」来构造：手机上的 Conscrypt 在握手失败后仍然保留对端证书。
        val emptySession =
            SSLContext
                .getInstance("TLS")
                .apply { init(null, null, null) }
                .createSSLEngine()
                .session
        assertThrows<SSLPeerUnverifiedException> { emptySession.peerCertificates }
        assertFalse(PinnedHostnameVerifier(rightPin).verify("127.0.0.1", emptySession))
    }

    // ---- ClientFactory：经过 OkHttp 的完整请求 ----

    @Test
    fun clientWithRightPinConnectsEvenThoughNameDoesNotMatch() {
        TlsTestSupport.httpsServer(serverCert).use { server ->
            // 证书上的名字是 example.invalid，连接地址是 127.0.0.1：不比对主机名，只认指纹
            val body = get(ClientFactory().forPin(rightPin), server.loopbackUrl())
            assertEquals("ok", body)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun clientWithWrongPinIsRejectedBeforeAnyRequest() {
        TlsTestSupport.httpsServer(serverCert).use { server ->
            val error = assertThrows<IOException> { get(ClientFactory().forPin(wrongPin), server.loopbackUrl()) }
            assertTrue("应识别为指纹不匹配：$error", error.isFingerprintMismatch())
            assertNoRequestReceived(server)
        }
    }

    @Test
    fun clientAcceptsCertificateReissuedWithSameKey() {
        // 插件到期前用同一把私钥重新签发证书，指纹不变，不需要重新配对
        val renewed = TlsTestSupport.serverCertificate(serverCert.keyPair)
        TlsTestSupport.httpsServer(renewed).use { server ->
            assertEquals("ok", get(ClientFactory().forPin(rightPin), server.loopbackUrl()))
        }
    }

    @Test
    fun clientRejectsCertificateWithSameNameButDifferentKey() {
        val impostor = TlsTestSupport.serverCertificate()
        TlsTestSupport.httpsServer(impostor).use { server ->
            val error = assertThrows<IOException> { get(ClientFactory().forPin(rightPin), server.loopbackUrl()) }
            assertTrue(error.isFingerprintMismatch())
            assertNoRequestReceived(server)
        }
    }

    @Test
    fun clientsWithDifferentPinsDoNotShareConnections() {
        val factory = ClientFactory()
        TlsTestSupport.httpsServer(serverCert, responses = 2).use { server ->
            assertEquals("ok", get(factory.forPin(rightPin), server.loopbackUrl()))
            // 连接池里已经有一条通过了校验的连接；另一个指纹的客户端不能复用它
            val error = assertThrows<IOException> { get(factory.forPin(wrongPin), server.loopbackUrl()) }
            assertTrue(error.isFingerprintMismatch())
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun clientRefusesCleartextHttp() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("ok").build())
            server.start(TlsTestSupport.LOOPBACK, 0)
            assertThrows<UnknownServiceException> {
                get(ClientFactory().forPin(rightPin), "http://127.0.0.1:${server.port}/")
            }
            assertNoRequestReceived(server)
        }
    }

    @Test
    fun clientConfigurationHasNoEscapeHatches() {
        val client = ClientFactory().forPin(rightPin)
        assertEquals(listOf(ClientFactory.TLS_SPEC), client.connectionSpecs)
        assertFalse(client.connectionSpecs.contains(ConnectionSpec.CLEARTEXT))
        assertEquals(listOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2), client.connectionSpecs.single().tlsVersions)
        assertTrue(client.hostnameVerifier is PinnedHostnameVerifier)
        assertTrue(client.x509TrustManager is PinnedTrustManager)
        assertTrue("不能有拦截器（例如记录请求头的日志拦截器）", client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertNull(client.cache)
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar)
    }

    @Test
    fun isFingerprintMismatchIgnoresUnrelatedErrors() {
        assertFalse(IOException("connection refused").isFingerprintMismatch())
        assertFalse(SSLException("handshake_failure").isFingerprintMismatch())
        assertTrue(SSLException("wrapped", FingerprintMismatchException()).isFingerprintMismatch())
    }

    // ---- 工具 ----

    private fun get(
        client: OkHttpClient,
        url: String,
    ): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.body.string()
        }

    private fun rawSocket(
        server: MockWebServer,
        trustManager: PinnedTrustManager,
    ): SSLSocket {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return context.socketFactory.createSocket(TlsTestSupport.LOOPBACK, server.port) as SSLSocket
    }

    private fun handshake(
        server: MockWebServer,
        trustManager: PinnedTrustManager,
    ): SSLSocket = rawSocket(server, trustManager).also { it.startHandshake() }

    private fun assertNoRequestReceived(server: MockWebServer) {
        assertEquals(0, server.requestCount)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("期望 ${T::class.java.simpleName}，实际是 $e", e)
        }
        fail("期望 ${T::class.java.simpleName}，但没有抛出异常")
        throw IllegalStateException()
    }
}
