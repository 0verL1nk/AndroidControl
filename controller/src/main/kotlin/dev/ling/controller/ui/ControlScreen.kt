package dev.ling.controller.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ling.controller.core.ControlChannel
import dev.ling.controller.core.DeviceInfo
import dev.ling.controller.core.RemoteSession
import dev.ling.controller.core.VoiceLink

/** 性能与连接状态，界面只读。 */
data class SessionStats(
    val fps: Double = 0.0,
    val kbps: Double = 0.0,
    val rttMs: Long = -1,
    val idleSeconds: Int = 0,
    val reconnecting: Int? = null,
    val reconnectMax: Int = 60,
    val failure: String? = null,
)

/**
 * 操控页：全屏画面 + 悬浮操作条。
 *
 * 安全边界常驻顶部（"正在控制 XX 的手机"）；弱网时立刻盖遮罩并给出**可数进度**，
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
    var voiceOn by remember { mutableStateOf(false) }
    val voice = remember { VoiceLink(device) }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voice.start()
            voiceOn = true
        } else {
            toast(context, "需要麦克风权限才能语音")
        }
    }

    DisposableEffect(Unit) {
        voice.onState = { connected, reason ->
            voiceOn = connected
            if (!connected && reason != null) toast(context, reason)
        }
        onDispose { voice.stop() }
    }

    val toggleVoice: () -> Unit = {
        when {
            voiceOn -> {
                voice.stop()
                voiceOn = false
            }
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> {
                voice.start()
                voiceOn = true
            }
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val listener = remember {
        object : RemoteSession.Listener {
            override fun onConnected(frameWidth: Int, frameHeight: Int) {
                stats = stats.copy(reconnecting = null, failure = null)
            }

            override fun onSizeChanged(frameWidth: Int, frameHeight: Int) = Unit

            override fun onStats(fps: Double, kbps: Double, rttMs: Long, idleSeconds: Int) {
                stats = stats.copy(fps = fps, kbps = kbps, rttMs = rttMs, idleSeconds = idleSeconds)
            }

            override fun onReconnecting(attempt: Int, max: Int) {
                stats = stats.copy(reconnecting = attempt, reconnectMax = max)
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

        TopBar(
            device = device,
            stats = stats,
            showHud = showHud,
            onToggleHud = { showHud = !showHud },
            onWake = { session?.sendKey(ControlChannel.Keys.WAKEUP) },
            voiceOn = voiceOn,
            onToggleVoice = toggleVoice,
        )

        if (showHud) {
            Hud(
                stats,
                Modifier.align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 46.dp, end = 12.dp),
            )
        }

        BottomBar(
            locked = locked,
            onBack = { session?.sendKey(ControlChannel.Keys.BACK) },
            onHome = { session?.sendKey(ControlChannel.Keys.HOME) },
            onRecents = { session?.sendKey(ControlChannel.Keys.APP_SWITCH) },
            onVolumeUp = { session?.sendKey(ControlChannel.Keys.VOLUME_UP) },
            onVolumeDown = { session?.sendKey(ControlChannel.Keys.VOLUME_DOWN) },
            onToggleLock = { locked = !locked },
            onDisconnect = { confirmDisconnect = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        stats.reconnecting?.let { attempt ->
            Scrim {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "正在重连…（$attempt/${stats.reconnectMax}）",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "网络不稳定，请稍候，无需操作",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { onExit(stats.failure) }) {
                        Text("断开", fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        }

        stats.failure?.let { reason ->
            Scrim {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(reason, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { onExit(reason) }) {
                        Text("返回", fontSize = 15.sp, color = Color.White)
                    }
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
            title = { Text("要断开连接吗？", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("断开后将无法继续操作对方手机", fontSize = 15.sp) },
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
private fun TopBar(
    device: DeviceInfo,
    stats: SessionStats,
    showHud: Boolean,
    onToggleHud: () -> Unit,
    onWake: () -> Unit,
    voiceOn: Boolean,
    onToggleVoice: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .statusBarsPadding()
                .height(38.dp)
                .padding(start = 12.dp, end = 4.dp),
        ) {
            Icon(
                Icons.Rounded.Visibility,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "正在控制 " + device.name,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "%.0f".format(stats.fps) + " fps · " + (if (stats.rttMs >= 0) "${stats.rttMs} ms" else "—"),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onToggleVoice, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (voiceOn) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                    contentDescription = if (voiceOn) "关闭语音" else "开启语音",
                    tint = if (voiceOn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onWake, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.Lightbulb,
                    contentDescription = "点亮对方屏幕",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onToggleHud, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (showHud) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (showHud) "隐藏浮层" else "显示浮层",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Hud(stats: SessionStats, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.58f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            HudLine("帧率", "%.1f fps".format(stats.fps))
            HudLine("码率", "%.2f Mbps".format(stats.kbps / 1000))
            HudLine("网络", if (stats.rttMs >= 0) "${stats.rttMs} ms" else "—")
            HudLine("质量", qualityLabel(stats.rttMs))
            if (stats.idleSeconds >= 5) {
                HudLine("画面", "${stats.idleSeconds}s 未更新")
            }
        }
    }
}

@Composable
private fun HudLine(label: String, value: String) {
    Row {
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.55f))
        Spacer(Modifier.size(6.dp))
        Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
    }
}

@Composable
private fun BottomBar(
    locked: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onToggleLock: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        shape = RoundedCornerShape(26.dp),
        shadowElevation = 10.dp,
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(58.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        ) {
            BarButton(Icons.Rounded.ArrowBack, "返回", onBack)
            BarButton(Icons.Rounded.Home, "主页", onHome)
            BarButton(Icons.Rounded.Apps, "最近任务", onRecents)
            BarButton(Icons.Rounded.VolumeDown, "音量减", onVolumeDown)
            BarButton(Icons.Rounded.VolumeUp, "音量加", onVolumeUp)
            BarButton(
                if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                if (locked) "解除锁定" else "锁定画面",
                onToggleLock,
            )
            BarButton(Icons.Rounded.LinkOff, "断开", onDisconnect, danger = true)
        }
    }
}

@Composable
private fun BarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun Scrim(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Toast 必须在主线程；语音状态回调来自网络线程。 */
private fun toast(context: Context, message: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 质量分级用"文字 + 数值"双重编码，不单靠颜色。 */
private fun qualityLabel(rtt: Long): String = when {
    rtt < 0 -> "测量中"
    rtt <= 120 -> "优秀"
    rtt <= 250 -> "良好"
    rtt <= 400 -> "一般"
    else -> "较差"
}
