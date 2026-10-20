package dev.ling.agent

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * 启动"被协助"会话所需的一切：Shizuku 授权、悬浮窗、麦克风、通知、前台服务。
 *
 * 首页与被协助详情页共用这一份逻辑：两个入口都必须做到"打开即待命"，
 * 不该让长辈理解"先点一个按钮"。
 */
class AssistLauncher(private val activity: Activity) {

    private val shizukuListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) startService()
        }

    fun register() = Shizuku.addRequestPermissionResultListener(shizukuListener)

    fun unregister() = Shizuku.removeRequestPermissionResultListener(shizukuListener)

    /** 页面打开就调用：能启动就直接启动；缺 Shizuku 时进入界面能表达的错误态。 */
    fun ensureRunning() {
        requestNotification()
        if (!Shizuku.pingBinder()) {
            AgentService.reportError("Shizuku 没有运行，需要协助你的人重新启动一次")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            startService()
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    /**
     * 跳到 Shizuku 让它自己启动。
     *
     * 非 root 模式下 Shizuku 在设备重启后不会自己运行，而它没运行我们就拿不到 shell。
     * 这是目前唯一需要长辈动手的场景，所以把操作压到最少：打开 Shizuku → 点"启动"。
     */
    fun openShizuku() {
        val intent = activity.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent == null) {
            AgentService.reportError("这台手机还没装 Shizuku，需要协助你的人安装一次")
            return
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { AgentService.reportError("打不开 Shizuku，请让协助你的人看看") }
    }

    private fun startService() {
        // 不在启动时跳系统权限页：那会把用户从应用里"踢出去"，像是出了故障。
        // 屏幕荧光属于一次性部署项（见 docs/ops/elder-device-setup.md），
        // 没有它协助照常工作，只是看不到提示圈。
        requestAudio()
        AgentService.start(activity)
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
    }

    /** 语音对讲需要麦克风；部署时授权一次即可，不拦路。 */
    private fun requestAudio() {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2002)
    }

    companion object {
        private const val TAG = "AssistLauncher"
        private const val REQUEST_CODE = 1001
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** Shizuku 是否已安装并运行。外部只通过这个函数判断，不需要直接依赖 Shizuku SDK。 */
        fun shizukuReady(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }
}
