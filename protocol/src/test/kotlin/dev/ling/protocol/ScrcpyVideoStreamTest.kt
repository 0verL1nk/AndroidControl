package dev.ling.protocol

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 拆包测试。
 *
 * 这些字节布局来自 scrcpy 的 `Streamer`：12 字节头像普通媒体包一样以 64 位
 * "标志位 + PTS" 开头，只是标志位在最高三位；会话包的宽高在偏移 4 与 8。
 * 一旦读错，症状是"尺寸对了但画面解不出"或直接中断，所以逐项锁住。
 */
class ScrcpyVideoStreamTest {

    private fun concat(vararg parts: ByteArray): ByteArray =
        parts.fold(ByteArray(0)) { acc, part -> acc + part }

    private fun deviceHeader(name: String = "Pixel 9", codec: String = "h264"): ByteArray {
        val meta = ByteArray(64)
        name.toByteArray().copyInto(meta)
        return concat(meta, codec.toByteArray())
    }

    private fun mediaPacket(flags: Int, pts: Long, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(12 + payload.size).apply {
            // 标志位占 64 位的最高三位，也就是首字节的 bit7/6/5
            putLong(((flags.toLong() and 0xFF) shl 56) or (pts and 0x1FFFFFFFFFFFFFFFL))
            putInt(payload.size)
            put(payload)
        }.array()

    private fun sessionPacket(width: Int, height: Int): ByteArray =
        ByteBuffer.allocate(12).apply {
            put(0, 0x80.toByte())
            putInt(4, width)
            putInt(8, height)
        }.array()

    @Test
    fun `设备信息是 64 字节名字加编码 id`() {
        val stream = ScrcpyVideoStream(ByteArrayInputStream(deviceHeader()))
        val info = stream.readDeviceInfo()
        assertEquals("Pixel 9", info.name)
        assertEquals("h264", info.codecId)
    }

    @Test
    fun `会话包宽高按 32 位整数解析`() {
        val stream = ScrcpyVideoStream(ByteArrayInputStream(sessionPacket(1080, 2400)))
        assertEquals(VideoPacket.Session(1080, 2400), stream.next())
    }

    @Test
    fun `配置包与关键帧标志位分别解析`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val stream = ScrcpyVideoStream(
            ByteArrayInputStream(concat(mediaPacket(0x40, 0, payload), mediaPacket(0x20, 9000, payload))),
        )

        val config = assertIs<VideoPacket.Config>(stream.next())
        assertContentEquals(payload, config.data)

        val frame = assertIs<VideoPacket.Frame>(stream.next())
        assertTrue(frame.isKey)
        assertEquals(9000L, frame.pts)
        assertContentEquals(payload, frame.data)
    }

    @Test
    fun `PTS 是 61 位 不受标志位影响`() {
        val pts = 0x1_0000_0000L // 超过 32 位，验证读的是完整 64 位而不是低 32 位
        val stream = ScrcpyVideoStream(ByteArrayInputStream(mediaPacket(0, pts, byteArrayOf(9))))
        val frame = assertIs<VideoPacket.Frame>(stream.next())
        assertEquals(pts, frame.pts)
        assertEquals(false, frame.isKey)
    }

    @Test
    fun `普通包不会把 PTS 的最高三位当成数据`() {
        val stream = ScrcpyVideoStream(
            ByteArrayInputStream(mediaPacket(0x20, 0x1FFFFFFFFFFFFFFFL, ByteArray(0))),
        )
        val frame = assertIs<VideoPacket.Frame>(stream.next())
        // 标志位被掩掉，只剩 61 位
        assertEquals(0x1FFFFFFFFFFFFFFFL, frame.pts)
    }

    @Test
    fun `负载字节数累加供码率统计`() {
        val stream = ScrcpyVideoStream(
            ByteArrayInputStream(mediaPacket(0x20, 0, ByteArray(100)) + mediaPacket(0, 1, ByteArray(50))),
        )
        repeat(2) { stream.next() }
        assertEquals(150L, stream.payloadBytes)
    }
}
