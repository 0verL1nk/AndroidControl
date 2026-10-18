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
import dev.ling.controller.ui.ControllerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ControllerTheme {
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
                    busy = true
                    status = "正在查找这台手机…"
                    scope.launch {
                        val found = withContext(Dispatchers.IO) {
                            runCatching { Discovery.findByToken(token) }.getOrNull()
                        }
                        busy = false
                        if (found == null) {
                            status = "没找到对应的手机。请确认：数字输入正确、长辈手机上的应用是打开状态、两台手机都连着家里的 Wi-Fi。"
                        } else {
                            val bound = found.copy(token = token)
                            store.upsert(bound)
                            store.touch(bound.host)
                            devices = store.all()
                            status = null
                            active = bound
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
