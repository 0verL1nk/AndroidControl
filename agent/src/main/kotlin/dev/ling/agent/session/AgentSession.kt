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
import dev.ling.agent.trust.SensitiveApps
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
    /** 进入/离开敏感页面（支付、银行）时回调；null 表示已离开。 */
    private val onSensitivePage: ((String?) -> Unit)? = null,
) {

    private var proxy: AgentProxy? = null
    private var relay: RelayClient? = null
    private var streams: Closeable? = null
    private var engine: IEngineService? = null
    private val started = AtomicBoolean(false)

    /** scid 为 31 位随机数，8 位十六进制；shell 侧用它拼 socket 名。 */
    private val scidHex: String = String.format("%08x", SecureRandom().nextInt() and 0x7FFFFFFF)

    data class SessionInfo(val controlPort: Int, val videoPort: Int, val token: String, val scid: String)

    /**
     * 引擎的画面参数。
     *
     * scrcpy 的 `max_size` 约束的是**长边**：1280 在 20:9 的手机上约等于 576×1280，
     * 缩到控制端屏幕上文字仍然清楚；720 会糊（实测反馈"画质不清晰"）。
     * 码率给 4 Mbps 是**上限**，编码器对静态界面实际只会用几百 Kbps，
     * 只有滚动/动画时才需要更多——不设自适应，弱网下宁可掉帧也不糊。
     */
    fun start(maxSize: Int = 1_280, maxFps: Int = 30, bitRate: Int = 4_000_000) {
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
                if (connected) "正在被协助" else "已准备好，等待对方连接",
            )
        }
        val (controlPort, videoPort) = p.start()
        state("已就绪")
        onPhase(AssistPhase.Ready, "已准备好，等待对方连接")
        onReady(SessionInfo(controlPort, videoPort, token, scidHex))

        Thread({ monitorSensitivePages(service, p) }, "sensitive-pages")
            .apply { isDaemon = true; start() }

        // 配了中继就主动连出去：这样两台手机都不需要任何入站可达性，
        // 长辈手机上也不用再装 VPN
        val store = PairingStore(context)
        val relayHost = store.relayHost()
        if (!relayHost.isNullOrBlank()) {
            state("连接中继节点 $relayHost…")
            val client = RelayClient(
                nodeHost = relayHost,
                nodePort = store.relayPort(),
                token = token,
                localControlPort = controlPort,
                localVideoPort = videoPort,
                tlsPin = store.relayFingerprint(),
                onState = { message ->
                    state(message)
                    val connected = message.contains("对方已连接")
                    onPhase(
                        if (connected) AssistPhase.Active else AssistPhase.Ready,
                        if (connected) "正在被协助" else "已准备好，等待对方连接",
                    )
                },
            )
            // 长辈按下"开启协助"就是一次求助：只在本次会话的第一次连上时上报，
            // 后续的重连不代表长辈又按了一次。
            val helpAnnounced = AtomicBoolean(false)
            client.onSignalingReady = {
                if (helpAnnounced.compareAndSet(false, true)) client.sendHelp()
            }
            relay = client
            client.start()
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
        raw.contains("Shizuku", ignoreCase = true) -> "权限丢失了，需要协助你的人重新设置一次"
        raw.contains("引擎", ignoreCase = true) -> "协助暂时用不了，可能是系统更新了"
        raw.contains("网络", ignoreCase = true) -> "网络没连上，请检查家里的 Wi-Fi"
        else -> "出了点问题，请让协助你的人看看"
    }

    /**
     * 敏感页面提示：只在有人正在看时轮询，避免无人协助时也一直跑 shell。
     * 命中支付/银行类应用就通知界面切成红色提示；离开后恢复。
     */
    private fun monitorSensitivePages(service: IEngineService, proxy: AgentProxy) {
        var reported: String? = null
        while (started.get()) {
            try {
                Thread.sleep(SENSITIVE_POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
            try {
                if (!proxy.hasController) {
                    if (reported != null) {
                        reported = null
                        onSensitivePage?.invoke(null)
                    }
                    continue
                }
                val packageName = runCatching { service.foregroundPackage() }.getOrDefault("")
                val sensitive = packageName.takeIf { SensitiveApps.matches(it) }
                Log.i(TAG, "前台应用：${packageName.ifEmpty { "?" }}（敏感=${sensitive != null}）")
                if (sensitive != reported) {
                    reported = sensitive
                    onSensitivePage?.invoke(sensitive)
                }
            } catch (t: Throwable) {
                // 轮询只是辅助功能：任何失败都不该把线程打死，下一轮继续
                Log.w(TAG, "敏感页面轮询失败：${t.message}")
            }
        }
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

        /** 敏感页面轮询间隔：3 秒足够及时，又不会让 dumpsys 成为负担。 */
        const val SENSITIVE_POLL_MS = 3_000L
    }
}
