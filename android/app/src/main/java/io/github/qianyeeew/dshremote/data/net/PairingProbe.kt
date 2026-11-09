package io.github.qianyeeew.dshremote.data.net

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * 配对探测：手动输入地址配对时，取得服务器证书的指纹，显示给用户和电脑上配对页的指纹核对
 * （docs/security.md「App 端的证书校验」、protocol.md 2.3）。
 *
 * 做法：建立 TCP 连接，开始 TLS 握手；专用的 TrustManager 在收到服务器证书时记下指纹，
 * 然后**一定抛出异常、中止握手**。握手从未完成，这条连接上不会发送任何应用数据（没有 HTTP 请求，没有配对码）。
 * 这里也不用 OkHttp，直接用 SSLSocket，确保不会有请求被组装出来。
 *
 * 探测得到的指纹只用于显示。用户核对一致后，才保存这个指纹，再用 [ClientFactory] 的正常客户端发送配对码。
 */
class PairingProbe(
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val handshakeTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) {
    sealed interface Result {
        /** 取得了服务器证书的指纹（握手已中止）。 */
        data class Fingerprinted(
            val fingerprint: Fingerprint,
        ) : Result

        /** 连不上：地址解析失败、连接被拒绝、网络不可达。 */
        data object Unreachable : Result

        /** 连接或握手超时。 */
        data object Timeout : Result

        /** 能连上，但对方没有完成 TLS 握手的前半段（例如不是 TLS 服务，或不支持 TLS 1.2 及以上）。 */
        data object NotTls : Result
    }

    suspend fun probe(
        host: String,
        port: Int,
    ): Result = withContext(Dispatchers.IO) { probeBlocking(host, port) }

    internal fun probeBlocking(
        host: String,
        port: Int,
    ): Result {
        require(port in 1..MAX_PORT) { "端口超出范围" }
        val trustManager = AbortingTrustManager()
        try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, port), connectTimeoutMillis)
                raw.soTimeout = handshakeTimeoutMillis
                val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
                // autoClose = true：关闭 SSLSocket 时一并关闭 raw
                (sslContext.socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { ssl ->
                    ssl.enabledProtocols = enabledProtocols(ssl.supportedProtocols)
                    ssl.startHandshake()
                }
            }
        } catch (_: SocketTimeoutException) {
            return trustManager.captured?.let { Result.Fingerprinted(it) } ?: Result.Timeout
        } catch (_: UnknownHostException) {
            return Result.Unreachable
        } catch (e: IOException) {
            // 握手被 AbortingTrustManager 中止时，异常（通常是 SSLHandshakeException）也走到这里
            val fingerprint = trustManager.captured
            if (fingerprint != null) return Result.Fingerprinted(fingerprint)
            return if (e is SSLException) Result.NotTls else Result.Unreachable
        }
        // AbortingTrustManager 总是抛出异常，握手不可能成功；万一成功了也不使用这条连接（上面已关闭）
        return Result.NotTls
    }

    /**
     * 只记录服务器证书的指纹，然后一定拒绝。它不判断证书是否可信，所以**只能用于探测**，
     * 不能用于发送任何数据的连接。关闭 lint 的 CustomX509TrustManager 是有意的：它从不放行任何证书。
     */
    @SuppressLint("CustomX509TrustManager")
    private class AbortingTrustManager : X509TrustManager {
        @Volatile
        var captured: Fingerprint? = null
            private set

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) {
            chain?.firstOrNull()?.let { captured = Fingerprint.of(it) }
            throw ProbeAbortedException()
        }

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ): Unit = throw CertificateException("不接受客户端证书")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class ProbeAbortedException : CertificateException("配对探测：取得指纹后中止握手")

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 5_000
        private const val MAX_PORT = 65_535

        /** 和 ClientFactory 相同：只用 TLS 1.3 和 1.2（Android 8、9 不支持 1.3 时只用 1.2）。 */
        internal fun enabledProtocols(supported: Array<String>): Array<String> =
            ClientFactory.TLS_VERSIONS
                .map { it.javaName }
                .filter { it in supported }
                .toTypedArray()
    }
}
