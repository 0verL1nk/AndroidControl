package dev.ling.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ling.controller.core.DeviceInfo
import dev.ling.controller.core.Discovery
import dev.ling.controller.store.DeviceStore
import dev.ling.controller.ui.ConnectScreen
import dev.ling.controller.ui.ControlScreen
import dev.ling.design.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private companion object {
        /** 走中继时地址由中继决定，这两个端口只是占位。 */
        const val devicePortUnused = 0
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                App()
            }
        }
    }

    @Composable
    private fun App() {
        val store = remember { DeviceStore(this) }
        var devices by remember { mutableStateOf(store.all()) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) }
        var active by remember { mutableStateOf<DeviceInfo?>(null) }
        var relay by remember { mutableStateOf(store.relayAddress()) }

        // 开发/验收用：am start ... --es host <ip> --es token <6位>
        // 走"手动输入"同一条路径，避免为了自动化去驱动 UI。
        remember {
            intent?.getStringExtra("relay_host")?.takeIf { it.isNotBlank() }?.let {
                val port = intent.getIntExtra("relay_port", 27180)
                store.setRelayAddress("$it:$port")
                store.setRelayFingerprint(intent.getStringExtra("relay_fingerprint"))
                relay = store.relayAddress()
            }
            val relayHostExtra = intent?.getStringExtra("relay_host")
            val tokenExtra = intent?.getStringExtra("token")
            if (!relayHostExtra.isNullOrBlank() && !tokenExtra.isNullOrBlank()) {
                // 只给中继 + 口令：这就是"打开就能连"的形态
                val port = intent.getIntExtra("relay_port", 27180)
                store.setRelayAddress("$relayHostExtra:$port")
                relay = store.relayAddress()
                val device = DeviceInfo(
                    host = relayHostExtra,
                    controlPort = 0,
                    videoPort = 0,
                    name = "长辈的手机",
                    token = tokenExtra,
                    relayHost = relayHostExtra,
                    relayPort = port,
                    relayFingerprint = store.relayFingerprint(),
                )
                store.upsert(device)
                devices = store.all()
                active = device
                return@remember true
            }
            val host = intent?.getStringExtra("host")
            val token = intent?.getStringExtra("token")
            if (!host.isNullOrBlank() && !token.isNullOrBlank()) {
                val port = intent?.getIntExtra("port", 27185) ?: 27185
                val device = DeviceInfo(host, port, port + 1, host, token = token)
                store.upsert(device)
                devices = store.all()
                active = device
            }
            true
        }
        val scope = rememberCoroutineScope()

        val current = active
        if (current != null) {
            ControlScreen(
                device = current,
                onExit = { reason ->
                    store.touch(current.host)
                    devices = store.all()
                    status = reason
                    active = null
                },
            )
            return
        }

        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            ConnectScreen(
                devices = devices,
                busy = busy,
                status = status,
                onConnectByToken = { token ->
                    val relayValue = relay
                    if (!relayValue.isNullOrBlank()) {
                        // 配了中继：不需要地址、不需要同网段，输数字就能连
                        val host = relayValue.substringBefore(':')
                        val port = relayValue.substringAfter(':', "27180").toIntOrNull() ?: 27180
                        val bound = DeviceInfo(
                            host = host,
                            controlPort = devicePortUnused,
                            videoPort = devicePortUnused,
                            name = "长辈的手机",
                            token = token,
                            relayHost = host,
                            relayPort = port,
                            relayFingerprint = store.relayFingerprint(),
                        )
                        store.upsert(bound)
                        store.touch(bound.host)
                        devices = store.all()
                        status = null
                        active = bound
                    } else {
                        busy = true
                        status = "正在查找这台手机…"
                        scope.launch {
                            val found = withContext(Dispatchers.IO) {
                                runCatching { Discovery.findByToken(token) }.getOrNull()
                            }
                            busy = false
                            if (found == null) {
                                status = "没找到这台手机。请确认：数字对不对、长辈手机上的应用是否开着、" +
                                    "以及两台手机是否在同一个网络（不同网络请填中继节点）。"
                            } else {
                                val bound = found.copy(token = token)
                                store.upsert(bound)
                                store.touch(bound.host)
                                devices = store.all()
                                status = null
                                active = bound
                            }
                        }
                    }
                },
                onConnectDevice = { device ->
                    status = null
                    active = device
                },
                onForget = { device ->
                    store.remove(device.host)
                    devices = store.all()
                },
                relayAddress = relay,
                onRelayAddressChange = { value ->
                    relay = value
                    store.setRelayAddress(value)
                },
                onConnectManual = { host, port, token ->
                    val device = DeviceInfo(
                        host = host,
                        controlPort = port,
                        videoPort = port + 1,
                        name = host,
                        token = token,
                    )
                    store.upsert(device)
                    devices = store.all()
                    status = null
                    active = device
                },
            )
        }
    }
}
