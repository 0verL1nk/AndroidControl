package dev.ling.agent

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.ling.agent.session.AssistPhase
import dev.ling.agent.ui.AgentScreen
import dev.ling.design.AppTheme
import rikka.shizuku.Shizuku

/**
 * 长辈端入口。
 *
 * 界面只负责"让人看懂 + 一个按钮"；真正的会话在 [AgentService]（前台服务）里，
 * 所以 Activity 退到后台或旋转都不会影响协助。
 */
class MainActivity : ComponentActivity() {

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) startAssist()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 部署用：am start ... --es relay_host <节点> [--ei relay_port 27180]
        intent?.getStringExtra("relay_host")?.takeIf { it.isNotBlank() }?.let { host ->
            val port = intent.getIntExtra("relay_port", 27180)
            dev.ling.agent.trust.PairingStore(this).setRelay(host, port)
            android.util.Log.i("AgentMain", "中继已配置：$host:$port")
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        requestNotificationPermission()

        setContent {
            AppTheme {
                val state by AgentService.state.collectAsState()
                AgentScreen(
                    state = state,
                    onStart = { requestAndStart() },
                    onStop = { AgentService.stop(this) },
                    onRetry = { requestAndStart() },
                    onOpenShizuku = { openShizuku() },
                    onRotateToken = { AgentService.rotateToken(this) },
                )
            }
        }

        // 打开即进入"可被协助"：长辈点开这个应用本身就是明确的动作
        window.decorView.postDelayed({ requestAndStart() }, 800)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }

    private fun requestAndStart() {
        if (!Shizuku.pingBinder()) {
            // 用界面能表达的方式提示：进入出错态
            AgentService.reportError("Shizuku 没有运行，需要家人帮忙重新启动一次")
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            startAssist()
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    private fun startAssist() {
        requestOverlayIfNeeded()
        requestAudioIfNeeded()
        AgentService.start(this)
    }

    /** 语音对讲需要麦克风；部署时授权一次即可，不拦路。 */
    private fun requestAudioIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2002)
    }

    /**
     * 荧光提示需要"显示在其他应用上层"权限。没有它协助照样能用，
     * 只是长辈看不到那圈提示——所以这里请求一次，但不拦路。
     */
    private fun requestOverlayIfNeeded() {
        if (android.provider.Settings.canDrawOverlays(this)) return
        runCatching {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                )
            )
        }.onFailure { android.util.Log.w("AgentMain", "无法打开悬浮窗授权页", it) }
    }

    /**
     * 跳到 Shizuku 让它自己启动。
     *
     * 非 root 模式下 Shizuku 在设备重启后不会自己运行，而它没运行我们就拿不到 shell。
     * 这是目前唯一需要长辈动手的场景，所以把操作压到最少：
     * 打开 Shizuku → 点"启动"。
     */
    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent == null) {
            AgentService.reportError("这台手机还没装 Shizuku，需要家人帮忙安装一次")
            return
        }
        runCatching { startActivity(intent) }
            .onFailure { AgentService.reportError("打不开 Shizuku，请让家人看看") }
    }

    private companion object {
        const val REQUEST_CODE = 1001
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
