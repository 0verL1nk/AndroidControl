package dev.ling.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.ling.agent.ui.AgentScreen
import dev.ling.design.AppTheme

/**
 * 被协助详情页。
 *
 * 打开即进入"可被协助"状态；界面只展示状态与高级信息，
 * 真正的会话在 [AgentService]（前台服务）里，退出页面也不影响。
 */
class MainActivity : ComponentActivity() {

    private lateinit var launcher: AssistLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 部署用：am start ... --es relay_host <节点> [--ei relay_port 27180]
        intent?.getStringExtra("relay_host")?.takeIf { it.isNotBlank() }?.let { host ->
            val port = intent.getIntExtra("relay_port", 27180)
            dev.ling.agent.trust.PairingStore(this).setRelay(host, port)
            android.util.Log.i("AgentMain", "中继已配置：$host:$port")
        }

        launcher = AssistLauncher(this)
        launcher.register()

        setContent {
            AppTheme {
                val state by AgentService.state.collectAsState()
                AgentScreen(
                    state = state,
                    onStart = { launcher.ensureRunning() },
                    onStop = { AgentService.stop(this) },
                    onRetry = { launcher.ensureRunning() },
                    onOpenShizuku = { launcher.openShizuku() },
                    onRotateToken = { AgentService.rotateToken(this) },
                    videoProfile = dev.ling.agent.trust.PairingStore(this).videoProfile(),
                    onVideoProfile = { profile -> AgentService.setVideoProfile(this, profile) },
                )
            }
        }

        // 打开即进入"可被协助"：点开这个应用本身就是明确的动作
        window.decorView.postDelayed({ launcher.ensureRunning() }, 800)
    }

    override fun onDestroy() {
        launcher.unregister()
        super.onDestroy()
    }
}
