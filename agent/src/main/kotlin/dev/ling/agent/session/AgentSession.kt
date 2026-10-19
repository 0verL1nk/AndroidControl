package dev.ling.agent.session

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.ling.agent.engine.EngineInstaller
import dev.ling.agent.engine.IEngineService
import dev.ling.agent.launcher.ServerLauncher
import dev.ling.agent.net.AgentProxy
import dev.ling.agent.net.EngineStreams
import dev.ling.agent.net.RelayClient
import dev.ling.agent.trust.PairingStore
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次"允许协助"的完整生命周期：
 *
 *   释放引擎文件 → 让 shell 侧建好监听 socket → 以 shell 身份启动引擎
 *   → 接受两条流（拿 fd）→ 暴露给控制端
 *
 * 每一步都通过 [onState] 汇报：长辈场景里排障只能靠这些信息。
 */
class AgentSession(
    private val context: Context,
    private val launcher: ServerLauncher,
    private val onState: (String) -> Unit,
    private val onReady: (SessionInfo) -> Unit,
    private val onPhase: (AssistPhase, String) -> Unit = { _, _ -> },
) {

    private var proxy: AgentProxy? = null
    private var relay: RelayClient? = null
    private var streams: Closeable? = null
    private var engine: IEngineService? = null
    private val started = AtomicBoolean(false)

    /** scid 为 31 位随机数，8 位十六进制；shell 侧用它拼 socket 名。 */
    private val scidHex: String = String.format("%08x", SecureRandom().nextInt() and 0x7FFFFFFF)

    data class SessionInfo(val controlPort: Int, val videoPort: Int, val token: String, val scid: String)

    fun start(maxSize: Int = 720, maxFps: Int = 30, bitRate: Int = 2_000_000) {
        if (!started.compareAndSet(false, true)) return
        onPhase(AssistPhase.Preparing, "正在准备…")

        state("准备引擎文件…")
        val jar = try {
            EngineInstaller(context).install()
        } catch (t: Throwable) {
            fail("引擎文件释放失败", t)
            return
        }
        state("引擎文件就绪")

        launcher.bind(onReady = { service ->
            engine = service
            state("已获得 shell 身份")
            Thread({ serve(service, jar.absolutePath, maxSize, maxFps, bitRate) }, "agent-session")
                .apply { isDaemon = true }.start()
        }, onError = { msg -> fail(msg, null) })
    }

    private fun serve(
        service: IEngineService,
        jarPath: String,
        maxSize: Int,
        maxFps: Int,
        bitRate: Int,
    ) {
        state("建立会话通道…")
        if (!service.prepareServer(scidHex)) {
            fail("无法建立会话通道", null)
            return
        }

        state("启动采集引擎…")
        val err = service.startEngine(jarPath, scidHex, maxSize, maxFps, bitRate)
        if (err.isNotEmpty()) {
            fail("引擎启动失败：$err", null)
            return
        }

        state("等待引擎连入…")
        val engineStreams = try {
            acceptStreams(service)
        } catch (t: Throwable) {
            fail("引擎未连入", t)
            return
        }
        streams = engineStreams
        state("引擎已连入（video + control）")

        // 口令持久化：控制端一次绑定后即可"打开就连"，不需要每次输入
        val token = PairingStore(context).token()
        val p = AgentProxy(engineStreams, token, deviceName = agentDisplayName())
        proxy = p
        // 控制端接入 / 断开 → 长辈端状态随之切换（"正在被协助"必须真实可见）
        p.onClientChanged = { connected ->
            onPhase(
                if (connected) AssistPhase.Active else AssistPhase.Ready,
                if (connected) "正在被协助" else "已准备好，等待家人连接",
            )
        }
        val (controlPort, videoPort) = p.start()
        state("已就绪")
        onPhase(AssistPhase.Ready, "已准备好，等待家人连接")
        onReady(SessionInfo(controlPort, videoPort, token, scidHex))

        // 配了中继就主动连出去：这样两台手机都不需要任何入站可达性，
        // 长辈手机上也不用再装 VPN
        val store = PairingStore(context)
        val relayHost = store.relayHost()
        if (!relayHost.isNullOrBlank()) {
            state("连接中继节点 $relayHost…")
            relay = RelayClient(
                nodeHost = relayHost,
                nodePort = store.relayPort(),
                token = token,
                localControlPort = controlPort,
                localVideoPort = videoPort,
                onState = { message ->
                    state(message)
                    val connected = message.contains("家人已连接")
                    onPhase(
                        if (connected) AssistPhase.Active else AssistPhase.Ready,
                        if (connected) "正在被协助" else "已准备好，等待家人连接",
                    )
                },
            ).also { it.start() }
        }
    }

    /**
     * 引擎按 video、control 的顺序连入，且两条流的 fd 必须通过 Binder 取回——
     * 应用进程无法直接连接 shell 创建的 socket（SELinux 跨域限制）。
     */
    private fun acceptStreams(service: IEngineService): EngineStreams {
        val videoPfd = service.acceptVideo()
        val controlPfd = service.acceptControl()
        return EngineStreams(
            videoIn = inputOf(videoPfd),
            controlIn = inputOf(controlPfd),
            controlOut = outputOf(controlPfd),
        )
    }

    private fun inputOf(pfd: ParcelFileDescriptor): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(pfd)

    /** 同一个 fd 既要读也要写，这里用 dup 避免关闭其中一个时把另一个也关掉。 */
    private fun outputOf(pfd: ParcelFileDescriptor): OutputStream =
        ParcelFileDescriptor.AutoCloseOutputStream(pfd.dup())

    fun stop() {
        started.set(false)
        relay?.stop()
        relay = null
        proxy?.stop()
        proxy = null
        runCatching { streams?.close() }
        streams = null
        runCatching { engine?.stopEngine() }
        engine = null
        runCatching { launcher.unbind() }
        state("已结束协助")
        onPhase(AssistPhase.Idle, "已结束协助")
    }

    fun engineOutput(): String = runCatching { engine?.recentOutput() ?: "" }.getOrDefault("")

    /** 控制端在发现阶段看到的名字；长辈端部署时可用设置覆盖（M4）。 */
    private fun agentDisplayName(): String =
        android.os.Build.MODEL ?: "Android 设备"

    /** 把底层原因翻成长辈能看懂的一句话（不出现错误码）。 */
    private fun humanize(raw: String): String = when {
        raw.contains("Shizuku", ignoreCase = true) -> "权限丢失了，需要家人重新设置一次"
        raw.contains("引擎", ignoreCase = true) -> "协助暂时用不了，可能是系统更新了"
        raw.contains("网络", ignoreCase = true) -> "网络没连上，请检查家里的 Wi-Fi"
        else -> "出了点问题，请让家人看看"
    }

    private fun state(message: String) {
        Log.i(TAG, message)
        onState(message)
    }

    private fun fail(message: String, t: Throwable?) {
        Log.e(TAG, message, t)
        started.set(false)
        onState("失败：$message")
        onPhase(AssistPhase.Error, humanize(message))
        stop()
    }

    private companion object {
        const val TAG = "AgentSession"
    }
}
