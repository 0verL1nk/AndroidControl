package dev.ling.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * scrcpy 协议里的多字节整数一律大端（网络字节序）。
 * 这里用官方测试向量里的真实数值做断言，避免"自认为对"。
 */
class ByteReaderTest {

    @Test
    fun `按大端读取 16 位无符号数`() {
        // 1080 = 0x0438，来自 scrcpy 触摸事件的 screen_width
        val r = ByteReader(byteArrayOf(0x04, 0x38))
        assertEquals(1080, r.u16())
        assertEquals(0, r.remaining)
    }

    @Test
    fun `按大端读取 32 位无符号数`() {
        // 0x0000_0064 = 100，来自触摸事件的 x
        val r = ByteReader(byteArrayOf(0x00, 0x00, 0x00, 0x64))
        assertEquals(100L, r.u32())
    }

    @Test
    fun `按大端读取 32 位有符号数`() {
        val r = ByteReader(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        assertEquals(-1, r.i32())
    }

    @Test
    fun `按大端读取 64 位值`() {
        // 0x1234567887654321，来自 scrcpy 触摸事件的 pointer_id
        val r = ByteReader(
            byteArrayOf(
                0x12, 0x34, 0x56, 0x78,
                0x87.toByte(), 0x65, 0x43, 0x21,
            )
        )
        assertEquals(0x1234567887654321L, r.i64())
    }

    @Test
    fun `读取顺序推进且可跳过`() {
        val r = ByteReader(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(1, r.u8())
        r.skip(2)
        assertEquals(4, r.u8())
        assertEquals(1, r.remaining)
    }

    @Test
    fun `读取超出长度时抛出异常`() {
        val r = ByteReader(byteArrayOf(1))
        assertFailsWith<IllegalStateException> { r.u32() }
    }

    @Test
    fun `按长度取子数组`() {
        val r = ByteReader(byteArrayOf(9, 8, 7, 6))
        assertContentEqualsSafe(byteArrayOf(9, 8), r.bytes(2))
        assertContentEqualsSafe(byteArrayOf(7, 6), r.bytes(2))
    }

    private fun assertContentEqualsSafe(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
