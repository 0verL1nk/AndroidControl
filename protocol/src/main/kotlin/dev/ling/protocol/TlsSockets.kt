package dev.ling.protocol

import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * 带**证书指纹固定**的 TLS 连接。
 *
 * 中继用自签证书即可：部署者把证书的 SHA-256 指纹配到长辈端，长辈端只认这张证书。
 * 这样不依赖公共 CA，也不会因为某张根证书被误签而把别人当成我们的节点。
 *
 * 指纹优先于主机名：连 IP 地址或域名都行，证书换了就必须重新配置指纹。
 * 指纹比较只看叶子证书，格式容忍 `AA:BB:…`、空格与大小写差异。
 */
object TlsSockets {

    fun connectPinned(host: String, port: Int, pin: String, connectTimeoutMs: Int): SSLSocket {
        val expected = normalizeFingerprint(pin)
        require(expected.length == 64) {
            "证书指纹应为 64 位十六进制（SHA-256），当前 ${expected.length} 位"
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(FingerprintTrustManager(expected)), SecureRandom())
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = connectTimeoutMs
            // 立刻握手：让"指纹不对"在连接阶段就失败，而不是等到第一次读写
            socket.startHandshake()
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    /** 去掉冒号/空格并转小写；长度不是 64 时由调用方报错。 */
    fun normalizeFingerprint(value: String): String =
        value.filter { it.isLetterOrDigit() }.lowercase()

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

private class FingerprintTrustManager(private val expected: String) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("服务端没有出示证书")
        val actual = TlsSockets.sha256Hex(leaf.encoded)
        if (!constantTimeEquals(actual, expected)) {
            throw CertificateException(
                "证书指纹不匹配（期望 ${expected.take(16)}…，实际 ${actual.take(16)}…）",
            )
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("中继不应要求客户端证书")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (index in a.indices) diff = diff or (a[index].code xor b[index].code)
        return diff == 0
    }
}
