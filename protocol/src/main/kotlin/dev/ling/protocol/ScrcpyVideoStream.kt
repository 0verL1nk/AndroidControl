package dev.ling.protocol

import java.io.EOFException
import java.io.InputStream

/** 视频流开头的设备信息：设备名 + 编码 id。 */
data class VideoDeviceInfo(val name: String, val codecId: String)

/** scrcpy 视频流上的一个包。 */
sealed interface VideoPacket {

    /** 会话包：画面尺寸（旋转后会重新发）。宽高是 32 位整数。 */
    data class Session(val width: Int, val height: Int) : VideoPacket

    /** 编码配置包（Annex-B，含 SPS/PPS）。 */
    data class Config(val data: ByteArray) : VideoPacket

    /** 一帧画面。 */
    data class Frame(val data: ByteArray, val pts: Long, val isKey: Boolean) : VideoPacket
}

/**
 * 只负责"按 scrcpy 协议把字节拆成包"，不碰 MediaCodec。
 *
 * 协议见 `specs/001-remote-android-control/contracts/wire-protocol.md`：
 *   64 字节设备名 → 4 字节编码 id → 若干个 12 字节头 + 负载
 *
 * 12 字节头里第一个字节的最高三位是标志位：
 *   bit 7 = 会话包，bit 6 = 配置包，bit 5 = 关键帧；剩下是 61 位 PTS（普通包）。
 * 会话包的宽高在偏移 4 与 8，各占 4 字节。
 */
class ScrcpyVideoStream(private val input: InputStream) {

    /** 已收到的负载字节数，供界面估算码率。 */
    @Volatile
    var payloadBytes: Long = 0L
        private set

    fun readDeviceInfo(): VideoDeviceInfo {
        val meta = readFully(DEVICE_META_SIZE)
        val name = String(meta, Charsets.UTF_8).trim { it <= '\u0000' }
        val codecId = String(readFully(CODEC_ID_SIZE), Charsets.US_ASCII)
        return VideoDeviceInfo(name, codecId)
    }

    /** 读下一个包；流结束抛 [EOFException]，由上层决定是否重连。 */
    fun next(): VideoPacket {
        val header = readFully(PACKET_HEADER_SIZE)
        // 标志位就在 PTS 的最高三位里，所以要先取标志、再从偏移 0 读整个 64 位
        val flags = header[0].toInt() and 0xFF
        val reader = ByteReader(header)

        if (flags and FLAG_SESSION != 0) {
            reader.skip(4)
            val width = reader.i32()
            val height = reader.i32()
            return VideoPacket.Session(width, height)
        }

        val pts = reader.i64() and PTS_MASK
        val size = reader.i32()
        val data = if (size > 0) readFully(size) else EMPTY
        payloadBytes += size

        return if (flags and FLAG_CONFIG != 0) {
            VideoPacket.Config(data)
        } else {
            VideoPacket.Frame(data, pts, isKey = flags and FLAG_KEY != 0)
        }
    }

    private fun readFully(size: Int): ByteArray = ByteArray(size).also { buffer ->
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) throw EOFException("视频流结束")
            read += n
        }
    }

    private companion object {
        const val DEVICE_META_SIZE = 64
        const val CODEC_ID_SIZE = 4
        const val PACKET_HEADER_SIZE = 12

        const val FLAG_SESSION = 0x80
        const val FLAG_CONFIG = 0x40
        const val FLAG_KEY = 0x20

        const val PTS_MASK = 0x1FFFFFFFFFFFFFFFL
        val EMPTY = ByteArray(0)
    }
}
