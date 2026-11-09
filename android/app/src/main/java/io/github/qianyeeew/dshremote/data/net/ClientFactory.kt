package io.github.qianyeeew.dshremote.data.net

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * 创建访问插件用的 OkHttpClient。**App 的所有网络请求都必须用这里创建的客户端**（android/AGENTS.md「约定」）。
 *
 * 每台主机一个客户端，[forPin] 的参数是这台主机配对时保存的指纹。客户端的设置：
 * - 两道指纹检查（[PinnedTrustManager]、[PinnedHostnameVerifier]），不使用系统的信任链；
 * - 只允许 TLS 1.2 和 1.3，不允许明文 HTTP（没有 `ConnectionSpec.CLEARTEXT`，`http://` 的请求直接失败）；
 * - 不跟随重定向，不保存 cookie，不使用缓存，不加任何拦截器（不能有记录请求头的日志拦截器）。
 *
 * 各主机的客户端共用同一个连接池和线程池。OkHttp 只会复用 SSLSocketFactory 和 HostnameVerifier
 * 都相同的连接，而每次调用 [forPin] 都会新建这两个对象，所以不同指纹的客户端不会拿到彼此的连接
 * （PinnedTlsTest 中有对应的测试）。
 */
class ClientFactory {
    private val base: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionSpecs(listOf(TLS_SPEC))
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    fun forPin(pin: Fingerprint): OkHttpClient {
        val trustManager = PinnedTrustManager(pin)
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return base
            .newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(PinnedHostnameVerifier(pin))
            .build()
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 10L

        /** protocol.md 第 1 节：只接受 TLS 1.2 及以上。PairingProbe 也用这个列表。 */
        internal val TLS_VERSIONS = listOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)

        internal val TLS_SPEC: ConnectionSpec =
            ConnectionSpec
                .Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(*TLS_VERSIONS.toTypedArray())
                .build()
    }
}
