package dev.ling.controller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ling.controller.core.DeviceInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 连接页。
 *
 * 主路径只有一件事：**输入 6 位数字口令**。地址、端口、设备都不用填——
 * 口令用来在隧道里定位到具体是哪一台长辈手机（见 Discovery.findByToken）。
 *
 * 已绑定过的设备会列在下面，点一下直接连。
 */
@Composable
fun ConnectScreen(
    devices: List<DeviceInfo>,
    busy: Boolean,
    status: String?,
    onConnectByToken: (String) -> Unit,
    onConnectDevice: (DeviceInfo) -> Unit,
    onForget: (DeviceInfo) -> Unit,
    onConnectManual: (host: String, port: Int, token: String) -> Unit = { _, _, _ -> },
    relayAddress: String? = null,
    onRelayAddressChange: (String) -> Unit = {},
) {
    var token by remember { mutableStateOf("") }
    var manualOpen by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf("") }
    var manualToken by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("远程协助", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Text(
            "输入长辈手机上显示的 6 位数字，即可连接",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = token,
            onValueChange = { input -> token = input.filter { it.isDigit() }.take(6) },
            label = { Text("6 位数字") },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                textAlign = TextAlign.Center,
                letterSpacing = 8.sp,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onConnectByToken(token) },
            enabled = token.length == 6 && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
                Spacer(Modifier.height(4.dp))
            }
            Text("连接", style = MaterialTheme.typography.titleLarge)
        }

        // 手动输入：给不走隧道/排查问题的场景留一个出口
        TextButton(onClick = { manualOpen = !manualOpen }) {
            Text(if (manualOpen) "收起手动输入" else "手动输入地址")
        }
        if (manualOpen) {
            OutlinedTextField(
                value = manualHost,
                onValueChange = { manualHost = it.trim() },
                label = { Text("地址（如 10.66.66.3）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = manualToken,
                onValueChange = { manualToken = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("6 位数字口令") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val host = manualHost.substringBefore(':')
                    val port = manualHost.substringAfter(':', "27185").toIntOrNull() ?: 27185
                    onConnectManual(host, port, manualToken)
                },
                enabled = manualHost.isNotBlank() && manualToken.length == 6 && !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("连接这台设备") }
        }

        // 中继节点：填一次，之后两台手机在任何网络下都能连（不需要 VPN，也不需要地址）
        OutlinedTextField(
            value = relayAddress.orEmpty(),
            onValueChange = onRelayAddressChange,
            label = { Text("中继节点（可选）") },
            placeholder = { Text("如 203.0.113.10:27180") },
            supportingText = { Text("填了就能跨网络连接：长辈端在家、你在外面也能连") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // 状态与失败原因：给人话，不给错误码
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }

        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("已绑定", style = MaterialTheme.typography.titleLarge)
            devices.sortedByDescending { it.lastUsedAt }.forEach { device ->
                DeviceCard(device, busy, onConnectDevice, onForget)
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceInfo,
    busy: Boolean,
    onConnect: (DeviceInfo) -> Unit,
    onForget: (DeviceInfo) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (device.subtitle.isNotBlank()) {
                    Text(
                        device.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "最近使用 " + formatRelative(device.lastUsedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onForget(device) }) { Text("移除") }
            Button(onClick = { onConnect(device) }, enabled = !busy) { Text("连接") }
        }
    }
}

private fun formatRelative(at: Long): String {
    if (at <= 0L) return "从未"
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
    }
}
