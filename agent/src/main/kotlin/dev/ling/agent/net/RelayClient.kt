package dev.ling.agent.net

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 反向隧道客户端：长辈端主动连到中继节点，随时待命。
 *
 * **v2：常连接 + 按需开流。**
 * v1 是"登记一次就被配对消耗掉"，控制端可能要等长辈端重新登记（最长几十秒）。
 * 现在改成：
 *  - 一条**常驻信令连接**（每 10 秒心跳），随时知道对方还在不在；
 *  - 控制端一来，中继在信令上发一句 `OPEN <角色>`，我们**立刻**开一条数据连接上去。
 * 于是配对是毫秒级的，掉线也能在一个心跳周期内被发现并重建。
 *
 * 为什么不用 VPN：两台手机都在 NAT 后面，主动连出去就不需要入站可达性；
 * 而且长辈手机上要装的东西越少越好。
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
    private var signalingThread: Thread? = null

    /** 信令通道的输出流：PING 与求助共用，写操作必须串行（否则两行会粘在一起）。 */
    @Volatile
    private var signalingOut: java.io.OutputStream? = null

    /** 信令刚建立时回调（每次重连都会调）；由上层决定是否需要做什么。 */
    var onSignalingReady: (() -> Unit)? = null

    /** 告诉中继"长辈按了求助"，供控制端下次打开时查询。 */
    fun sendHelp() {
        val out = signalingOut ?: return
        synchronized(out) {
            runCatching {
                out.write("HELP\n".toByteArray())
                out.flush()
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        signalingThread = Thread({ signalingLoop() }, "relay-signaling")
            .apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        signalingThread?.interrupt()
        signalingThread = null
    }

    /** 信令连接：断了就重连，全程维护"我还在线"这件事。 */
    private fun signalingLoop() {
        while (running.get()) {
            try {
                maintainSignaling()
            } catch (t: Throwable) {
                Log.w(TAG, "信令中断：${t.message}")
            }
            if (!running.get()) break
            Thread.sleep(RETRY_DELAY_MS)
        }
    }

    private fun maintainSignaling() {
        Socket().use { node ->
            node.connect(InetSocketAddress(nodeHost, nodePort), CONNECT_TIMEOUT_MS)
            node.tcpNoDelay = true
            node.keepAlive = true
            node.soTimeout = HANDSHAKE_TIMEOUT_MS

            val out = node.getOutputStream()
            out.write("AGENT $tokenHash signaling\n".toByteArray())
            out.flush()
            if (readLine(node.getInputStream()) != "OK") {
                throw IllegalStateException("节点拒绝了信令连接")
            }
            signalingOut = out

            onState("隧道已建立，等待对方连接")
            Log.i(TAG, "信令已连接")
            onSignalingReady?.invoke()

            // 心跳：既是保活，也是"对方还在不在"的探针
            val pinger = Thread({
                while (running.get()) {
                    try {
                        Thread.sleep(PING_INTERVAL_MS)
                        synchronized(out) {
                            out.write("PING\n".toByteArray())
                            out.flush()
                        }
                    } catch (t: Throwable) {
                        return@Thread
                    }
                }
            }, "relay-ping").apply { isDaemon = true; start() }

            try {
                // 读超时设为心跳周期的若干倍：长时间收不到 PONG 就认为连接已死
                node.soTimeout = SIGNALING_TIMEOUT_MS
                while (running.get()) {
                    val line = readLine(node.getInputStream())
                    if (line == null) break
                    when {
                        line == "PONG" -> Unit
                        line.startsWith("OPEN ") -> {
                            val role = line.removePrefix("OPEN ").trim()
                            if (role in ROLES) {
                                // 控制端在等着，必须开得快——放到独立线程，别堵住信令
                                Thread({ openDataConnection(role) }, "relay-data-$role")
                                    .apply { isDaemon = true; start() }
                            }
                        }
                        else -> Log.w(TAG, "信令上收到未知消息：${line.take(40)}")
                    }
                }
            } finally {
                pinger.interrupt()
                signalingOut = null
            }
        }
        onState("隧道断开，正在重连…")
    }

    /** 按需开一条数据连接，并与本机代理对接。 */
    private fun openDataConnection(role: String) {
        // 语音与控制在同一端口上、用不同动词区分；视频单独一个端口
        val (localPort, localVerb) = when (role) {
            "control" -> localControlPort to "HELLO"
            "audio" -> localControlPort to "AUDIO"
            else -> localVideoPort to "AUTH"
        }
        try {
            Socket().use { relay ->
                relay.connect(InetSocketAddress(nodeHost, nodePort), DATA_CONNECT_TIMEOUT_MS)
                relay.tcpNoDelay = true
                relay.soTimeout = DATA_HANDSHAKE_TIMEOUT_MS
                relay.getOutputStream().apply {
                    write("DATA $tokenHash $role\n".toByteArray())
                    flush()
                }
                if (readLine(relay.getInputStream()) != "OK") {
                    throw IllegalStateException("节点没有接受数据连接")
                }

                Socket().use { local ->
                    local.connect(InetSocketAddress("127.0.0.1", localPort), DATA_CONNECT_TIMEOUT_MS)
                    local.tcpNoDelay = true
                    local.soTimeout = DATA_HANDSHAKE_TIMEOUT_MS
                    local.getOutputStream().apply {
                        write("$localVerb $token\n".toByteArray())
                        flush()
                    }
                    if (readLine(local.getInputStream()) != "OK") {
                        throw IllegalStateException("本地代理拒绝了握手（可能已有其他人在看）")
                    }

                    onState("对方已连接（$role）")
                    Log.i(TAG, "$role 数据流已建立")
                    relay.soTimeout = 0
                    local.soTimeout = 0
                    pumpBothWays(relay, local, role)
                    onState("对方已断开（$role）")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$role 数据流失败：${t.message}")
        }
    }

    private fun pumpBothWays(a: Socket, b: Socket, role: String) {
        val up = Thread({ pump("$role 上行", a, b) }, "relay-up")
            .apply { isDaemon = true; start() }
        pump("$role 下行", b, a)
        // 任一方向结束就关掉两端：否则另一个方向会一直阻塞在读上，
        // 会话结束后既不会退出也不会重新登记（实测踩到过）。
        runCatching { a.close() }
        runCatching { b.close() }
        up.interrupt()
    }

    /**
     * 在 try 里获取流：对端可能已经关闭，`getInputStream()` 会抛异常。
     * 这个线程上的未捕获异常会直接杀掉整个长辈端进程（Android 的默认处理器），
     * 所以从取流到转发都必须包住。
     */
    private fun pump(label: String, from: Socket, to: Socket) {
        val buffer = ByteArray(64 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (running.get()) {
                val n = input.read(buffer)
                if (n < 0) {
                    Log.i(TAG, "$label 对端正常关闭")
                    break
                }
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (t: Throwable) {
            Log.i(TAG, "$label 异常结束：${t.message}")
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
        val ROLES = setOf("control", "video", "audio")

        const val CONNECT_TIMEOUT_MS = 5_000
        const val HANDSHAKE_TIMEOUT_MS = 8_000

        /** 数据连接是"有人在等"的，必须快：超时就意味着这次连接失败。 */
        const val DATA_CONNECT_TIMEOUT_MS = 10_000
        const val DATA_HANDSHAKE_TIMEOUT_MS = 6_000

        const val PING_INTERVAL_MS = 10_000L
        const val SIGNALING_TIMEOUT_MS = 35_000

        /** 重连间隔：短一点，让控制端几乎感觉不到。 */
        const val RETRY_DELAY_MS = 1_000L
    }
}
