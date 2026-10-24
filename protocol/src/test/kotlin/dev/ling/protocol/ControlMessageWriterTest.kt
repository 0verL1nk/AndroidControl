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

    @Test
    fun `文本输入为类型加长度加 UTF-8 字节`() {
        // "hi" → 01 00000002 68 69
        assertBytes(
            intArrayOf(0x01, 0x00, 0x00, 0x00, 0x02, 0x68, 0x69),
            ControlMessageWriter.injectText("hi"),
        )
    }

    @Test
    fun `文本长度按 UTF-8 字节数计算`() {
        // "你好" 是 6 个 UTF-8 字节
        val chinese = ControlMessageWriter.injectText("你好")
        assertEquals(5 + 6, chinese.size)
        assertBytes(intArrayOf(0x01, 0x00, 0x00, 0x00, 0x06), chinese.copyOfRange(0, 5))
        assertEquals(6, chinese.copyOfRange(5, 11).size)

        // emoji 占 4 字节
        val emoji = ControlMessageWriter.injectText("😀")
        assertEquals(5 + 4, emoji.size)
        assertBytes(intArrayOf(0x01, 0x00, 0x00, 0x00, 0x04), emoji.copyOfRange(0, 5))

        // 空串也要发一个 5 字节的合法消息（清空输入框/删光文字的场景）
        assertBytes(intArrayOf(0x01, 0x00, 0x00, 0x00, 0x00), ControlMessageWriter.injectText(""))
    }
}
