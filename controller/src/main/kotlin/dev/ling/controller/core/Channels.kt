package dev.ling.controller.core

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/** 两条流：控制（双向输入）与视频（下行画面）。 */
enum class Role(val wire: String) {
    Control("control"),
    Video("video"),
}

/**
 * 建立一条到长辈端的流并完成握手。
 *
 * 直连与中继只有两处不同：连哪个地址、首行发什么动词。把这层收在一起，
 * 控制与视频就可以**各自独立地**建立、断开、重连。
 */
object Channels {

    private const val CONNECT_TIMEOUT_MS = 8_000

    fun open(device: DeviceInfo, role: Role, connectTimeoutMs: Int = CONNECT_TIMEOUT_MS): Socket {
        val relayHost = device.relayHost
        return if (relayHost.isNullOrBlank()) {
            openDirect(device, role, connectTimeoutMs)
        } else {
            openViaRelay(device, role, relayHost, connectTimeoutMs)
        }
    }

    private fun openDirect(device: DeviceInfo, role: Role, timeoutMs: Int): Socket {
        val port = if (role == Role.Control) device.controlPort else device.videoPort
        val socket = connect(device.host, port, timeoutMs)
        val verb = if (role == Role.Control) "HELLO" else "AUTH"
        val reply = handshake(socket, "$verb ${device.token}")
        if (reply != "OK") {
            socket.close()
            throw SessionException(directReason(reply))
        }
        return socket
    }

    /**
     * 经中继连接：两条流都只是 `FIND <口令哈希> <角色>`，
     * 中继通知长辈端开一条数据连接上来，之后就只是一条透明管道。
     */
    private fun openViaRelay(device: DeviceInfo, role: Role, relayHost: String, timeoutMs: Int): Socket {
        val socket = connect(relayHost, device.relayPort, timeoutMs)
        val reply = handshake(socket, "FIND ${sha256Hex(device.token)} ${role.wire}")
        if (reply != "OK") {
            socket.close()
            throw SessionException(relayReason(reply))
        }
        return socket
    }

    private fun connect(host: String, port: Int, timeoutMs: Int): Socket =
        Socket().apply {
            connect(InetSocketAddress(host, port), timeoutMs)
            soTimeout = 0 // 两条流都是长连接，不做读超时
            tcpNoDelay = true // 远程操作对延迟敏感，禁用 Nagle
        }

    /**
     * 握手必须**逐字节**读到换行，不能用 BufferedReader：
     * 它会预读，把紧接着 `OK\n` 之后的视频流头部一并吞进缓冲区，
     * 之后改用原始流读取时那部分字节就丢了——表现为"设备名乱码、解不出帧"。
     */
    private fun handshake(socket: Socket, line: String): String {
        socket.getOutputStream().write("$line\n".toByteArray())
        socket.getOutputStream().flush()

        val input = socket.getInputStream()
        val sb = StringBuilder()
        while (sb.length < 64) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString().trim()
    }

    private fun relayReason(reply: String): String = when (reply) {
        "ERR" -> "这台手机还没连上中继（可能刚重启，稍后再试）"
        "" -> "中继没有响应"
        else -> "中继拒绝了连接（$reply）"
    }

    private fun directReason(reply: String): String = when {
        reply == "BUSY" -> "已有其他人在看这台手机"
        reply == "ERR" -> "口令不正确"
        reply.isEmpty() -> "对方没有响应"
        else -> "连接被拒绝（$reply）"
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** 面向用户的连接失败原因。 */
    class SessionException(message: String) : Exception(message)
}
