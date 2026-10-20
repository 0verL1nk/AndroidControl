package dev.ling.agent.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ling.agent.session.AgentUiState
import dev.ling.agent.session.AssistPhase
import dev.ling.design.Tokens

/**
 * 长辈端唯一的主界面。
 *
 * 设计原则（docs/design/ui-spec.md）：
 * - **用眼睛读，不用脑子读**：颜色 + 图标 + 大字同时表达状态，任何一屏 1 秒可懂；
 * - 文案写人话，不出现端口、日志、错误码；
 * - 主操作放在下半屏、按钮高度 64dp，长辈单手够得着；
 * - 技术信息收进隐藏面板（连点图标 7 次），只给部署的子女看。
 *
 * 视觉上刻意克制：一个图标、一句话、一个按钮，没有多余装饰。
 */
@Composable
fun AgentScreen(
    state: AgentUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpenShizuku: () -> Unit = {},
    onRotateToken: () -> Unit = {},
) {
    var tapCount by remember { mutableIntStateOf(0) }
    var showAdvanced by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    val dark = isSystemInDarkTheme()
    val palette = remember(dark, state.phase) { paletteFor(state.phase, dark) }

    val background by animateColorAsState(palette.background, tween(220), label = "bg")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(48.dp))

        StatusIcon(
            phase = state.phase,
            palette = palette,
            onClick = {
                tapCount++
                if (tapCount >= 7) {
                    tapCount = 0
                    showAdvanced = true
                }
            },
            contentDescription = accessibilityText(state),
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = titleFor(state),
            fontSize = 38.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            color = palette.title,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = subtitleFor(state),
            fontSize = 19.sp,
            lineHeight = 28.sp,
            color = palette.body,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )

        // 配对码：只在"已准备好"时出现，照着念给协助者即可
        state.info?.token?.takeIf { state.phase == AssistPhase.Ready && it.isNotBlank() }?.let { token ->
            Spacer(Modifier.height(32.dp))
            PairingCodeCard(token, palette)
        }

        Spacer(Modifier.height(40.dp))

        PrimaryAction(state, palette, onStart, onRetry) { confirmStop = true }

        if (state.phase == AssistPhase.Error && state.error?.contains("Shizuku") == true) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenShizuku) {
                Text("打开 Shizuku，点一下「启动」", fontSize = 18.sp)
            }
        }

        Spacer(Modifier.height(48.dp))
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("要结束这次协助吗？", fontSize = 28.sp, fontWeight = FontWeight.Bold) },
            text = { Text("结束后对方将看不到你的手机", fontSize = 19.sp, lineHeight = 26.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    onStop()
                }) {
                    Text("结束", fontSize = 19.sp, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("取消", fontSize = 19.sp) }
            },
        )
    }

    if (showAdvanced) {
        AdvancedDialog(state, onRotateToken) { showAdvanced = false }
    }
}

/** 状态图标：外圈容器 + 内层图标；被协助时轻微呼吸，让"它正在动"这件事可感知。 */
@Composable
private fun StatusIcon(
    phase: AssistPhase,
    palette: Palette,
    onClick: () -> Unit,
    contentDescription: String,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (phase == AssistPhase.Active) 1.06f else 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "scale",
    )

    Box(
        modifier = Modifier
            .size(168.dp)
            .scale(pulse)
            .background(palette.iconBackground, CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        if (phase == AssistPhase.Preparing) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = palette.accent,
                strokeWidth = 5.dp,
            )
        } else {
            Icon(
                imageVector = iconFor(phase),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(84.dp),
            )
        }
    }
}

/** 配对码卡片：视觉重心，长辈只需要照着念。 */
@Composable
private fun PairingCodeCard(token: String, palette: Palette) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = palette.cardBackground,
        modifier = Modifier.widthIn(min = 260.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 20.dp),
        ) {
            Text("配对码", fontSize = 15.sp, color = palette.body, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text = token.chunked(3).joinToString("  "),
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = palette.title,
            )
            Spacer(Modifier.height(6.dp))
            Text("让对方输入这 6 位数字", fontSize = 15.sp, color = palette.body)
        }
    }
}

