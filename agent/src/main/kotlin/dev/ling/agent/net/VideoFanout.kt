package dev.ling.agent.net

import android.util.Log
import dev.ling.protocol.ByteReader
import java.io.OutputStream

/**
 * 视频流分发器：把引擎的视频流按 scrcpy 协议拆包，并支持"迟到客户端"。
 *
 * 为什么需要它：引擎在应用启动的最初几十毫秒就会发出**设备名(64B) + 编码 id(4B) +
 * 会话包 + 配置包(SPS/PPS)**，那时通常还没有控制端连进来。如果只是盲目转发，
 * 这些头部会被丢掉，后来的客户端拿到的是没有头的裸流，解不出画面。
 *
 * 所以这里做两件事：
 * 1. 把流头（设备名、编码 id、最近一次会话包、最近一次配置包）缓存下来；
 * 2. 每来一个新客户端，先把流头回放给它，再接上实时流。
 *    这样"断线重连"也能自然恢复画面，不需要重启引擎。
 */
class VideoFanout(private val input: java.io.InputStream) {

    @Volatile
    private var deviceMeta: ByteArray? = null

    @Volatile
    private var codecId: ByteArray? = null

    /** 最近一次的会话包（旋转后会更新），12 字节。 */
    @Volatile
    private var lastSessionPacket: ByteArray? = null

    /** 最近一次的配置包（含 SPS/PPS），解码器初始化需要。 */
    @Volatile
    private var lastConfigPacket: ByteArray? = null

    private val writeLock = Any()

    @Volatile
    private var sink: OutputStream? = null

    /** 是否有客户端在接收。 */
    val hasClient: Boolean get() = sink != null

    fun readLoop(running: () -> Boolean) {
        try {
            deviceMeta = readFully(DEVICE_META_SIZE)
            codecId = readFully(CODEC_ID_SIZE)
            Log.i(TAG, "已缓存流头：编码=${String(codecId!!)}")
        } catch (t: Throwable) {
            Log.w(TAG, "读取流头失败：${t.message}")
            return
        }

        val header = ByteArray(PACKET_HEADER_SIZE)
        while (running()) {
            try {
                readInto(header)
            } catch (t: Throwable) {
                Log.i(TAG, "视频流结束：${t.message}")
                return
            }

            val reader = ByteReader(header)
            val first = reader.u8()

            if (first and 0x80 != 0) {
                // 会话包：宽高变化（旋转）时引擎会发
                lastSessionPacket = header.copyOf()
                // 宽高是 32 位整数（Streamer.writeSessionMeta），不是 u16
                val width = ((header[4].toInt() and 0xFF) shl 24) or
                    ((header[5].toInt() and 0xFF) shl 16) or
                    ((header[6].toInt() and 0xFF) shl 8) or
                    (header[7].toInt() and 0xFF)
                val height = ((header[8].toInt() and 0xFF) shl 24) or
                    ((header[9].toInt() and 0xFF) shl 16) or
                    ((header[10].toInt() and 0xFF) shl 8) or
                    (header[11].toInt() and 0xFF)
                Log.i(TAG, "画面尺寸更新 ${width}x$height")
                continue
            }

            val isConfig = first and 0x40 != 0
            val payloadSize = ((header[8].toLong() and 0xFF shl 24) or
                (header[9].toLong() and 0xFF shl 16) or
                (header[10].toLong() and 0xFF shl 8) or
                (header[11].toLong() and 0xFF)).toInt()

            val payload = try {
                readFully(payloadSize)
            } catch (t: Throwable) {
                Log.i(TAG, "视频流中断：${t.message}")
                return
            }

            if (isConfig) lastConfigPacket = header + payload

            val current = sink ?: continue
            synchronized(writeLock) {
                if (sink === current) {
                    try {
                        current.write(header)
                        current.write(payload)
                        current.flush()
                    } catch (t: Throwable) {
                        Log.w(TAG, "客户端写失败，转为丢弃：${t.message}")
                        sink = null
                    }
                }
            }
        }
    }

    /** 接入一个客户端：先把流头回放过去，再把它设为实时接收方。 */
    fun attach(out: OutputStream): Boolean {
        val meta = deviceMeta ?: return false
        val codec = codecId ?: return false
        synchronized(writeLock) {
            try {
                out.write(meta)
                out.write(codec)
                lastSessionPacket?.let { out.write(it) }
                lastConfigPacket?.let { out.write(it) }
                out.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "回放流头失败：${t.message}")
                return false
            }
            sink = out
        }
        Log.i(TAG, "客户端已接入，流头已回放")
        return true
    }

    fun detach(out: OutputStream) {
        synchronized(writeLock) {
            if (sink === out) sink = null
        }
    }

    fun dropClient() {
        synchronized(writeLock) { sink = null }
    }

    private fun readFully(size: Int): ByteArray {
        val buf = ByteArray(size)
        readInto(buf)
        return buf
    }

    private fun readInto(buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw java.io.EOFException("流已结束")
            read += n
        }
    }

    private companion object {
        const val TAG = "VideoFanout"
        const val DEVICE_META_SIZE = 64
        const val CODEC_ID_SIZE = 4
        const val PACKET_HEADER_SIZE = 12
    }
}
