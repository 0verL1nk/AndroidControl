package dev.ling.controller.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * 在隧道网段里找设备。
 *
 * 两种用法：
 * - [findByToken]：只输入口令就能定位到"是哪一台"——逐个地址发 `FIND <口令哈希>`，
 *   匹配的那台会应答。口令本身不上网线，且隧道只有我们自己的设备。
 * - [scan]：不带口令，列出所有在线的长辈端（给"设备列表"用）。
 */
object Discovery {

    /** 隧道网段前缀，与节点上的 WireGuard 配置保持一致。 */
    const val TUNNEL_PREFIX = "10.66.66."

    /** 地址范围：.1 是节点，设备从 .2 开始。 */
    private val HOST_RANGE = 2..254

    suspend fun findByToken(
        token: String,
        controlPort: Int = 27185,
        prefix: String = TUNNEL_PREFIX,
        timeoutMs: Int = 400,
    ): DeviceInfo? = withContext(Dispatchers.IO) {
        val hash = sha256Hex(token)
        val matches = probeAll(prefix, controlPort, timeoutMs) { socket, reader, writer ->
            writer.write("FIND $hash\n".toByteArray())
            writer.flush()
            reader.readLine()
        }
        matches.firstOrNull()
    }

    suspend fun scan(
        controlPort: Int = 27185,
        prefix: String = TUNNEL_PREFIX,
        timeoutMs: Int = 400,
    ): List<DeviceInfo> = withContext(Dispatchers.IO) {
        probeAll(prefix, controlPort, timeoutMs) { _, reader, writer ->
            writer.write("PROBE\n".toByteArray())
            writer.flush()
            reader.readLine()
        }
    }

    private suspend fun probeAll(
        prefix: String,
        port: Int,
        timeoutMs: Int,
        handshake: (Socket, java.io.BufferedReader, java.io.OutputStream) -> String?,
    ): List<DeviceInfo> = coroutineScope {
        HOST_RANGE.map { last ->
            async(Dispatchers.IO) {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("$prefix$last", port), timeoutMs)
                        socket.soTimeout = timeoutMs
                        val reader = socket.getInputStream().bufferedReader()
                        val line = handshake(socket, reader, socket.getOutputStream()) ?: return@use null
                        parse(prefix + last, port, line)
                    }
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    /** 解析 `AGENT k=v;k=v` 响应。 */
    private fun parse(host: String, port: Int, line: String): DeviceInfo? {
        if (!line.startsWith("AGENT ")) return null
        val fields = line.removePrefix("AGENT ").trim()
            .split(';')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
            }
            .toMap()
        return DeviceInfo(
            host = host,
            controlPort = fields["control"]?.toIntOrNull() ?: port,
            videoPort = fields["video"]?.toIntOrNull() ?: (port + 1),
            name = fields["name"].orEmpty().ifBlank { fields["model"].orEmpty().ifBlank { host } },
            model = fields["model"].orEmpty(),
            brand = fields["brand"].orEmpty(),
            androidVersion = fields["android"].orEmpty(),
        )
    }

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
