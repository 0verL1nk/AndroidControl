package dev.ling.agent

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 由 Shizuku 启动的 UserService：本进程以 **shell（uid 2000）** 身份运行，
 * 因此它派生的子进程也拥有 shell 身份，可以执行 `app_process` 拉起 scrcpy-server。
 *
 * 这个类的唯一职责是"证明这条路走得通"（tasks.md T009）。真正的引擎启动器在
 * `launcher/ShizukuLauncher` 中实现。
 */
class ShizukuProbeService : IProbe.Stub() {

    override fun identity(): String = run("id")

    override fun run(command: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { output.append(it).append('\n') }
            }
            val exit = process.waitFor()
            "exit=$exit\n$output"
        } catch (t: Throwable) {
            Log.e(TAG, "命令执行失败: $command", t)
            "exception=${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * 由 Shizuku 反射调用（不是重写父类方法）。
     * UserService 进程不会随解绑自动退出，必须显式结束，否则下次绑定会拿到旧进程。
     */
    @Suppress("unused")
    fun onDestroy() {
        Log.i(TAG, "UserService 退出")
        System.exit(0)
    }

    private companion object {
        const val TAG = "ShizukuProbe"

        @Suppress("unused")
        val sdkInt = Build.VERSION.SDK_INT
    }
}
