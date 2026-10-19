package dev.ling.agent.engine

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/**
 * 以 **shell（uid 2000）** 身份运行的引擎宿主。由 Shizuku 拉起，不随 App 常驻。
 *
 * 它做三件事，且只做这三件：
 * 1. 在 shell 侧建监听 socket（应用不能建：SELinux 会拦住反向连接）；
 * 2. 以 shell 身份把 scrcpy-server 跑起来；
 * 3. 接受引擎连入的两条流，把 fd 交给应用。
 */
class EngineService : IEngineService.Stub() {

    private val engine = AtomicReference<Process?>(null)
    private val outputTail = StringBuilder()
    private val outputLock = Any()

    private var serverSocket: LocalServerSocket? = null

    /**
     * 必须持有已接受的 LocalSocket 引用：LocalSocket 被回收时会关闭并 shutdown
     * 底层 socket，而我们只是把它的 fd dup 给了应用——一旦 shutdown，
     * 应用那一侧读到的就是半截数据。这是踩过的坑。
     */
    private val acceptedSockets = mutableListOf<LocalSocket>()

    override fun prepareServer(scidHex: String): Boolean {
        closeServer()
        return try {
            serverSocket = LocalServerSocket("scrcpy_$scidHex")
            Log.i(TAG, "监听 scrcpy_$scidHex")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "创建监听 socket 失败", t)
            false
        }
    }

    override fun startEngine(
        jarPath: String,
        scidHex: String,
        maxSize: Int,
        maxFps: Int,
        bitRate: Int,
    ): String {
        stopEngine()

        if (serverSocket == null) return "尚未 prepareServer"
        if (!java.io.File(jarPath).canRead()) return "引擎文件不可读：$jarPath"

        // 应用被强杀时 UserService 进程会成为孤儿，残留的引擎会继续占用采集资源。
        // 每次启动前清一遍，保证只有一个引擎在跑。
        killStaleEngines()

        val args = listOf(
            "/system/bin/app_process",
            "/",
            "com.genymobile.scrcpy.Server",
            SCRCPY_VERSION,
            "scid=$scidHex",
            "log_level=info",
            "audio=false",
            "control=true",
            // 默认值即"引擎反向连接客户端"，这里显式写出以免将来改动误伤
            "tunnel_forward=false",
            "max_size=$maxSize",
            "max_fps=$maxFps",
            "video_bit_rate=$bitRate",
            // 协助期间屏幕保持常亮；连上的瞬间如果屏幕是熄的，也直接点亮——
            // 长辈按了"开启协助"就是希望被看到，黑屏会让控制端以为连不上。
            "stay_awake=true",
            "power_on=true",
        )

        return try {
            val pb = ProcessBuilder(args)
            pb.environment()["CLASSPATH"] = jarPath
            val process = pb.start()
            engine.set(process)
            drainAsync(process)
            Log.i(TAG, "引擎已启动 scid=$scidHex size=$maxSize fps=$maxFps rate=$bitRate")
            ""
        } catch (t: Throwable) {
            Log.e(TAG, "引擎启动失败", t)
            "启动失败：${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun killStaleEngines() {
        runCatching {
            ProcessBuilder("/system/bin/sh", "-c", "pkill -f com.genymobile.scrcpy.Server")
                .start().waitFor()
        }
    }

    override fun acceptVideo(): ParcelFileDescriptor = acceptOne("video")
    override fun acceptControl(): ParcelFileDescriptor = acceptOne("control")

    /**
     * 当前前台应用包名（敏感页面提示用）。
     *
     * 不同 ROM 的 dumpsys 字段不一样，两种最常见的形式各试一次；
     * 都取不到就返回空串——调用方应把它当作"未知"，而不是"安全"。
     */
    override fun foregroundPackage(): String {
        val commands = listOf(
            "dumpsys window windows | grep -m1 mCurrentFocus",
            "dumpsys activity activities | grep -m1 topResumedActivity",
        )
        for (command in commands) {
            val output = runShellCommand(command) ?: continue
            PACKAGE_PATTERN.find(output)?.groupValues?.get(1)?.let { return it }
        }
        return ""
    }

    private fun runShellCommand(command: String): String? = runCatching {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        // 先等进程结束（有硬超时）再读输出：`readText()` 会一直等到 EOF，
        // 一旦 dumpsys 卡住，读操作就跟着卡死，整个轮询线程再无响应。
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader().use { it.readText().take(MAX_OUTPUT) }
    }.getOrNull()

    private fun acceptOne(label: String): ParcelFileDescriptor {
        val server = serverSocket ?: throw IllegalStateException("尚未 prepareServer")
        val socket: LocalSocket = server.accept()
        acceptedSockets += socket
        Log.i(TAG, "已接受 $label 连接（累计 ${acceptedSockets.size}）")
        // dup 一份交给应用；本进程继续持有原 socket，直到会话结束
        return ParcelFileDescriptor.dup(socket.fileDescriptor)
    }

    override fun stopEngine(): Boolean {
        val p = engine.getAndSet(null) ?: return false
        return try {
            p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
            Log.i(TAG, "引擎已停止")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "停止引擎时出错", t)
            false
        }
    }

    override fun isRunning(): Boolean = engine.get()?.isAlive == true

    override fun recentOutput(): String = synchronized(outputLock) { outputTail.toString() }

    override fun identity(): String =
        runCatching {
            val p = ProcessBuilder("/system/bin/sh", "-c", "id").start()
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        }.getOrDefault("(无法读取身份)")

    private fun closeServer() {
        acceptedSockets.forEach { runCatching { it.close() } }
        acceptedSockets.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun drainAsync(process: Process) {
        listOf(process.inputStream, process.errorStream).forEachIndexed { index, stream ->
            Thread {
                stream.bufferedReader().useLines { lines -> lines.forEach { appendOutput(it) } }
            }.apply { isDaemon = true; name = "engine-out-$index" }.start()
        }
    }

    private fun appendOutput(line: String) {
        Log.i(TAG, line)
        synchronized(outputLock) {
            outputTail.append(line).append('\n')
            if (outputTail.length > MAX_OUTPUT) {
                outputTail.delete(0, outputTail.length - MAX_OUTPUT)
            }
        }
    }

    /**
     * 由 Shizuku 反射调用（非重写）。UserService 进程不会随解绑自动退出，
     * 必须显式结束，否则下次绑定会拿到带着旧引擎的进程。
     */
    @Suppress("unused")
    fun onDestroy() {
        stopEngine()
        closeServer()
        Log.i(TAG, "EngineService 退出")
        System.exit(0)
    }

    private companion object {
        const val TAG = "AgentEngine"
        const val SCRCPY_VERSION = "5.0.1"
        const val MAX_OUTPUT = 8 * 1024

        /** 从 `... u0 com.pkg/.Activity` 或 `Window{... com.pkg/com.pkg.Activity}` 里取包名。 */
        val PACKAGE_PATTERN = Regex("""([a-zA-Z][\w.]*)/[\w.$]+""")
    }
}
