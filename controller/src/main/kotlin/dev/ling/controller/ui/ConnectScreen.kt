package dev.ling.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ling.controller.core.DeviceInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 连接页。
 *
 * 主路径只有一件事：**输入 6 位数字**——用六个独立方格呈现，比一个文本框更像成品，
 * 也让"差几位"一眼可见。
 *
 * 中继节点、手动地址这些部署项收进「设置」弹层，不干扰日常使用。
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
    var settingsOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(48.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "远程协助",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                )
                Text(
                    "帮家人操作手机",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { settingsOpen = true }) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.height(36.dp))

        Text("输入长辈手机上的 6 位数字", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))

        CodeInput(token) { token = it }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { onConnectByToken(token) },
            enabled = token.length == 6 && !busy,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text("连接", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }

        status?.let {
            Spacer(Modifier.height(14.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    it,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text(
                "已绑定设备",
                fontSize = 12.sp,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            devices.sortedByDescending { it.lastUsedAt }.forEach { device ->
                DeviceCard(device, busy, onConnectDevice, onForget)
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(48.dp))
    }

    if (settingsOpen) {
        SettingsDialog(
            relayAddress = relayAddress,
            busy = busy,
            onRelayAddressChange = onRelayAddressChange,
            onConnectManual = onConnectManual,
            onDismiss = { settingsOpen = false },
        )
    }
}

/** 六个方格：输入用一个透明的真实输入框承接，视觉上呈现为分格。 */
@Composable
private fun CodeInput(token: String, onChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val focused = remember { mutableStateOf(false) }

    Box {
        BasicTextField(
            value = token,
            onValueChange = { input -> onChange(input.filter { it.isDigit() }.take(6)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
            textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier
                .focusRequester(focusRequester)
                .size(1.dp)
                .alpha(0.01f),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { focusRequester.requestFocus() },
        ) {
            repeat(6) { index ->
                val digit = token.getOrNull(index)
                val isNext = index == token.length
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isNext) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isNext) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.weight(1f).height(62.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            digit?.toString().orEmpty(),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

@Composable
private fun SettingsDialog(
    relayAddress: String?,
    busy: Boolean,
    onRelayAddressChange: (String) -> Unit,
    onConnectManual: (host: String, port: Int, token: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var relay by remember { mutableStateOf(relayAddress.orEmpty()) }
    var host by remember { mutableStateOf("") }
    var manualToken by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = relay,
                    onValueChange = { relay = it; onRelayAddressChange(it) },
                    label = { Text("中继节点", fontSize = 13.sp) },
                    placeholder = { Text("203.0.113.10:27180", fontSize = 13.sp) },
                    supportingText = { Text("跨网络连接时使用；已填好可不管", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("设备地址（局域网）", fontSize = 13.sp) },
                    placeholder = { Text("10.66.66.3", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = manualToken,
                    onValueChange = { manualToken = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("6 位数字", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        val h = host.substringBefore(':')
                        val p = host.substringAfter(':', "27185").toIntOrNull() ?: 27185
                        onConnectManual(h, p, manualToken)
                        onDismiss()
                    },
                    enabled = host.isNotBlank() && manualToken.length == 6 && !busy,
                ) { Text("用地址连接", fontSize = 14.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成", fontSize = 15.sp) } },
    )
}

@Composable
private fun DeviceCard(
    device: DeviceInfo,
    busy: Boolean,
    onConnect: (DeviceInfo) -> Unit,
    onForget: (DeviceInfo) -> Unit,
) {
    Card(
        onClick = { onConnect(device) },
        enabled = !busy,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(21.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(device.subtitle, formatRelative(device.lastUsedAt))
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { "点按连接" },
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = { onForget(device) }) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(19.dp),
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun formatRelative(at: Long): String {
    if (at <= 0L) return ""
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> "刚刚用过"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
    }
}
