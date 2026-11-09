package io.github.qianyeeew.dshremote.data.net

import android.annotation.SuppressLint
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/*
 * App 端证书校验的两道检查（docs/security.md「App 端的证书校验」），按要求写在同一个文件里。
 *
 * 插件的证书是自签名的，电脑的地址也会变化，所以两道默认检查都改为只认配对时保存的指纹：
 * - PinnedTrustManager：握手过程中比对服务器证书的公钥指纹，不一致就中止握手。
 * - PinnedHostnameVerifier：握手完成后、发送任何请求之前，取握手得到的证书再比对一次指纹。
 *   它以指纹比对代替主机名比对，不是「全部放行」。
 * 任何一道写错，另一道仍然能拦住中间人。两者都不使用系统的信任链，也不比对主机名和有效期
 * （protocol.md 第 1 节：App 只校验指纹）。
 *
 * 这两个类只由 ClientFactory 使用；PairingProbe 有自己专用的 TrustManager。
 */

/** 服务器证书的指纹和配对时保存的不一致。界面据此提示「需要重新配对」，不提供「仍然继续」（红线第 2 条）。 */
class FingerprintMismatchException : CertificateException("服务器证书的指纹和配对时保存的不一致")

/**
 * 只信任公钥指纹等于 [pin] 的服务器证书。
 *
 * 只看证书链的第一张（服务器自己的证书）：证书链里后面的证书由服务器随意提供，不能作为信任依据。
 *
 * 关闭 lint 的 CustomX509TrustManager（提醒「自己实现 TrustManager 容易出错」）是有意的：
 * 自签名证书只能靠指纹校验（ADR 0003）。测试见 PinnedTlsTest，在 JVM 和真机上各跑一遍。
 */
@SuppressLint("CustomX509TrustManager")
class PinnedTrustManager(
    private val pin: Fingerprint,
) : X509TrustManager {
    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("服务器没有提供证书")
        if (Fingerprint.of(leaf) != pin) throw FingerprintMismatchException()
    }

    /** App 只作为客户端，不接受任何客户端证书。 */
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ): Unit = throw CertificateException("不接受客户端证书")

    /** 不信任任何证书颁发机构。 */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * 以指纹比对代替主机名比对：握手得到的服务器证书的指纹等于 [pin] 时才通过，其他任何情况都不通过。
 * 不看 `hostname` 参数：用户可能用 IP、主机名或 Tailscale 地址连接，地址不是信任的依据（protocol.md 第 1 节）。
 */
class PinnedHostnameVerifier(
    private val pin: Fingerprint,
) : HostnameVerifier {
    override fun verify(
        hostname: String?,
        session: SSLSession?,
    ): Boolean {
        if (session == null) return false
        val leaf =
            try {
                session.peerCertificates.firstOrNull() as? X509Certificate
            } catch (_: SSLPeerUnverifiedException) {
                null
            } ?: return false
        return Fingerprint.of(leaf) == pin
    }
}

/**
 * 判断一次连接失败是不是因为指纹校验没有通过（任何一道检查拦下都算）。
 * 是的话，界面显示「指纹不匹配，需要重新配对」，并且不再自动重连。
 */
fun Throwable.isFingerprintMismatch(): Boolean =
    generateSequence(this) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .any { it is FingerprintMismatchException || it is SSLPeerUnverifiedException }

private const val MAX_CAUSE_DEPTH = 16
