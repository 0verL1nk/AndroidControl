package dev.ling.agent.net

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * 引擎的两条流，由 shell 侧的 UserService 通过 Binder 把 fd 交给应用后包装而成。
 *
 * - video 只有下行（引擎 → 应用）
 * - control 是双向的（应用 → 引擎的输入事件；引擎 → 应用的设备消息）
 */
class EngineStreams(
    val videoIn: InputStream,
    val controlIn: InputStream,
    val controlOut: OutputStream,
) : Closeable {

    override fun close() {
        runCatching { videoIn.close() }
        runCatching { controlIn.close() }
        runCatching { controlOut.close() }
    }
}
