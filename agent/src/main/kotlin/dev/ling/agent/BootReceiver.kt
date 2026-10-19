package dev.ling.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启动。
 *
 * 能起来的是我们自己：设备重启后系统会拉起本应用，我们随即恢复"可被协助"状态。
 *
 * **不能**起来的是 Shizuku：非 root 模式下 Shizuku 需要借助调试通道启动，
 * 重启后必须有人（或某段程序）重新触发一次。这一点见 docs/ROADMAP.md 的 M2，
 * 以及 docs/DECISIONS.md 里关于 Shizuku 版本的取舍。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Log.i(TAG, "收到 ${intent.action}，尝试恢复协助服务")
        runCatching { AgentService.start(context) }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
