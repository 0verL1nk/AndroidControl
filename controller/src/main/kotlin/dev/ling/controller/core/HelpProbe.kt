package dev.ling.controller.core

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * 查询长辈最近一次"求助"的时间（Unix 毫秒）；没有或已过期返回 null。
 *
 * 这是一次很短的中继连接（`ASK <哈希>` → `HELP <秒>` / `NONE`），
 * 不需要长辈端在线，也不需要建立控制会话——所以打开 App 就能看到红色提示。
 */
object HelpProbe {

    private const val TIMEOUT_MS = 3_000

    fun lastHelpAt(device: DeviceInfo): Long? {
        val relayHost = device.relayHost ?: return null
        if (relayHost.isBlank()) return null
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(relayHost, device.relayPort), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                socket.getOutputStream().apply {
                    write("ASK ${sha256Hex(device.token)}\n".toByteArray())
                    flush()
                }
                val line = readLine(socket)
                if (line.startsWith("HELP ")) {
                    line.removePrefix("HELP ").trim().toLongOrNull()?.times(1_000)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /** 逐字节读一行：中继紧接着不会发别的，但保持与其它握手一致的读法。 */
    private fun readLine(socket: Socket): String {
        val input = socket.getInputStream()
        val builder = StringBuilder()
        while (builder.length < 64) {
            val byte = input.read()
            if (byte < 0) break
            if (byte == '\n'.code) break
            builder.append(byte.toChar())
        }
        return builder.toString().trim()
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
