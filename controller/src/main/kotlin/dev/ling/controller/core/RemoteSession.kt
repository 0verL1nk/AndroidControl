package dev.ling.controller.core

import android.util.Log
import android.view.Surface
import dev.ling.protocol.ControlMessageWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次远程操作会话的编排：连接 → 解码上屏 → 转发输入 → 统计 → 断线重连。
 *
 * 两条流各自独立：
 * - **视频流**由本类持有，解码循环阻塞在这条线程上，中断即整场会话中断；
 * - **控制流**交给 [ControlLink]，它自己重连，不牵连画面。
 *
 * 重连策略按验收标准（FR-012 / SC-006）：最多 8 次、指数退避（1s→30s），
 * 且把次数报给界面——弱网时不能让画面装作没事。
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
    private var statsThread: Thread? = null

    /** 本轮会话的统计循环开关；release 时置 false，旧统计线程自行退出。 */
    @Volatile
    private var statsRunning = false

    /** 连续失败次数；连上后归零（runSession 在握手成功后重置）。 */
    private var attempt = 0

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

    /** 坐标来自控件，内部换算成帧坐标；DOWN 落在黑边上则忽略。 */
    fun sendTouch(action: Int, viewX: Float, viewY: Float, viewWidth: Float, viewHeight: Float) {
        // 只有 DOWN 要判断是否点在黑边上；MOVE/UP 一律夹到边界再发，
        // 否则手指划出画面后抬起时 UP 会丢失，远端一直处于按下状态。
        val (fx, fy) = if (action == ControlMessageWriter.ACTION_DOWN) {
            TouchMapper.toFrame(viewX, viewY, viewWidth, viewHeight, frameWidth, frameHeight)
        } else {
            TouchMapper.toFrameClamped(viewX, viewY, viewWidth, viewHeight, frameWidth, frameHeight)
        } ?: return
        control?.touch(action, PointerIdGenericFinger, fx, fy, frameWidth, frameHeight)
    }

    fun sendKey(keycode: Int) = control?.key(keycode)

    /** 把一整段文字发到对方正在聚焦的输入框。 */
    fun sendText(text: String) = control?.text(text)

    fun sendScroll(viewX: Float, viewY: Float, viewWidth: Float, viewHeight: Float, vScroll: Float) {
        val (fx, fy) = TouchMapper.toFrame(viewX, viewY, viewWidth, viewHeight, frameWidth, frameHeight)
            ?: return
        control?.scroll(fx, fy, frameWidth, frameHeight, 0f, vScroll)
    }

    // ── 连接与重连 ──────────────────────────────────────────────

    private fun connectLoop() {
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
                try {
                    Thread.sleep(reconnectDelayMs(attempt))
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /**
     * 指数退避：1s、2s、4s、8s、15s，之后固定 30s。
     * 固定 500ms 的连续重试会把同一口令哈希快速打到中继的失败锁定阈值上。
     */
    private fun reconnectDelayMs(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 4_000L
        4 -> 8_000L
        5 -> 15_000L
        else -> 30_000L
    }

    /** 建立一次会话并阻塞到它结束。 */
    private fun runSession() {
        val video = Channels.open(device, Role.Video)
        // 握手成功即视为"连上"：下次再断时重新从 1s 开始退避
        attempt = 0
        videoSocket = video
        Log.i(TAG, "已连接 ${device.name}")

        val link = ControlLink(device)
        control = link
        // 每次控制流（重新）连上：先确保对方屏幕亮着，再补一个关键帧请求
        // （后连入的客户端需要新的 IDR 才能出画）
        link.onConnected = {
            link.wakeScreen()
            link.requestKeyFrame()
        }
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

        statsRunning = true
        statsThread = Thread({ statsLoop(videoDecoder) }, "stats").apply {
            isDaemon = true
            start()
        }

        // 解码循环阻塞在这条线程上；尺寸未知时先按 0 报一次，拿到会话包会立刻再报
        frameWidth = 0
        frameHeight = 0
        videoDecoder.decodeLoop()
    }

    private fun releaseCurrentSession() {
        statsRunning = false
        runCatching { statsThread?.interrupt() }
        statsThread = null
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
        while (running.get() && statsRunning) {
            try {
                Thread.sleep(STATS_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
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
     * 就测**到中继节点的连接建立时间**（走 TLS 时含握手）——那正是操作延迟里最大的那一段。
     * 这是网络 RTT，不是端到端画面延迟，界面文案要和它一致。
     */
    private fun measureRtt(): Long = runCatching {
        val start = System.nanoTime()
        val relayHost = device.relayHost
        if (!relayHost.isNullOrBlank()) {
            val pin = device.relayFingerprint
            val socket = if (pin.isNullOrBlank()) {
                java.net.Socket().apply {
                    connect(
                        java.net.InetSocketAddress(relayHost, device.relayPort),
                        RTT_TIMEOUT_MS,
                    )
                }
            } else {
                dev.ling.protocol.TlsSockets.connectPinned(
                    relayHost,
                    device.relayPort,
                    pin,
                    RTT_TIMEOUT_MS,
                )
            }
            socket.close()
        } else {
            java.net.Socket().use { socket ->
                socket.connect(
                    java.net.InetSocketAddress(device.host, device.controlPort),
                    RTT_TIMEOUT_MS,
                )
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

        /**
         * 重试次数。中继对同一口令哈希连续失败 10 次会锁 10 分钟，
         * 所以这里宁可早点让用户看到失败，也不能靠高频重试硬顶。
         * 指数退避后，8 次的总窗口约 1~1.5 分钟。
         */
        const val MAX_ATTEMPTS = 8
        const val STATS_INTERVAL_MS = 1_000L
        const val RTT_TIMEOUT_MS = 400

        /** scrcpy 的通用手指指针 id（-2）。 */
        const val PointerIdGenericFinger = -2L
    }
}
