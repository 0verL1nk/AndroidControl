package dev.ling.controller.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import dev.ling.protocol.ScrcpyVideoStream
import dev.ling.protocol.VideoPacket
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * 把长辈端发来的 scrcpy 视频流解到 [Surface] 上。
 *
 * 职责边界：拆包交给 [ScrcpyVideoStream]，SPS/PPS 解析交给 [SpsPps]，
 * 这里只负责解码器的生命周期与"什么时候能喂帧"。
 *
 * 两条真机踩出来的规则：
 * 1. 尺寸与配置包都到齐才创建解码器（MediaFormat 需要 csd-0/csd-1）；
 * 2. 首个关键帧之前不能喂 P 帧——解码器会吃进去占住输入缓冲却不出图，
 *    等真正的 IDR 到达时反而没缓冲可用，表现为永久黑屏。
 */
class VideoDecoder(
    input: InputStream,
    private val surface: Surface,
) {

    fun interface SizeListener {
        fun onSizeChanged(width: Int, height: Int)
    }

    private val stream = ScrcpyVideoStream(input)

    @Volatile
    var frameWidth: Int = 0
        private set

    @Volatile
    var frameHeight: Int = 0
        private set

    @Volatile
    var frameCount: Long = 0
        private set

    val bytesReceived: Long get() = stream.payloadBytes

    var onSizeChanged: SizeListener? = null

    /** 解码卡住时回调，让上层重新请求关键帧。 */
    var onNeedKeyFrame: (() -> Unit)? = null

    private var codec: MediaCodec? = null
    private var running = true
    private var config: ByteArray? = null
    private var sawKeyFrame = false
    private var fedPackets = 0

    private var droppedBeforeKeyFrame = 0
    private var starvationSince = 0L
    private var lastStarvationLogAt = 0L
    private var lastKeyFrameRequestAt = 0L

    /** 在独立线程里跑；异常直接抛出，由上层决定是否重连。 */
    fun decodeLoop() {
        val device = stream.readDeviceInfo()
        Log.i(TAG, "设备名=${device.name} 编码=${device.codecId}")
        check(device.codecId == CODEC_ID) { "不支持的编码：${device.codecId}" }

        while (running) {
            when (val packet = stream.next()) {
                is VideoPacket.Session -> onSession(packet)
                is VideoPacket.Config -> onConfig(packet)
                is VideoPacket.Frame -> onFrame(packet)
            }
        }
    }

    fun stop() {
        running = false
        releaseCodec()
    }

    // ── 包处理 ──────────────────────────────────────────────────

    private fun onSession(session: VideoPacket.Session) {
        if (session.width == frameWidth && session.height == frameHeight) return
        frameWidth = session.width
        frameHeight = session.height
        Log.i(TAG, "画面尺寸 ${session.width}x${session.height}")
        onSizeChanged?.onSizeChanged(session.width, session.height)
        ensureCodec(force = true)
    }

    private fun onConfig(packet: VideoPacket.Config) {
        config = packet.data
        Log.i(TAG, "收到配置包（${packet.data.size} 字节）")
        ensureCodec()
    }

    private fun onFrame(frame: VideoPacket.Frame) {
        val codec = codec ?: return

        // 先把已经解好的帧释放掉：输出积压时输入缓冲永远回不来
        drainOutputs(codec)

        if (!frame.isKey && !sawKeyFrame) {
            droppedBeforeKeyFrame++
            if (droppedBeforeKeyFrame == 1 || droppedBeforeKeyFrame % 60 == 0) {
                Log.i(TAG, "等待关键帧，已丢弃 $droppedBeforeKeyFrame 帧")
            }
            return
        }

        val index = dequeueInput(codec)
        if (index < 0) {
            onStarvation()
            return
        }
        starvationSince = 0L

        if (frame.isKey && !sawKeyFrame) {
            sawKeyFrame = true
            if (droppedBeforeKeyFrame > 0) {
                Log.i(TAG, "关键帧已到（此前丢弃 $droppedBeforeKeyFrame 帧）")
            }
        }

        fedPackets++
        if (fedPackets <= 3) {
            Log.i(TAG, "送入第 $fedPackets 帧（key=${frame.isKey}，${frame.data.size} 字节）")
        }

        queueInput(codec, index, frame)

        // 远程操作对延迟敏感：拿到就立刻上屏，不攒帧
        drainOutputs(codec)
    }

    private fun queueInput(codec: MediaCodec, index: Int, frame: VideoPacket.Frame) {
        val buffer = codec.getInputBuffer(index)
        if (buffer == null) {
            // 拿不到 ByteBuffer 也必须还回 index，否则这一格缓冲永久泄漏
            runCatching { codec.queueInputBuffer(index, 0, 0, frame.pts, 0) }
            return
        }
        buffer.clear()
        buffer.put(frame.data)
        val flags = if (frame.isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        codec.queueInputBuffer(index, 0, frame.data.size, frame.pts, flags)
    }

    // ── 解码器生命周期 ──────────────────────────────────────────

    /** 尺寸与配置包都齐了才建解码器；尺寸变化或卡死时 [force] 重建。 */
    private fun ensureCodec(force: Boolean = false) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        val csd = config ?: return
        if (codec != null && !force) return

        releaseCodec()

        val ssp = SpsPps.parse(csd)
        if (!ssp.isComplete) {
            Log.w(TAG, "配置包里没有找到 SPS/PPS，退化为按 CODEC_CONFIG 缓冲喂入")
        }

        val format = MediaFormat.createVideoFormat(MIME, frameWidth, frameHeight).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
            if (ssp.sps.isNotEmpty()) setByteBuffer("csd-0", ByteBuffer.wrap(ssp.sps))
            if (ssp.pps.isNotEmpty()) setByteBuffer("csd-1", ByteBuffer.wrap(ssp.pps))
        }

        codec = MediaCodec.createDecoderByType(MIME).apply {
            configure(format, surface, null, 0)
            start()
        }
        // 新解码器必须重新等一个 IDR：旧的关键帧对它没有意义
        sawKeyFrame = false
        droppedBeforeKeyFrame = 0
        starvationSince = 0L
        Log.i(TAG, "解码器已创建 ${frameWidth}x$frameHeight（csd: ${ssp.sps.size}/${ssp.pps.size} 字节）")
    }

    private fun releaseCodec() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    // ── 缓冲与卡死恢复 ──────────────────────────────────────────

    private fun dequeueInput(codec: MediaCodec): Int {
        repeat(INPUT_RETRIES) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) return index
        }
        return -1
    }

    private fun drainOutputs(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = runCatching { codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US) }
                .getOrNull() ?: return
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "解码器输出格式：${codec.outputFormat}")
                continue
            }
            if (outIndex < 0) return
            codec.releaseOutputBuffer(outIndex, true)
            frameCount++
            if (frameCount == 1L) Log.i(TAG, "首帧已上屏（${info.size} 字节）")
        }
    }

    /**
     * 输入缓冲一直拿不到，说明解码器被卡住了（等参考帧、或者输出没人释放）。
     * 先请求关键帧；还不行就重建解码器——重建会丢弃所有积压，代价只是多等一个 IDR。
     */
    private fun onStarvation() {
        val now = System.currentTimeMillis()
        if (starvationSince == 0L) starvationSince = now
        if (now - lastStarvationLogAt >= STARVATION_LOG_INTERVAL_MS) {
            Log.w(TAG, "没有可用的输入缓冲，丢弃一帧")
            lastStarvationLogAt = now
        }

        if (now - lastKeyFrameRequestAt >= KEY_FRAME_REQUEST_INTERVAL_MS) {
            lastKeyFrameRequestAt = now
            onNeedKeyFrame?.invoke()
        }

        if (now - starvationSince >= RECOVER_AFTER_MS) {
            Log.w(TAG, "解码器持续卡住，重建")
            onNeedKeyFrame?.invoke()
            ensureCodec(force = true)
        }
    }

    private companion object {
        const val TAG = "VideoDecoder"
        const val CODEC_ID = "h264"
        const val MIME = "video/avc"
        const val TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 5_000L
        const val INPUT_RETRIES = 20

        const val STARVATION_LOG_INTERVAL_MS = 2_000L
        const val KEY_FRAME_REQUEST_INTERVAL_MS = 1_500L
        const val RECOVER_AFTER_MS = 3_000L
    }
}