/** 主操作：状态决定它的颜色与文案，永远只有一个。 */
@Composable
private fun PrimaryAction(
    state: AgentUiState,
    palette: Palette,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onStopRequested: () -> Unit,
) {
    val label: String
    val icon: ImageVector?
    when (state.phase) {
        AssistPhase.Idle -> {
            label = "开启协助"; icon = Icons.Rounded.TouchApp
        }
        AssistPhase.Preparing -> {
            label = "正在准备"; icon = null
        }
        AssistPhase.Ready, AssistPhase.Active -> {
            label = "结束协助"; icon = null
        }
        AssistPhase.Error -> {
            label = "重试"; icon = Icons.Rounded.Refresh
        }
    }

    Button(
        onClick = {
            when (state.phase) {
                AssistPhase.Idle, AssistPhase.Error -> onStart()
                AssistPhase.Ready, AssistPhase.Active -> onStopRequested()
                AssistPhase.Preparing -> Unit
            }
        },
        enabled = state.phase != AssistPhase.Preparing,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.button,
            contentColor = palette.onButton,
        ),
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .height(64.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AdvancedDialog(
    state: AgentUiState,
    onRotateToken: () -> Unit,
    onDismiss: () -> Unit,
) {
    val info = state.info
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高级信息", fontSize = 22.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("状态：${state.detail}", fontSize = 15.sp)
                Text("配对码：${info?.token ?: "—"}", fontSize = 15.sp)
                Text("控制端口：${info?.controlPort ?: "—"} / 视频：${info?.videoPort ?: "—"}", fontSize = 15.sp)
                Text("会话号：${info?.scid ?: "—"}", fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRotateToken) {
                    Text("重新生成配对码（旧码立即失效）", fontSize = 15.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", fontSize = 17.sp) } },
    )
}

private data class Palette(
    val background: Color,
    val iconBackground: Color,
    val cardBackground: Color,
    val title: Color,
    val body: Color,
    val accent: Color,
    val button: Color,
    val onButton: Color,
)

private fun paletteFor(phase: AssistPhase, dark: Boolean): Palette {
    val bg = if (dark) Tokens.DarkBase else Tokens.LightBase
    val title = if (dark) Tokens.DarkTextPrimary else Tokens.LightTextPrimary
    val body = if (dark) Tokens.DarkTextSecondary else Tokens.LightTextSecondary
    val card = if (dark) Tokens.DarkSurface else Tokens.LightSurface

    return when (phase) {
        AssistPhase.Ready -> Palette(
            background = bg,
            iconBackground = if (dark) Tokens.DarkSuccessContainer else Tokens.LightSuccessContainer,
            cardBackground = card,
            title = if (dark) Tokens.DarkTextPrimary else Tokens.LightTextPrimary,
            body = body,
            accent = if (dark) Tokens.DarkSuccess else Tokens.LightSuccess,
            button = if (dark) Tokens.DarkSurfaceHigh else Tokens.LightSurfaceHigh,
            onButton = if (dark) Tokens.DarkSuccess else Tokens.LightSuccess,
        )
        AssistPhase.Active -> Palette(
            background = bg,
            iconBackground = if (dark) Tokens.DarkInfoContainer else Tokens.LightInfoContainer,
            cardBackground = card,
            title = if (dark) Tokens.DarkInfo else Tokens.LightInfo,
            body = body,
            accent = if (dark) Tokens.DarkInfo else Tokens.LightInfo,
            button = if (dark) Tokens.DarkDanger else Tokens.LightDanger,
            onButton = Color.White,
        )
        AssistPhase.Error -> Palette(
            background = bg,
            iconBackground = if (dark) Tokens.DarkDangerContainer else Tokens.LightDangerContainer,
            cardBackground = card,
            title = if (dark) Tokens.DarkDanger else Tokens.LightDanger,
            body = body,
            accent = if (dark) Tokens.DarkDanger else Tokens.LightDanger,
            button = if (dark) Tokens.DarkDanger else Tokens.LightDanger,
            onButton = Color.White,
        )
        else -> Palette(
            background = bg,
            iconBackground = if (dark) Tokens.DarkSurfaceHigh else Tokens.LightSurfaceHigh,
            cardBackground = card,
            title = title,
            body = body,
            accent = body,
            button = if (dark) Tokens.DarkPrimary else Tokens.LightPrimary,
            onButton = if (dark) Color(0xFF06305F) else Color.White,
        )
    }
}

/** 图标用 Material 图标库，不用 emoji——emoji 在不同 ROM 上字形不一，也不成体系。 */
private fun iconFor(phase: AssistPhase): ImageVector = when (phase) {
    AssistPhase.Idle -> Icons.Rounded.TouchApp
    AssistPhase.Preparing -> Icons.Rounded.TouchApp // 实际走进度环分支
    AssistPhase.Ready -> Icons.Rounded.VerifiedUser
    AssistPhase.Active -> Icons.Rounded.Visibility
    AssistPhase.Error -> Icons.Rounded.ErrorOutline
}

private fun titleFor(state: AgentUiState): String = when (state.phase) {
    AssistPhase.Idle -> "未开启协助"
    AssistPhase.Preparing -> "正在准备…"
    AssistPhase.Ready -> "已准备好协助"
    AssistPhase.Active -> "正在被协助"
    AssistPhase.Error -> state.error ?: "出了点问题"
}

private fun subtitleFor(state: AgentUiState): String = when (state.phase) {
    AssistPhase.Idle -> "点下面的按钮，对方就可以帮你"
    AssistPhase.Preparing -> "请稍候，不要关机"
    AssistPhase.Ready -> "对方现在可以连接这台手机"
    AssistPhase.Active -> "对方正在看你的手机，有任何不舒服可以随时结束"
    AssistPhase.Error -> "请让协助你的人看看，或者点下面的按钮重试"
}

private fun accessibilityText(state: AgentUiState): String = when (state.phase) {
    AssistPhase.Idle -> "状态：未开启协助。点开启协助按钮，对方就可以帮你"
    AssistPhase.Preparing -> "状态：正在准备，请稍候"
    AssistPhase.Ready -> "状态：已准备好协助。对方现在可以连接这台手机"
    AssistPhase.Active -> "正在被协助。对方正在看你的手机。双击可结束"
    AssistPhase.Error -> "出错了：${state.error ?: "未知原因"}。双击可重试"
}
