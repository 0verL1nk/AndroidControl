package dev.ling.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 直接用 scrcpy 官方测试向量（`app/tests/test_control_msg_serialize.c`）做断言。
 * 这些字节错了会导致"点了没反应"或"点错位置"，而且很难从前端现象定位，
 * 所以必须逐字节对齐。
 */
class ControlMessageWriterTest {

    private fun assertBytes(expected: IntArray, actual: ByteArray) {
        assertEquals(
            expected.map { it.toByte() },
            actual.toList(),
            "字节序列不一致\n期望: ${expected.joinToString(" ") { "%02x".format(it) }}\n" +
                "实际: ${actual.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }}",
        )
    }

    @Test
    fun `按键事件与官方向量一致`() {
        val bytes = ControlMessageWriter.keycode(
            action = ControlMessageWriter.KEY_ACTION_UP,
            keycode = 66,          // AKEYCODE_ENTER
            repeat = 5,
            metaState = 0x41,      // AMETA_SHIFT_ON | AMETA_SHIFT_LEFT_ON
        )
        assertEquals(14, bytes.size)
        assertBytes(
            intArrayOf(
                0x00,
                0x01,
                0x00, 0x00, 0x00, 0x42,
                0x00, 0x00, 0x00, 0x05,
                0x00, 0x00, 0x00, 0x41,
            ),
            bytes,
        )
    }

    @Test
    fun `触摸事件与官方向量一致`() {
        val bytes = ControlMessageWriter.touch(
            action = ControlMessageWriter.ACTION_DOWN,
            pointerId = 0x1234567887654321L,
            x = 100,
            y = 200,
            screenWidth = 1080,
            screenHeight = 1920,
            pressure = 1.0f,
        )
        assertEquals(32, bytes.size)
        assertBytes(
            intArrayOf(
                0x02,
                0x00,
                0x12, 0x34, 0x56, 0x78, 0x87, 0x65, 0x43, 0x21,
                0x00, 0x00, 0x00, 0x64,
                0x00, 0x00, 0x00, 0xC8,
                0x04, 0x38,
                0x07, 0x80,
                0xFF, 0xFF,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
            ),
            bytes,
        )
    }

    @Test
    fun `滚动事件与官方向量一致`() {
        val bytes = ControlMessageWriter.scroll(
            x = 260,
            y = 1026,
            screenWidth = 1080,
            screenHeight = 1920,
            hScroll = 16f,
            vScroll = -16f,
            buttons = 1,
        )
        assertEquals(21, bytes.size)
        assertBytes(
            intArrayOf(
                0x03,
                0x00, 0x00, 0x01, 0x04,
                0x00, 0x00, 0x04, 0x02,
                0x04, 0x38,
                0x07, 0x80,
                0x7F, 0xFF,
                0x80, 0x00,
                0x00, 0x00, 0x00, 0x01,
            ),
            bytes,
        )
    }

    @Test
    fun `返回或亮屏事件与官方向量一致`() {
        val bytes = ControlMessageWriter.backOrScreenOn(ControlMessageWriter.KEY_ACTION_UP)
        assertEquals(2, bytes.size)
        assertBytes(intArrayOf(0x04, 0x01), bytes)
    }

    @Test
    fun `压力值会被夹取到合法区间`() {
        val high = ControlMessageWriter.touch(0, 0, 0, 0, 100, 100, pressure = 2.0f)
        assertEquals(0xFF, high[22].toInt() and 0xFF)
        assertEquals(0xFF, high[23].toInt() and 0xFF)

        val low = ControlMessageWriter.touch(0, 0, 0, 0, 100, 100, pressure = -1.0f)
        assertEquals(0, low[22].toInt())
        assertEquals(0, low[23].toInt())
    }

    @Test
    fun `通用手指的 pointer id 编码为大端负数`() {
        val bytes = ControlMessageWriter.touch(
            action = 0,
            pointerId = ControlMessageWriter.POINTER_ID_GENERIC_FINGER,
            x = 0, y = 0, screenWidth = 1, screenHeight = 1,
        )
        // -2 的 64 位补码是 0xFFFFFFFFFFFFFFFE
        assertBytes(
            intArrayOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFE),
            bytes.copyOfRange(2, 10),
        )
    }
}
