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
) {

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
                    try {
                        // 读一点就丢：用于感知对端断开
                        val probe = ByteArray(256)
                        while (running.get() && s.inputStream.read(probe) >= 0) Unit
                    } catch (_: Throwable) {
                    } finally {
                        fanout.detach(out)
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

    /** 首行必须是 `<verb> <token>`；成功后回 OK，否则回 ERR 并断开。 */
    private fun authenticate(input: InputStream, output: OutputStream, verb: String): Boolean {
        val line = readLine(input) ?: return false
        val parts = line.trim().split(' ')
        val ok = parts.size == 2 && parts[0] == verb && parts[1] == token
        output.write(if (ok) "OK\n".toByteArray() else "ERR\n".toByteArray())
        output.flush()
        if (!ok) Log.w(TAG, "鉴权失败：$line")
        return ok
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
    }
}
