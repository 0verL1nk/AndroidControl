package dev.ling.controller.core

import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 与一台长辈端建立两条 TCP 流：控制（双向）与视频（下行）。
 *
 * 握手很轻：控制通道 `HELLO <口令>`，视频通道 `AUTH <口令>`，成功回 `OK`。
 * 口令错、版本不符等都会在握手阶段被拒，不会进入操控页。
 */
class SessionClient(
    private val device: DeviceInfo,
    private val connectTimeoutMs: Int = 8_000,
) : AutoCloseable {

    private lateinit var control: Socket
    lateinit var video: Socket
        private set

    lateinit var controlOut: OutputStream
        private set
    lateinit var controlIn: InputStream
        private set
    lateinit var videoIn: InputStream
        private set

    /** 连接并完成握手。失败抛异常，调用方负责把原因翻译成人话。 */
    fun connect() {
        val relay = device.relayHost
        if (!relay.isNullOrBlank()) {
            connectViaRelay(relay, device.relayPort)
            return
        }
        connectDirect()
    }

    /**
     * 经中继连接：两台手机都在 NAT 后面时用这条。
     *
     * 控制端只发 `FIND <口令哈希> <角色>`，中继按哈希把它和长辈端登记好的连接对接起来；
     * 之后的握手由长辈端在本地完成，控制端拿到的就是一条已经通了的数据流。
     * 好处是**不需要地址、不需要 VPN**，只需要那 6 位数字。
     */
    private fun connectViaRelay(relayHost: String, relayPort: Int) {
        val hash = sha256Hex(device.token)

        control = Socket().apply {
            connect(InetSocketAddress(relayHost, relayPort), connectTimeoutMs)
            soTimeout = 0
            tcpNoDelay = true
        }
        val controlReply = handshake(control, "FIND $hash control")
        if (controlReply != "OK") {
            close()
            throw SessionException(relayReason(controlReply))
        }
        controlOut = control.getOutputStream()
        controlIn = control.getInputStream()

        video = Socket().apply {
            connect(InetSocketAddress(relayHost, relayPort), connectTimeoutMs)
            soTimeout = 0
            tcpNoDelay = true
        }
        val videoReply = handshake(video, "FIND $hash video")
        if (videoReply != "OK") {
            close()
            throw SessionException(relayReason(videoReply))
        }
        videoIn = video.getInputStream()
    }

    private fun connectDirect() {
        control = open(device.controlPort)
        controlOut = control.getOutputStream()
        val controlLine = handshake(control, "HELLO ${device.token}")
        if (controlLine != "OK") {
            close()
            throw SessionException(handshakeReason(controlLine))
        }
        controlIn = control.getInputStream()

        video = open(device.videoPort)
        val videoLine = handshake(video, "AUTH ${device.token}")
        if (videoLine != "OK") {
            close()
            throw SessionException(handshakeReason(videoLine))
        }
        videoIn = video.getInputStream()
    }

    private fun open(port: Int): Socket =
        Socket().apply {
            connect(InetSocketAddress(device.host, port), connectTimeoutMs)
            soTimeout = 0 // 视频/控制都是长连接，不做读超时
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

    private fun handshakeReason(reply: String): String = when {
        reply == "BUSY" -> "已有其他人在看这台手机"
        reply == "ERR" -> "口令不正确"
        reply.isEmpty() -> "对方没有响应"
        else -> "连接被拒绝（$reply）"
    }

    override fun close() {
        runCatching { control.close() }
        runCatching { video.close() }
    }

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    class SessionException(message: String) : Exception(message)
}
