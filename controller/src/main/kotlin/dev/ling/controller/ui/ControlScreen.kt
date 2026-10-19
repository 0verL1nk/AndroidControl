package dev.ling.controller.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ling.controller.core.ControlChannel
import dev.ling.controller.core.DeviceInfo
import dev.ling.controller.core.RemoteSession

/** 性能与连接状态，界面只读。 */
data class SessionStats(
    val fps: Double = 0.0,
    val kbps: Double = 0.0,
    val rttMs: Long = -1,
    val reconnecting: Int? = null,
    val failure: String? = null,
)

/**
 * 操控页：全屏画面 + 悬浮操作条。
 *
 * 安全边界常驻顶部（"正在控制 XX 的手机"），弱网时立刻盖遮罩并给出**可数进度**——
 * 不允许画面卡住却装作正常。
 */
@Composable
fun ControlScreen(
    device: DeviceInfo,
    onExit: (String?) -> Unit,
) {
    var session by remember { mutableStateOf<RemoteSession?>(null) }
    var stats by remember { mutableStateOf(SessionStats()) }
    var showHud by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    val listener = remember {
        object : RemoteSession.Listener {
            override fun onConnected(frameWidth: Int, frameHeight: Int) {
                stats = stats.copy(reconnecting = null, failure = null)
            }

            override fun onSizeChanged(frameWidth: Int, frameHeight: Int) = Unit

            override fun onStats(fps: Double, kbps: Double, rttMs: Long) {
                stats = stats.copy(fps = fps, kbps = kbps, rttMs = rttMs)
            }

            override fun onReconnecting(attempt: Int, max: Int) {
                stats = stats.copy(reconnecting = attempt)
            }

            override fun onFailed(reason: String) {
                stats = stats.copy(reconnecting = null, failure = reason)
            }

            override fun onEndedByPeer() {
                stats = stats.copy(reconnecting = null, failure = "对方已结束协助")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(locked, session) {
                    if (locked) return@pointerInput
                    awaitEachGesture {
                        val target = session ?: return@awaitEachGesture
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        target.sendTouch(0, down.position.x, down.position.y, w, h)

                        // 按下后持续转发移动，直到抬起；长按交给远端系统判定
                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (change.changedToUp()) {
                                target.sendTouch(1, change.position.x, change.position.y, w, h)
                                break
                            }
                            if (change.positionChanged()) {
                                target.sendTouch(2, change.position.x, change.position.y, w, h)
                            }
                        }
                    }
                },
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val created = RemoteSession(device, holder.surface, listener)
                            session = created
                            created.start()
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            session?.stop()
                            session = null
                        }
                    })
                }
            },
        )

        // 顶部安全条：常驻，明确"正在控制谁"
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .height(40.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Text(
                    "正在控制 " + device.name + " 的手机",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    fpsLabel(stats.fps) + " · " + rttShort(stats.rttMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showHud = !showHud }) {
                    Text(if (showHud) "隐藏浮层" else "显示浮层")
                }
            }
        }

        if (showHud) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 12.dp),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    HudLine("帧率 " + fpsLabel(stats.fps))
                    HudLine("码率 " + mbpsLabel(stats.kbps))
                    HudLine("网络 " + rttShort(stats.rttMs))
                    HudLine(qualityLabel(stats.rttMs))
                }
            }
        }

        // 底部悬浮操作条
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) {
                BarButton("返回", Modifier.weight(1f)) { session?.sendKey(ControlChannel.Keys.BACK) }
                BarButton("主页", Modifier.weight(1f)) { session?.sendKey(ControlChannel.Keys.HOME) }
                BarButton("最近", Modifier.weight(1f)) { session?.sendKey(ControlChannel.Keys.APP_SWITCH) }
                BarButton("音量+", Modifier.weight(1f)) { session?.sendKey(ControlChannel.Keys.VOLUME_UP) }
                BarButton("音量-", Modifier.weight(1f)) { session?.sendKey(ControlChannel.Keys.VOLUME_DOWN) }
                BarButton(if (locked) "已锁定" else "锁定", Modifier.weight(1f)) { locked = !locked }
                BarButton("断开", Modifier.weight(1f), danger = true) { confirmDisconnect = true }
            }
        }

        // 重连遮罩：给出可数进度，不装死
        stats.reconnecting?.let { attempt ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        "正在重连…（" + attempt + "/15）",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        "网络不稳定，请稍候，无需操作",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }

        stats.failure?.let { reason ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        reason,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                    )
                    TextButton(onClick = { onExit(reason) }) { Text("返回") }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { session?.stop() }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("要断开连接吗？") },
            text = { Text("断开后将无法继续操作对方手机") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    session?.stop()
                    onExit(null)
                }) { Text("断开", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HudLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White)
}

@Composable
private fun BarButton(
    label: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = modifier.height(56.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fpsLabel(fps: Double): String = String.format("%.1f fps", fps)

private fun mbpsLabel(kbps: Double): String = String.format("%.2f Mbps", kbps / 1000.0)

private fun rttShort(rtt: Long): String = if (rtt >= 0) "$rtt ms" else "测量中"

/** 质量分级用"文字 + 数值"双重编码，不单靠颜色。 */
private fun qualityLabel(rtt: Long): String = when {
    rtt < 0 -> "质量 测量中"
    rtt <= 120 -> "质量 优秀"
    rtt <= 250 -> "质量 良好"
    rtt <= 400 -> "质量 一般"
    else -> "质量 较差"
}
