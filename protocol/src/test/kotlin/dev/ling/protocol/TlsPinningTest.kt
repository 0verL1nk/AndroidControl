package dev.ling.protocol

import java.io.File
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 用 JDK 自带的 keytool 现场生成自签证书，起一个本地 TLS 服务端，
 * 验证 [TlsSockets.connectPinned] 真的按证书 SHA-256 指纹校验。
 *
 * 不依赖真实节点，CI 上照常执行；环境里没有 keytool 时才跳过。
 * SSLSocket 默认不做主机名校验（连的是 127.0.0.1，证书是 CN=localhost），
 * 恰好证明安全边界来自指纹而不是主机名；测试里不要设置
 * endpointIdentificationAlgorithm。
 */
class TlsPinningTest {

    @Test
    fun the_pinned_fingerprint_is_enforced_against_a_local_server(@TempDir dir: Path) {
        val keytool = keytoolBinary()
        assumeTrue(keytool.exists(), "找不到 keytool（$keytool），跳过本地 TLS 指纹测试")

        val password = UUID.randomUUID().toString().replace("-", "")
        val keystore = dir.resolve("test.p12")
        val pem = dir.resolve("cert.pem")
        keytool(
            keytool,
            "-genkeypair", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=localhost", "-validity", "2", "-storetype", "PKCS12",
            "-storepass", password, "-keypass", password, "-alias", "test",
            "-keystore", keystore.toString(),
        )
        keytool(
            keytool,
            "-exportcert", "-rfc", "-alias", "test",
            "-keystore", keystore.toString(), "-storepass", password,
            "-file", pem.toString(),
        )

        // 导出的 PEM 去头尾、Base64 解码后就是叶子证书的 DER
        val certDer = Base64.getMimeDecoder().decode(
            Files.readString(pem).lineSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString(""),
        )
        val correctPin = TlsSockets.sha256Hex(certDer)

        val store = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(keystore).use { load(it, password.toCharArray()) }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, password.toCharArray())
        val context = SSLContext.getInstance("TLS")
        context.init(kmf.keyManagers, null, SecureRandom())

        val server = try {
            context.serverSocketFactory.createServerSocket(
                0,
                2,
                InetAddress.getLoopbackAddress(),
            ) as SSLServerSocket
        } catch (e: Exception) {
            assumeTrue(false, "无法启动本地 TLS 服务（$e），跳过本地 TLS 指纹测试")
            throw e
        }
        val port = server.localPort

        // 两次连接：正确指纹握手成功，错误指纹在握手阶段被拒
        val serverThread = thread(isDaemon = true, name = "tls-pinning-test-server") {
            repeat(2) {
                try {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = TIMEOUT_MS
                        socket.startHandshake()
                    }
                } catch (_: Exception) {
                    // 错误指纹时客户端会主动断开，服务端握手失败是预期内的
                }
            }
        }

        try {
            TlsSockets.connectPinned("127.0.0.1", port, correctPin, TIMEOUT_MS).close()

            val failure = assertFailsWith<Exception> {
                TlsSockets.connectPinned("127.0.0.1", port, "00".repeat(32), TIMEOUT_MS).close()
            }
            assertTrue(
                failure !is SocketTimeoutException,
                "应是证书校验失败而不是超时：$failure",
            )
            assertTrue(
                failure.message.orEmpty().contains("指纹不匹配"),
                "异常应说明指纹不匹配：${failure.message}（cause=${failure.cause}）",
            )
        } finally {
            server.close()
            serverThread.join(TIMEOUT_MS.toLong())
        }
    }

    /** keytool 在 JAVA_HOME/bin 下；Windows 上带 .exe。 */
    private fun keytoolBinary(): File {
        val bin = File(System.getProperty("java.home"), "bin")
        val plain = File(bin, "keytool")
        return if (plain.exists()) plain else File(bin, "keytool.exe")
    }

    private fun keytool(binary: File, vararg args: String) {
        val process = ProcessBuilder(listOf(binary.absolutePath, *args))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertEquals(0, exit, "keytool ${args.firstOrNull()} 失败（退出码 $exit）：\n$output")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
    }
}
