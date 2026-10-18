package dev.ling.agent.engine

import android.content.Context
import java.io.File

/**
 * 把打包在 assets 里的 scrcpy-server 释放到 **shell 可读**的位置。
 *
 * 为什么不能直接用 assets：Shizuku 的 UserService 是以 shell 身份运行的独立进程，
 * 读不到应用私有目录。应用的外部文件目录（/sdcard/Android/data/<pkg>/files）
 * 对 shell 可读，实测可行。
 */
class EngineInstaller(private val context: Context) {

    fun install(): File {
        val dir = File(context.getExternalFilesDir(null), "engine")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建引擎目录：$dir")
        }

        val target = File(dir, ENGINE_FILE_NAME)
        val bytes = context.assets.open(ENGINE_ASSET).use { it.readBytes() }
        if (target.exists() && target.length() == bytes.size.toLong()) {
            return target
        }
        target.writeBytes(bytes)
        // shell 需要读权限；外部文件目录的默认权限通常已足够，这里显式放开
        target.setReadable(true, false)
        return target
    }

    companion object {
        const val ENGINE_ASSET = "scrcpy-server.jar"
        const val ENGINE_FILE_NAME = "scrcpy-server.jar"
    }
}
