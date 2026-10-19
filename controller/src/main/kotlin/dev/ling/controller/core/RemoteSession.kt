package dev.ling.controller.core

import android.util.Log
import android.view.Surface
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次远程操作会话的编排：连接 → 解码上屏 → 转发输入 → 统计 → 断线重连。
 *
 * 两条流各自独立：
 * - **视频流**由本类持有，解码循环阻塞在这条线程上，中断即整场会话中断；
 * - **控制流**交给 [ControlLink]，它自己重连，不牵连画面。
 *
 * 重连策略按验收标准（FR-012 / SC-006）：最多 15 次，且把次数报给界面——
 * 弱网时不能让画面装作没事。
 */
class RemoteSession(
    private val device: DeviceInfo,
    private val surface: Surface,
    private val listener: Listener,
) {

    interface Listener {
        fun onConnected(frameWidth: Int, frameHeight: Int)
        fun onSizeChanged(frameWidth: Int, frameHeight: Int)
        fun onStats(fps: Double, kbps: Double, rttMs: Long, idleSeconds: Int)
        fun onReconnecting(attempt: Int, max: Int)
        fun onFailed(reason: String)
        /** 长辈端主动结束（对方点了"结束协助"）。这种不该自动重连。 */
        fun onEndedByPeer()
    }

    private val running = AtomicBoolean(false)
    private var videoSocket: Socket? = null
    private var decoder: VideoDecoder? = null
    private var control: ControlLink? = null

    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ connectLoop() }, "session").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        releaseCurrentSession()
    }

    // ── 输入转发 ────────────────────────────────────────────────

    /** 坐标来自控件，内部换算成帧坐标；落在黑边上则忽略。 */
    fun sendTouch(action: Int, viewX: Float, viewY: Float, viewWidth: Float, viewHeight: Float) {
        val (fx, fy) = TouchMapper.toFrame(viewX, viewY, viewWidth, viewHeight, frameWidth, frameHeight)
            ?: return
        control?.touch(action, PointerIdGenericFinger, fx, fy, frameWidth, frameHeight)
    }

    fun sendKey(keycode: Int) = control?.key(keycode)

    fun sendScroll(viewX: Float, viewY: Float, viewWidth: Float, viewHeight: Float, vScroll: Float) {
        val (fx, fy) = TouchMapper.toFrame(viewX, viewY, viewWidth, viewHeight, frameWidth, frameHeight)
            ?: return
        control?.scroll(fx, fy, frameWidth, frameHeight, 0f, vScroll)
    }

    // ── 连接与重连 ──────────────────────────────────────────────

    private fun connectLoop() {
        var attempt = 0
        while (running.get()) {
            try {
                runSession()
                // 正常返回意味着对端结束了会话
                if (running.get()) {
                    listener.onEndedByPeer()
                    return
                }
            } catch (t: Throwable) {
                // 本轮连接已不可用：先关干净，避免上一轮的连接/解码器残留
                releaseCurrentSession()
                Log.w(TAG, "会话中断：${t.message}")
                if (!running.get()) return
                attempt++
                if (attempt > MAX_ATTEMPTS) {
                    listener.onFailed("连接已中断：${t.message ?: "未知原因"}")
                    return
                }
                listener.onReconnecting(attempt, MAX_ATTEMPTS)
                Thread.sleep(RECONNECT_DELAY_MS)
            }
        }
    }

    /** 建立一次会话并阻塞到它结束。 */
    private fun runSession() {
        val video = Channels.open(device, Role.Video)
        videoSocket = video
        Log.i(TAG, "已连接 ${device.name}")

        val link = ControlLink(device)
        control = link
        // 每次控制流（重新）连上都补一个关键帧请求：后连入的客户端需要新的 IDR 才能出画
        link.onConnected = { link.requestKeyFrame() }
        link.start()

        val videoDecoder = VideoDecoder(video.getInputStream(), surface)
        // 解码卡住时通过控制通道要一个新关键帧（例如丢包后参考帧断链）
        videoDecoder.onNeedKeyFrame = { link.requestKeyFrame() }
        decoder = videoDecoder

        videoDecoder.onSizeChanged = VideoDecoder.SizeListener { w, h ->
            frameWidth = w
            frameHeight = h
            listener.onSizeChanged(w, h)
        }

        Thread({ statsLoop(videoDecoder) }, "stats").apply { isDaemon = true }.start()

        // 解码循环阻塞在这条线程上；尺寸未知时先按 0 报一次，拿到会话包会立刻再报
        frameWidth = 0
        frameHeight = 0
        videoDecoder.decodeLoop()
    }

    private fun releaseCurrentSession() {
        runCatching { control?.stop() }
        runCatching { decoder?.stop() }
        runCatching { videoSocket?.close() }
        control = null
        decoder = null
        videoSocket = null
    }

    private fun statsLoop(decoder: VideoDecoder) {
        var lastFrames = 0L
        var lastBytes = 0L
        var lastAt = System.currentTimeMillis()
        var lastFrameAt = System.currentTimeMillis()
        while (running.get()) {
            Thread.sleep(STATS_INTERVAL_MS)
            val now = System.currentTimeMillis()
            val elapsed = (now - lastAt).coerceAtLeast(1)
            val fps = (decoder.frameCount - lastFrames) * 1000.0 / elapsed
            val kbps = (decoder.bytesReceived - lastBytes) * 8.0 / elapsed
            // 画面静止时引擎本来就不发帧，这是正常的；
            // 但如果长时间一帧都没有，界面要如实说明，而不是让人以为卡死。
            if (decoder.frameCount != lastFrames) {
                lastFrameAt = now
            }
            lastFrames = decoder.frameCount
            lastBytes = decoder.bytesReceived
            lastAt = now
            val idleSeconds = ((now - lastFrameAt) / 1000).toInt()

            if (frameWidth > 0 && frameHeight > 0) {
                listener.onConnected(frameWidth, frameHeight)
            }
            listener.onStats(fps, kbps, measureRtt(), idleSeconds)
        }
    }

    /**
     * 网络往返时延。
     *
     * 直连时发一句 `PROBE` 测完整往返；中继模式下没有设备地址，
     * 就测**到中继节点的 TCP 握手时间**——那正是操作延迟里最大的那一段。
     * 这是网络 RTT，不是端到端画面延迟，界面文案要和它一致。
     */
    private fun measureRtt(): Long = runCatching {
        val start = System.nanoTime()
        java.net.Socket().use { socket ->
            val relayHost = device.relayHost
            if (!relayHost.isNullOrBlank()) {
                socket.connect(java.net.InetSocketAddress(relayHost, device.relayPort), RTT_TIMEOUT_MS)
            } else {
                socket.connect(java.net.InetSocketAddress(device.host, device.controlPort), RTT_TIMEOUT_MS)
                socket.soTimeout = RTT_TIMEOUT_MS
                socket.getOutputStream().write("PROBE\n".toByteArray())
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().readLine()
            }
        }
        (System.nanoTime() - start) / 1_000_000
    }.getOrDefault(-1L)

    private companion object {
        const val TAG = "RemoteSession"
        const val MAX_ATTEMPTS = 15
        const val RECONNECT_DELAY_MS = 500L
        const val STATS_INTERVAL_MS = 1_000L
        const val RTT_TIMEOUT_MS = 400

        /** scrcpy 的通用手指指针 id（-2）。 */
        const val PointerIdGenericFinger = -2L
    }
}
