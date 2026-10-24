package dev.ling.protocol

import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 对**真实节点**做一次 TLS 指纹固定验证。默认跳过（CI 上没有节点），
 * 部署后在本机手工跑一次：
 *
 * ```bash
 * AC_RELAY_PROBE_HOST=<节点> \
 * AC_RELAY_PROBE_PORT=27443 \
 * AC_RELAY_PROBE_FINGERPRINT=<openssl x509 -noout -fingerprint -sha256 的输出> \
 * ./gradlew :protocol:test --tests '*TlsPinningLiveTest*'
 * ```
 *
 * 只在环境变量齐全时执行；参数由调用方提供，仓库里不出现任何节点信息。
 */
class TlsPinningLiveTest {

    @Test
    fun the_pinned_certificate_is_accepted_and_a_wrong_pin_is_rejected() {
        val host = System.getenv("AC_RELAY_PROBE_HOST")
        val port = System.getenv("AC_RELAY_PROBE_PORT")?.toIntOrNull()
        val fingerprint = System.getenv("AC_RELAY_PROBE_FINGERPRINT")
        assumeTrue(
            !host.isNullOrBlank() && port != null && !fingerprint.isNullOrBlank(),
            "未提供 AC_RELAY_PROBE_* 环境变量，跳过真实节点验证",
        )
        requireNotNull(host)
        requireNotNull(port)
        requireNotNull(fingerprint)

        // 正确指纹：握手应成功
        TlsSockets.connectPinned(host, port, fingerprint, TIMEOUT_MS).use { socket ->
            val suite = (socket as javax.net.ssl.SSLSocket).session.cipherSuite
            assertTrue(suite.isNotBlank(), "握手后应协商出加密套件")
        }

        // 错误指纹：必须在握手阶段就被拒绝
        val wrong = "00".repeat(32)
        val rejected = runCatching {
            TlsSockets.connectPinned(host, port, wrong, TIMEOUT_MS).close()
        }
        assertTrue(rejected.isFailure, "错误指纹不应通过")
        val error = rejected.exceptionOrNull()
        assertTrue(
            error !is SocketTimeoutException,
            "应是证书校验失败而不是超时：$error",
        )
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
