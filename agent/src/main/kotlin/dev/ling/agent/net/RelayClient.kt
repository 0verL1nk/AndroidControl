package dev.ling.agent.net

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 反向隧道客户端：长辈端主动连到中继节点，把两条流"挂"上去。
 *
 * 为什么这样做：
 * - 两台手机通常都在 NAT 后面，谁也连不上谁；主动连出去就不需要任何入站可达性。
 * - 比组 VPN 少一个 App、少一份配置、少一个 VPN 权限——长辈手机上要装的东西越少越好。
 *
 * 配对由中继按"口令哈希"完成：长辈端登记 `AGENT <哈希> <角色>`，控制端拿同样的哈希来配对。
 * 线上不出现口令本身。
 */
class RelayClient(
    private val nodeHost: String,
    private val nodePort: Int,
    private val token: String,
    private val localControlPort: Int,
    private val localVideoPort: Int,
    private val onState: (String) -> Unit = {},
) {

    private val running = AtomicBoolean(false)
    private var controlThread: Thread? = null
    private var videoThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        controlThread = serve("control", localControlPort, "HELLO")
        videoThread = serve("video", localVideoPort, "AUTH")
    }

    fun stop() {
        running.set(false)
        controlThread?.interrupt()
        videoThread?.interrupt()
    }

    /** 维持一条"登记 → 被配对 → 转发"的循环；断开就重连。 */
    private fun serve(role: String, localPort: Int, localVerb: String): Thread =
        Thread({
            while (running.get()) {
                try {
                    runOneSession(role, localPort, localVerb)
                } catch (t: Throwable) {
                    Log.w(TAG, "$role 隧道中断：${t.message}")
                }
                if (!running.get()) break
                Thread.sleep(RETRY_DELAY_MS)
            }
        }, "relay-$role").apply { isDaemon = true; start() }

    private fun runOneSession(role: String, localPort: Int, localVerb: String) {
        Socket().use { node ->
            node.connect(InetSocketAddress(nodeHost, nodePort), CONNECT_TIMEOUT_MS)
            node.tcpNoDelay = true
            // 中继进程被杀时连接可能进入"半开"状态：没有保活就会一直挂着不重连
            node.keepAlive = true
            node.soTimeout = 0

            node.getOutputStream().apply {
                write("AGENT $tokenHash $role\n".toByteArray())
                flush()
            }
            val reply = readLine(node.getInputStream())
            if (reply != "OK") throw IllegalStateException("节点拒绝了登记：$reply")

            onState("隧道已建立（$role），等待家人连接")
            Log.i(TAG, "$role 已登记到节点")

            // 等节点确认"真的有人来了"再动本地流：
            // 否则会在没人连接时就占住本地接收方，状态也会误报"正在被协助"。
            // 加超时是为了让"中继挂了"这种情况能被发现并重连，而不是无声地挂着。
            node.soTimeout = PAIRED_TIMEOUT_MS
            val paired = readLine(node.getInputStream())
            node.soTimeout = 0
            if (paired != "PAIRED") throw IllegalStateException("节点未确认配对：$paired")
            Log.i(TAG, "$role 已被配对，接入本地流")

            // 我们把它接到本机的代理端口，并代替控制端完成握手。
            Socket().use { local ->
                local.connect(InetSocketAddress("127.0.0.1", localPort), CONNECT_TIMEOUT_MS)
                local.tcpNoDelay = true
                local.getOutputStream().apply {
                    write("$localVerb $token\n".toByteArray())
                    flush()
                }
                val localReply = readLine(local.getInputStream())
                if (localReply != "OK") throw IllegalStateException("本地代理拒绝了握手：$localReply")

                onState("家人已连接（$role）")
                pumpBothWays(node, local)
                onState("家人已断开（$role）")
            }
        }
    }

    private fun pumpBothWays(a: Socket, b: Socket) {
        val up = Thread({ pump(a.getInputStream(), b.getOutputStream()) }, "relay-up")
            .apply { isDaemon = true; start() }
        pump(b.getInputStream(), a.getOutputStream())
        // 任一方向结束就关掉两端：否则另一个方向会一直阻塞在读上，
        // 会话结束后既不会退出也不会重新登记（实测踩到过：control 永远挂住）。
        runCatching { a.close() }
        runCatching { b.close() }
        up.interrupt()
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        try {
            while (running.get()) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (t: Throwable) {
            Log.i(TAG, "隧道转发结束：${t.message}")
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 32) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return sb.toString().trim()
            sb.append(b.toChar())
        }
        return sb.toString().trim()
    }

    val tokenHash: String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "RelayClient"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val RETRY_DELAY_MS = 3_000L

        /** 等待"被配对"的最长时间；超时说明中继可能已经失联。 */
        const val PAIRED_TIMEOUT_MS = 90_000
    }
}
