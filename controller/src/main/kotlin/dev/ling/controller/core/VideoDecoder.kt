package dev.ling.controller.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * 把长辈端发来的 scrcpy 视频流解到 [Surface] 上。
 *
 * 流的形状见 specs/001-remote-android-control/contracts/wire-protocol.md：
 *   64 字节设备名 → 4 字节编码 id(必须是 h264) → 若干个 12 字节头 + 负载
 *
 * 两个必须注意的点（都是实测踩出来的）：
 * 1. 宽高是会话包里的 **32 位整数**；
 * 2. 联发科等硬解码器要求 SPS/PPS 通过 `MediaFormat` 的 `csd-0/csd-1` 传入，
 *    只把配置包当普通输入缓冲喂进去会报 "CSD is not calculated" 并永远不出帧。
 *    因此要等"尺寸 + 配置包"都到齐再创建解码器。
 */
class VideoDecoder(
    private val input: InputStream,
    private val surface: Surface,
) {

    fun interface SizeListener {
        fun onSizeChanged(width: Int, height: Int)
    }

    @Volatile
    var frameWidth: Int = 0
        private set

    @Volatile
    var frameHeight: Int = 0
        private set

    @Volatile
    var frameCount: Long = 0
        private set

    @Volatile
    var bytesReceived: Long = 0
        private set

    var onSizeChanged: SizeListener? = null

    private var codec: MediaCodec? = null
    private var running = true
    private var config: ByteArray? = null

    /** 在独立线程里跑；异常直接抛出，由上层决定是否重连。 */
    fun decodeLoop() {
        val deviceMeta = readFully(64)
        val codecId = String(readFully(4))
        Log.i(TAG, "设备名=${String(deviceMeta).trim { it <= '\u0000' }} 编码=$codecId")
        if (codecId != "h264") throw IllegalStateException("不支持的编码：$codecId")

        val header = ByteArray(12)
        while (running) {
            readInto(header)
            val first = header[0].toInt() and 0xFF

            if (first and 0x80 != 0) {
                // 会话包：宽高各是 32 位整数
                val w = readInt(header, 4)
                val h = readInt(header, 8)
                if (w != frameWidth || h != frameHeight) {
                    frameWidth = w
                    frameHeight = h
                    Log.i(TAG, "画面尺寸 ${w}x$h")
                    onSizeChanged?.onSizeChanged(w, h)
                    ensureCodec(force = true)
                }
                continue
            }

            val isConfig = first and 0x40 != 0
            val isKeyFrame = first and 0x20 != 0
            val pts = readLong(header, 0) and 0x1FFFFFFFFFFFFFFFL
            val size = readInt(header, 8)
            val payload = if (size > 0) readFully(size) else ByteArray(0)
            bytesReceived += size

            if (isConfig) {
                config = payload
                Log.i(TAG, "收到配置包（${payload.size} 字节）")
                ensureCodec()
                continue
            }

            ensureCodec()
            feed(payload, pts, isConfig = false, isKeyFrame = isKeyFrame)
        }
    }

    fun stop() {
        running = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    /** 尺寸与配置包都齐了才建解码器；尺寸变化时重建。 */
    private fun ensureCodec(force: Boolean = false) {
        if (frameWidth <= 0 || frameHeight <= 0) return
        val csd = config ?: return
        if (codec != null && !force) return

        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null

        val (sps, pps) = splitSpsPps(csd)
        if (sps.isEmpty() || pps.isEmpty()) {
            Log.w(TAG, "配置包里没有找到 SPS/PPS，退化为按 CODEC_CONFIG 缓冲喂入")
        }

        val format = MediaFormat.createVideoFormat(MIME, frameWidth, frameHeight).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
            if (sps.isNotEmpty()) setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            if (pps.isNotEmpty()) setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        }

        codec = MediaCodec.createDecoderByType(MIME).apply {
            configure(format, surface, null, 0)
            start()
        }
        Log.i(TAG, "解码器已创建 ${frameWidth}x$frameHeight（csd: ${sps.size}/${pps.size} 字节）")
    }

    private var fedPackets = 0

    private fun feed(payload: ByteArray, pts: Long, isConfig: Boolean, isKeyFrame: Boolean) {
        val codec = codec ?: return

        // 关键帧只有开头那一个；拿不到输入缓冲就丢帧会导致永远没有画面，
        // 所以这里重试几次而不是直接放弃。
        var index = -1
        var tries = 0
        while (index < 0 && tries < INPUT_RETRIES) {
            index = codec.dequeueInputBuffer(TIMEOUT_US)
            tries++
        }
        if (index < 0) {
            Log.w(TAG, "没有可用的输入缓冲，丢弃一帧（key=$isKeyFrame size=${payload.size}）")
            return
        }
        fedPackets++
        if (fedPackets <= 3) {
            Log.i(TAG, "送入第 $fedPackets 帧（key=$isKeyFrame，${payload.size} 字节）")
        }
        if (true) {
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(payload)
            var flags = 0
            if (isConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            if (isKeyFrame) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            codec.queueInputBuffer(index, 0, payload.size, pts, flags)
        }

        // 远程操作对延迟敏感：拿到就立刻上屏，不攒帧
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "解码器输出格式：${codec.outputFormat}")
                continue
            }
            if (outIndex < 0) break
            codec.releaseOutputBuffer(outIndex, true)
            frameCount++
            if (frameCount == 1L) Log.i(TAG, "首帧已上屏（${info.size} 字节）")
        }
    }

    /**
     * 从 Annex-B 配置包里切出 SPS（NAL 7）与 PPS（NAL 8）。
     * 返回的每个数组都带起始码，符合 MediaFormat csd 的要求。
     */
    private fun splitSpsPps(data: ByteArray): Pair<ByteArray, ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            val is4 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
            val is3 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1
            when {
                is4 -> { starts += i; i += 4 }
                is3 -> { starts += i; i += 3 }
                else -> i++
            }
        }
        if (starts.isEmpty()) return ByteArray(0) to ByteArray(0)

        var sps = ByteArray(0)
        var pps = ByteArray(0)
        for ((index, start) in starts.withIndex()) {
            val end = starts.getOrNull(index + 1) ?: data.size
            val nal = data.copyOfRange(start, end)
            val headerIndex = if (nal.size > 3 && nal[2].toInt() == 1) 3 else 4
            if (nal.size <= headerIndex) continue
            when (nal[headerIndex].toInt() and 0x1F) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
        return sps to pps
    }

    private fun readInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)

    private fun readLong(buf: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[offset + i].toLong() and 0xFF)
        return v
    }

    private fun readFully(size: Int): ByteArray = ByteArray(size).also { readInto(it) }

    private fun readInto(buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw java.io.EOFException("视频流结束")
            read += n
        }
    }

    private companion object {
        const val TAG = "VideoDecoder"
        const val MIME = "video/avc"
        const val TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 5_000L
        const val INPUT_RETRIES = 20
    }
}
