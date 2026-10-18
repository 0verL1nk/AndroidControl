package dev.ling.controller.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.InputStream

/**
 * 把长辈端发来的 scrcpy 视频流解到 [Surface] 上。
 *
 * 流的形状（详见 specs/001-remote-android-control/contracts/wire-protocol.md）：
 *   64 字节设备名 → 4 字节编码 id(必须是 h264) → 若干个 12 字节头 + 负载
 *
 * 会话包会带来画面尺寸（旋转后也会重发），尺寸变化时必须重建解码器——
 * 直接改 MediaFormat 不生效。
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

    /** 在独立线程里跑；异常直接抛出，由上层决定是否重连。 */
    fun decodeLoop() {
        val deviceMeta = readFully(64)
        val codecId = String(readFully(4))
        Log.i(TAG, "设备名=${String(deviceMeta).trim { it <= ' ' }} 编码=$codecId")
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
                    Log.i(TAG, "画面尺寸 ${w}x$h，重建解码器")
                    reconfigure(w, h)
                    onSizeChanged?.onSizeChanged(w, h)
                }
                continue
            }

            val isConfig = first and 0x40 != 0
            val ptsAndFlags = readLong(header, 0)
            val pts = ptsAndFlags and 0x1FFFFFFFFFFFFFFFL
            val size = readInt(header, 8)
            val payload = if (size > 0) readFully(size) else ByteArray(0)
            bytesReceived += size

            feed(payload, pts, isConfig)
        }
    }

    fun stop() {
        running = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private fun reconfigure(width: Int, height: Int) {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        if (width <= 0 || height <= 0) return

        val format = MediaFormat.createVideoFormat(MIME, width, height)
        // 不设这个，部分解码器对无边界流会报错
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
        codec = MediaCodec.createDecoderByType(MIME).apply {
            configure(format, surface, null, 0)
            start()
        }
    }

    private fun feed(payload: ByteArray, pts: Long, isConfig: Boolean) {
        val codec = codec ?: return // 还没拿到会话包，尺寸未知；这几帧只能丢
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index >= 0) {
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(payload)
            codec.queueInputBuffer(
                index,
                0,
                payload.size,
                pts,
                if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0,
            )
        }

        // 排空输出：远程操作对延迟敏感，拿到就立刻上屏，不攒帧
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            if (outIndex < 0) break
            codec.releaseOutputBuffer(outIndex, true)
            frameCount++
        }
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
    }
}
