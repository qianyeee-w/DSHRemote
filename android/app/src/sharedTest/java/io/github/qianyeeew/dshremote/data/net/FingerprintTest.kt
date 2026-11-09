package io.github.qianyeeew.dshremote.data.net

import okhttp3.CertificatePinner
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerprintTest {
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    @Test
    fun matchesFingerprintsComputedByPlugin() {
        val expected = TlsTestSupport.pluginFingerprints()
        for ((file, fingerprint) in expected) {
            assertEquals(file, fingerprint, Fingerprint.of(TlsTestSupport.pluginCertificate(file)).value)
        }
    }

    @Test
    fun reissuedCertificateWithSameKeyKeepsFingerprint() {
        val first = TlsTestSupport.pluginCertificate("key-a-cert-1.crt")
        val renewed = TlsTestSupport.pluginCertificate("key-a-cert-2.crt")
        val otherKey = TlsTestSupport.pluginCertificate("key-b-cert.crt")
        // 两张证书本身不同（有效期、名称、序列号），公钥相同
        assertNotEquals(first.serialNumber, renewed.serialNumber)
        assertEquals(Fingerprint.of(first), Fingerprint.of(renewed))
        assertNotEquals(Fingerprint.of(first), Fingerprint.of(otherKey))
    }

    @Test
    fun matchesOkHttpsIndependentSpkiHash() {
        val certificates =
            listOf(
                HeldCertificate.Builder().ecdsa256().build(),
                HeldCertificate.Builder().rsa2048().build(),
            )
        for (held in certificates) {
            // CertificatePinner.pin 返回 "sha256/<标准 base64，带填充>"
            val okhttp = CertificatePinner.pin(held.certificate).removePrefix("sha256/")
            val asBase64Url = okhttp.replace('+', '-').replace('/', '_').trimEnd('=')
            assertEquals(asBase64Url, Fingerprint.of(held.certificate).value)
        }
    }

    @Test
    fun parseAcceptsCanonicalFingerprint() {
        val text = TlsTestSupport.pluginFingerprints().getValue("key-a-cert-1.crt")
        val parsed = checkNotNull(Fingerprint.parse(text))
        assertEquals(text, parsed.value)
        assertEquals(text, parsed.toString())
        assertEquals(Fingerprint.LENGTH, parsed.value.length)
    }

    @Test
    fun parseRejectsMalformedFingerprints() {
        val valid = TlsTestSupport.pluginFingerprints().getValue("key-a-cert-1.crt")
        // 最后一个字符只有高 4 位有效，低 2 位必须为 0；改成低位不为 0 的字符得到非规范编码
        val lastIndex = alphabet.indexOf(valid.last())
        val nonCanonical = valid.dropLast(1) + alphabet[lastIndex or 1]
        val malformed =
            listOf(
                "",
                valid.dropLast(1), // 42 个字符
                valid + "A", // 44 个字符
                "$valid=", // 带填充
                valid.dropLast(1) + "=",
                "+" + valid.drop(1), // 标准 base64 字符
                "/" + valid.drop(1),
                " " + valid.drop(1),
                valid.dropLast(1) + "\n",
                "é" + valid.drop(1),
                nonCanonical,
            )
        for (text in malformed) {
            assertNull("应拒绝：<$text>", Fingerprint.parse(text))
        }
    }

    @Test
    fun equalityComparesBytes() {
        val text = TlsTestSupport.pluginFingerprints().getValue("key-a-cert-1.crt")
        val other = TlsTestSupport.pluginFingerprints().getValue("key-b-cert.crt")
        assertEquals(Fingerprint.parse(text), Fingerprint.parse(text))
        assertEquals(Fingerprint.parse(text).hashCode(), Fingerprint.parse(text).hashCode())
        assertNotEquals(Fingerprint.parse(text), Fingerprint.parse(other))
        assertNotEquals(Fingerprint.parse(text) as Any, text)
    }
}
