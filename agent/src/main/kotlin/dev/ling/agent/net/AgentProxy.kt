package dev.ling.agent.net

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把引擎的两条流暴露给远端控制端。
 *
 * 设计要点：
 * - **没有控制端时不能阻塞引擎**：视频流必须被持续读走（[VideoFanout] 负责），
 *   否则引擎写满缓冲会卡住。流头被缓存下来，客户端迟到也能接上。
 * - 控制通道是双向的（引擎会回传剪贴板等设备消息），两条泵各自独立。
 * - 鉴权用一次性口令，避免隧道里其它设备直接接管。
 */
class AgentProxy(
    private val streams: EngineStreams,
    private val token: String,
    /** 设备展示名，用于控制端"自动发现"时列出可连设备。 */
    private val deviceName: String = android.os.Build.MODEL ?: "Android 设备",
) {

    /** 控制端接入/断开回调，用来驱动长辈端"正在被协助"的可见状态。 */
    var onClientChanged: ((Boolean) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val fanout = VideoFanout(streams.videoIn)

    private var videoServer: ServerSocket? = null
    private var controlServer: ServerSocket? = null

    var controlPort: Int = 0
        private set
    var videoPort: Int = 0
        private set

    /**
     * 端口固定，不随机：控制端地址与端口在隧道里是常量，
     * 这样"长辈端一打开，控制端就能连"，不需要任何手工输入。
     */
    fun start(control: Int = CONTROL_PORT, video: Int = VIDEO_PORT): Pair<Int, Int> {
        check(running.compareAndSet(false, true)) { "已在运行" }

        Thread({ fanout.readLoop { running.get() } }, "video-read")
            .apply { isDaemon = true; start() }

        controlServer = ServerSocket(control).also { controlPort = it.localPort }
        videoServer = ServerSocket(video).also { videoPort = it.localPort }

        Thread({ acceptControl(controlServer!!) }, "control-accept")
            .apply { isDaemon = true; start() }
        Thread({ acceptVideo(videoServer!!) }, "video-accept")
            .apply { isDaemon = true; start() }

        Log.i(TAG, "代理已启动 control=$controlPort video=$videoPort")
        return controlPort to videoPort
    }

    fun stop() {
        running.set(false)
        fanout.dropClient()
        runCatching { controlServer?.close() }
        runCatching { videoServer?.close() }
        runCatching { streams.close() }
    }

    private fun acceptVideo(server: ServerSocket) {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (t: Throwable) {
                break
            }
            Thread {
                socket.use { s ->
                    val out = s.getOutputStream()
                    if (!authenticate(s.inputStream, out, "AUTH")) return@use
                    if (!fanout.attach(out)) {
                        Log.w(TAG, "流头尚未就绪，拒绝本次连接")
                        return@use
                    }
                    onClientChanged?.invoke(true)
                    try {
                        // 读一点就丢：用于感知对端断开
                        val probe = ByteArray(256)
                        while (running.get() && s.inputStream.read(probe) >= 0) Unit
                    } catch (_: Throwable) {
                    } finally {
                        fanout.detach(out)
                        onClientChanged?.invoke(false)
                    }
                }
            }.apply { isDaemon = true; name = "video-client"; start() }
        }
    }

    private fun acceptControl(server: ServerSocket) {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (t: Throwable) {
                break
            }
            Thread {
                socket.use { s ->
                    val out = s.getOutputStream()
                    if (!authenticate(s.inputStream, out, "HELLO")) return@use
                    val upstream = Thread({ pump(streams.controlIn, out) }, "ctrl-up")
                        .apply { isDaemon = true; start() }
                    pump(s.inputStream, streams.controlOut)
                    upstream.interrupt()
                }
            }.apply { isDaemon = true; name = "control-client"; start() }
        }
    }

    /**
     * 首个握手行有三种可能：
     * - `PROBE`            → 控制端在扫描局域网，回一句设备描述即可（不需要口令）
     * - `HELLO <token>`    → 建立控制会话
     * - `AUTH <token>`     → 建立视频通道
     *
     * PROBE 只在隧道内可达，且只暴露设备名，用来实现"打开就能连、不用手输地址"。
     */
    private fun authenticate(input: InputStream, output: OutputStream, verb: String): Boolean {
        val line = readLine(input) ?: return false
        if (line.trim() == PROBE_VERB) {
            output.write(discoveryReply().toByteArray(Charsets.UTF_8))
            output.flush()
            return false
        }

        // `FIND <口令哈希>`：控制端只输入一次 6 位口令，靠它自动找到"是哪一台"。
        // 线上传的是哈希而非口令本身；隧道本身就是信任边界。
        if (line.startsWith(FIND_PREFIX)) {
            val claimed = line.removePrefix(FIND_PREFIX).trim()
            if (claimed.equals(tokenHash(), ignoreCase = true)) {
                output.write(discoveryReply().toByteArray(Charsets.UTF_8))
                output.flush()
            }
            return false
        }
        val parts = line.trim().split(' ')
        val ok = parts.size == 2 && parts[0] == verb && parts[1] == token
        output.write(if (ok) "OK\n".toByteArray() else "ERR\n".toByteArray())
        output.flush()
        if (!ok) Log.w(TAG, "鉴权失败：$line")
        return ok
    }

    /**
     * 发现响应：`AGENT k=v;k=v\n`。
     *
     * 带上名字/型号/系统版本是为了让控制端在"有多个长辈"时能分辨是哪一台；
     * 只暴露这些无隐私含义的信息（不含屏幕内容、不含口令）。
     */
    private fun discoveryReply(): String {
        val fields = listOf(
            "agent=1",
            "name=" + deviceName,
            "model=" + (android.os.Build.MODEL ?: ""),
            "brand=" + (android.os.Build.MANUFACTURER ?: ""),
            "android=" + (android.os.Build.VERSION.RELEASE ?: ""),
            "sdk=" + android.os.Build.VERSION.SDK_INT,
            "control=" + controlPort,
            "video=" + videoPort,
        ).joinToString(";")
        return "AGENT " + fields + "\n"
    }

    private fun tokenHash(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
            Log.i(TAG, "泵结束：${t.message}")
        }
    }

    private fun readLine(input: InputStream, max: Int = 128): String? {
        val sb = StringBuilder()
        while (sb.length < max) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    companion object {
        const val TAG = "AgentProxy"

        /** 控制通道端口（隧道内固定）。 */
        const val CONTROL_PORT = 27185

        /** 视频通道端口（隧道内固定）。 */
        const val VIDEO_PORT = 27186

        /** 发现用握手词：只报告信息。 */
        const val PROBE_VERB = "PROBE"

        /** 按口令找设备：`FIND <sha256(口令)>`。 */
        const val FIND_PREFIX = "FIND "
    }
}
