package dev.ling.protocol

/**
 * scrcpy 控制消息（客户端 → 设备）的序列化。
 *
 * 全部**大端**。字段布局以 scrcpy 5.0.1 的 `app/src/control_msg.h` 与官方测试向量
 * `app/tests/test_control_msg_serialize.c` 为准，本文件对应的一串单测逐条对齐了那些向量——
 * 协议这种东西不能"看起来对"就行。
 */
object ControlMessageWriter {

    // 消息类型（control_msg.h 的枚举顺序）
    const val TYPE_INJECT_KEYCODE = 0
    const val TYPE_INJECT_TEXT = 1
    const val TYPE_INJECT_TOUCH_EVENT = 2
    const val TYPE_INJECT_SCROLL_EVENT = 3
    const val TYPE_BACK_OR_SCREEN_ON = 4
    const val TYPE_RESET_VIDEO = 17

    // android.view.MotionEvent 的 action
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    // android.view.KeyEvent 的 action
    const val KEY_ACTION_DOWN = 0
    const val KEY_ACTION_UP = 1

    const val BUTTON_PRIMARY = 1

    /** 通用手指的 pointer id：scrcpy 定义为 -2。 */
    const val POINTER_ID_GENERIC_FINGER: Long = -2L

    /** 压力满值，对应 1.0f。 */
    private const val PRESSURE_FULL = 0xFFFF

    /**
     * 触摸事件，固定 32 字节。
     *
     * 坐标是**视频帧尺寸**下的像素，不是设备原始分辨率——由调用方换算好再传进来。
     */
    fun touch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Float = 1f,
        actionButton: Int = BUTTON_PRIMARY,
        buttons: Int = BUTTON_PRIMARY,
    ): ByteArray {
        val out = ByteArray(32)
        var i = 0
        out[i++] = TYPE_INJECT_TOUCH_EVENT.toByte()
        out[i++] = action.toByte()
        i = putLong(out, i, pointerId)
        i = putInt(out, i, x)
        i = putInt(out, i, y)
        i = putShort(out, i, screenWidth)
        i = putShort(out, i, screenHeight)
        i = putShort(out, i, (pressure.coerceIn(0f, 1f) * PRESSURE_FULL).toInt())
        i = putInt(out, i, actionButton)
        putInt(out, i, buttons)
        return out
    }

    /** 按键事件，固定 14 字节。 */
    fun keycode(
        action: Int,
        keycode: Int,
        repeat: Int = 0,
        metaState: Int = 0,
    ): ByteArray {
        val out = ByteArray(14)
        var i = 0
        out[i++] = TYPE_INJECT_KEYCODE.toByte()
        out[i++] = action.toByte()
        i = putInt(out, i, keycode)
        i = putInt(out, i, repeat)
        putInt(out, i, metaState)
        return out
    }

    /**
     * 滚动事件，固定 21 字节。
     *
     * hscroll/vscroll 是**浮点**，但线上编码为 [-16,16] 区间的 16 位整数
     * （见官方测试向量：16 → 0x7FFF，-16 → 0x8000）。
     */
    fun scroll(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        hScroll: Float,
        vScroll: Float,
        buttons: Int = BUTTON_PRIMARY,
    ): ByteArray {
        val out = ByteArray(21)
        var i = 0
        out[i++] = TYPE_INJECT_SCROLL_EVENT.toByte()
        i = putInt(out, i, x)
        i = putInt(out, i, y)
        i = putShort(out, i, screenWidth)
        i = putShort(out, i, screenHeight)
        i = putShort(out, i, encodeScroll(hScroll))
        i = putShort(out, i, encodeScroll(vScroll))
        putInt(out, i, buttons)
        return out
    }

    /** `BACK_OR_SCREEN_ON`，固定 2 字节；息屏时用于点亮屏幕。 */
    fun backOrScreenOn(action: Int): ByteArray =
        byteArrayOf(TYPE_BACK_OR_SCREEN_ON.toByte(), action.toByte())

    /**
     * 请求引擎重启视频编码（scrcpy 的 RESET_VIDEO，单字节）。
     *
     * 为什么需要它：解码器必须在**关键帧**之后才能出画面。控制端是后连进来的，
     * 引擎开头发的那一个 IDR 早就过去了；而长辈端画面静止时引擎不会再产生帧。
     * 发这条消息让引擎重新起一轮编码，客户端就能立刻拿到新的 IDR。
     */
    fun resetVideo(): ByteArray = byteArrayOf(TYPE_RESET_VIDEO.toByte())

    /** 把 [-16,16] 的浮点滚动量编码成 16 位整数。 */
    private fun encodeScroll(value: Float): Int {
        val clamped = value.coerceIn(-16f, 16f)
        return (clamped * 0x8000 / 16f).toInt().coerceIn(-0x8000, 0x7FFF) and 0xFFFF
    }

    private fun putShort(out: ByteArray, offset: Int, value: Int): Int {
        out[offset] = (value shr 8).toByte()
        out[offset + 1] = value.toByte()
        return offset + 2
    }

    private fun putInt(out: ByteArray, offset: Int, value: Int): Int {
        out[offset] = (value shr 24).toByte()
        out[offset + 1] = (value shr 16).toByte()
        out[offset + 2] = (value shr 8).toByte()
        out[offset + 3] = value.toByte()
        return offset + 4
    }

    private fun putLong(out: ByteArray, offset: Int, value: Long): Int {
        for (i in 0 until 8) {
            out[offset + i] = (value shr (56 - 8 * i)).toByte()
        }
        return offset + 8
    }
}
