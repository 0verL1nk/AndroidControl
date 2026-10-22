package dev.ling.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.ling.agent.AgentService
import dev.ling.agent.AssistLauncher
import dev.ling.agent.trust.PairingStore
import dev.ling.app.ui.HomeScreen
import dev.ling.controller.store.DeviceStore
import dev.ling.design.AppTheme

/**
 * 唯一的入口：同一个 App 既能被协助，也能协助别人。
 *
 * 被协助不需要按任何按钮——打开首页就已经在待命（前提是这台手机装好并启动了 Shizuku）。
 * 首页展示自己的口令与实时状态；协助别人则是输入对方的口令。
 */
class HomeActivity : ComponentActivity() {

    private lateinit var launcher: AssistLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        launcher = AssistLauncher(this)
        launcher.register()

        // Shizuku 没装/没启动时不去打扰用户：这类设备通常只用来"协助别人"，
        // 状态区会安静地说明"暂时不能被协助"，而不是弹一个错误。
        val shizukuReady = AssistLauncher.shizukuReady()

        setContent {
            AppTheme {
                val myCode = remember { PairingStore(this).token() }
                val state by AgentService.state.collectAsState()
                HomeScreen(
                    myCode = myCode,
                    phase = state.phase,
                    error = state.error,
                    shizukuReady = shizukuReady,
                    onRetry = { launcher.ensureRunning() },
                    onStop = { AgentService.stop(this) },
                    onOpenDetails = {
                        startActivity(Intent(this, dev.ling.agent.MainActivity::class.java))
                    },
                    onOpenShizuku = { launcher.openShizuku() },
                    onHelpOther = { token -> openController(token) },
                )
            }
        }

        // 打开即待命
        if (shizukuReady) {
            window.decorView.postDelayed({ launcher.ensureRunning() }, 800)
        }
    }

    override fun onDestroy() {
        launcher.unregister()
        super.onDestroy()
    }

    /** 协助别人：把口令直接带过去，进入后立即连接。 */
    private fun openController(token: String) {
        val relay = DeviceStore(this).relayAddress()
        val intent = Intent(this, dev.ling.controller.MainActivity::class.java)
            .putExtra("token", token)
        if (!relay.isNullOrBlank()) {
            intent.putExtra("relay_host", relay.substringBefore(':'))
            intent.putExtra(
                "relay_port",
                relay.substringAfter(':', "27180").toIntOrNull() ?: 27180,
            )
        }
        startActivity(intent)
    }
}
