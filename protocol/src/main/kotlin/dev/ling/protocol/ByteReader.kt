package dev.ling.protocol

/**
 * scrcpy 协议中的多字节整数一律为**大端**（网络字节序）。
 *
 * 不做隐式的边界截断：读越界一律抛 [IllegalStateException]，因为越界意味着
 * 对端发来的数据不符合协议，继续读下去只会产生更难查的错误。
 */
class ByteReader(private val data: ByteArray) {

    var position: Int = 0
        private set

    val remaining: Int get() = data.size - position

    fun u8(): Int {
        requireAvailable(1)
        return data[position++].toInt() and 0xFF
    }

    fun u16(): Int {
        requireAvailable(2)
        val hi = data[position].toInt() and 0xFF
        val lo = data[position + 1].toInt() and 0xFF
        position += 2
        return (hi shl 8) or lo
    }

    /** 32 位无符号数以 Long 返回，避免 Kotlin 的有符号 Int 造成负数。 */
    fun u32(): Long {
        requireAvailable(4)
        var v = 0L
        for (i in 0 until 4) {
            v = (v shl 8) or (data[position + i].toLong() and 0xFF)
        }
        position += 4
        return v
    }

    fun i32(): Int = u32().toInt()

    fun i64(): Long {
        requireAvailable(8)
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (data[position + i].toLong() and 0xFF)
        }
        position += 8
        return v
    }

    fun skip(n: Int) {
        require(n >= 0) { "skip 长度不能为负：$n" }
        requireAvailable(n)
        position += n
    }

    fun bytes(n: Int): ByteArray {
        require(n >= 0) { "读取长度不能为负：$n" }
        requireAvailable(n)
        val out = data.copyOfRange(position, position + n)
        position += n
        return out
    }

    private fun requireAvailable(n: Int) {
        if (remaining < n) {
            throw IllegalStateException("数据不足：需要 $n 字节，剩余 $remaining 字节")
        }
    }
}
