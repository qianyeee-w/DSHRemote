package io.github.qianyeeew.dshremote.data.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * 配对探测的测试：能取得指纹，并且握手一定中止、服务器收不到任何应用数据。
 * 在 JVM 和真机上各跑一遍（src/sharedTest）。
 */
class PairingProbeTest {
    private val serverCert = TlsTestSupport.serverCertificate()

    @Test
    fun returnsServerFingerprint() {
        TlsTestSupport.httpsServer(serverCert).use { server ->
            val result = runBlocking { PairingProbe().probe("127.0.0.1", server.port) }
            assertEquals(PairingProbe.Result.Fingerprinted(Fingerprint.of(serverCert.certificate)), result)
            // 握手被中止，HTTP 服务器上没有任何请求
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun handshakeIsAbortedAndServerReceivesNoApplicationData() {
        val serverSocket =
            TlsTestSupport
                .serverHandshake(serverCert)
                .sslContext()
                .serverSocketFactory
                .createServerSocket(0, 1, TlsTestSupport.LOOPBACK) as SSLServerSocket
        serverSocket.use {
            // 服务器端：完成握手（应当失败），再看能不能读到任何数据
            val observed =
                CompletableFuture.supplyAsync {
                    serverSocket.accept().use { socket ->
                        socket.soTimeout = SERVER_TIMEOUT_MILLIS
                        val handshake = runCatching { (socket as SSLSocket).startHandshake() }
                        val data = runCatching { socket.getInputStream().readBytes() }
                        handshake to data
                    }
                }
            val result = PairingProbe().probeBlocking("127.0.0.1", serverSocket.localPort)
            assertEquals(PairingProbe.Result.Fingerprinted(Fingerprint.of(serverCert.certificate)), result)

            val (handshake, data) = observed.get(SERVER_TIMEOUT_MILLIS * 2L, TimeUnit.MILLISECONDS)
            assertTrue("服务器端的握手必须失败", handshake.isFailure)
            // 握手失败后连接已断开：读取要么失败，要么读到 0 字节
            assertTrue("服务器不应收到任何应用数据", data.isFailure || data.getOrThrow().isEmpty())
        }
    }

    @Test
    fun reportsUnreachableWhenNothingListens() {
        val port = ServerSocket(0, 1, TlsTestSupport.LOOPBACK).use { it.localPort }
        assertEquals(PairingProbe.Result.Unreachable, PairingProbe().probeBlocking("127.0.0.1", port))
    }

    @Test
    fun reportsUnreachableForUnknownHost() {
        // .invalid 是保留的顶级域名，不会被解析
        assertEquals(PairingProbe.Result.Unreachable, PairingProbe().probeBlocking("dshremote.invalid", 3190))
    }

    @Test
    fun reportsNotTlsForPlainHttpServer() {
        ServerSocket(0, 1, TlsTestSupport.LOOPBACK).use { plain ->
            val server =
                CompletableFuture.runAsync {
                    plain.accept().use { socket ->
                        socket.soTimeout = SERVER_TIMEOUT_MILLIS
                        socket.getInputStream().read()
                        socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.getOutputStream().flush()
                        // 先半关闭并排空 ClientHello，避免 Windows 因未读数据发送 RST，吞掉已写出的 HTTP 响应。
                        socket.shutdownOutput()
                        runCatching {
                            val buffer = ByteArray(1024)
                            while (socket.getInputStream().read(buffer) != -1) { /* 等客户端结束探测 */ }
                        }
                    }
                }
            assertEquals(PairingProbe.Result.NotTls, PairingProbe().probeBlocking("127.0.0.1", plain.localPort))
            server.get(SERVER_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun reportsTimeoutWhenServerNeverAnswers() {
        ServerSocket(0, 1, TlsTestSupport.LOOPBACK).use { silent ->
            val accepted = CompletableFuture.supplyAsync { silent.accept() }
            val probe = PairingProbe(connectTimeoutMillis = 1_000, handshakeTimeoutMillis = 300)
            assertEquals(PairingProbe.Result.Timeout, probe.probeBlocking("127.0.0.1", silent.localPort))
            accepted.get(SERVER_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS).close()
        }
    }

    @Test
    fun rejectsPortOutOfRange() {
        for (port in listOf(0, -1, 65_536)) {
            val error = runCatching { PairingProbe().probeBlocking("127.0.0.1", port) }.exceptionOrNull()
            assertTrue("端口 $port 应被拒绝", error is IllegalArgumentException)
        }
    }

    @Test
    fun enablesOnlyTls12AndAbove() {
        val supported = arrayOf("SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")
        assertArrayEquals(arrayOf("TLSv1.3", "TLSv1.2"), PairingProbe.enabledProtocols(supported))
        // Android 8、9 不支持 TLS 1.3
        assertArrayEquals(arrayOf("TLSv1.2"), PairingProbe.enabledProtocols(arrayOf("TLSv1", "TLSv1.1", "TLSv1.2")))
        assertEquals(0, PairingProbe.enabledProtocols(arrayOf()).size)
    }

    private companion object {
        const val SERVER_TIMEOUT_MILLIS = 5_000
    }
}
