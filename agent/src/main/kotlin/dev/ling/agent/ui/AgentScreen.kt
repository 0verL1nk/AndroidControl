package dev.ling.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
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
 * - 主操作放在下半屏、按钮 64dp，长辈单手够得着；
 * - 技术信息收进隐藏面板（连点图标 7 次），只给部署的子女看。
 */
@Composable
fun AgentScreen(
    state: AgentUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    var tapCount by remember { mutableStateOf(0) }
    var showAdvanced by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val palette = remember(dark, state.phase) { paletteFor(state.phase, dark) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 状态图标：连点 7 次打开高级信息（不提示，避免长辈误触）
        Box(
            modifier = Modifier
                .size(160.dp)
                .background(palette.iconBackground, CircleShape)
                .clickable {
                    tapCount++
                    if (tapCount >= 7) {
                        tapCount = 0
                        showAdvanced = true
                    }
                }
                .semantics {
                    contentDescription = accessibilityText(state)
                    liveRegion = LiveRegionMode.Polite
                },
            contentAlignment = Alignment.Center,
        ) {
            if (state.phase == AssistPhase.Preparing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(72.dp),
                    color = palette.accent,
                    strokeWidth = 6.dp,
                )
            } else {
                Text(
                    text = iconFor(state.phase),
                    fontSize = 72.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = titleFor(state),
            fontSize = 44.sp,
            lineHeight = 52.sp,
            fontWeight = FontWeight.Bold,
            color = palette.title,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = subtitleFor(state),
            fontSize = 22.sp,
            lineHeight = 30.sp,
            color = palette.body,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        // 配对码：只在这台手机"已准备好"时显示，长辈照着念给家人即可
        val token = state.info?.token
        if (state.phase == AssistPhase.Ready && !token.isNullOrBlank()) {
            Spacer(Modifier.height(28.dp))
            Text(
                "配对码",
                fontSize = 20.sp,
                color = palette.body,
            )
            Text(
                text = token.chunked(3).joinToString(" "),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                color = palette.title,
            )
        }

        Spacer(Modifier.height(40.dp))

        Button(
            onClick = {
                when (state.phase) {
                    AssistPhase.Idle, AssistPhase.Error -> onStart()
                    AssistPhase.Ready -> confirmStop = true
                    AssistPhase.Active -> confirmStop = true
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
                .fillMaxWidth(0.8f)
                .height(64.dp),
        ) {
            Text(buttonTextFor(state.phase), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("要结束这次协助吗？", fontSize = 32.sp, fontWeight = FontWeight.Bold) },
            text = { Text("结束后家人将看不到你的手机", fontSize = 22.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    onStop()
                }) {
                    Text("结束", fontSize = 22.sp, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) {
                    Text("取消", fontSize = 22.sp)
                }
            },
        )
    }

    if (showAdvanced) {
        AdvancedDialog(state, onDismiss = { showAdvanced = false })
    }
}

@Composable
private fun AdvancedDialog(state: AgentUiState, onDismiss: () -> Unit) {
    val info = state.info
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高级信息", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("状态：${state.detail}", fontSize = 16.sp)
                Text("配对码：${info?.token ?: "—"}", fontSize = 16.sp)
                Text("控制端口：${info?.controlPort ?: "—"} / 视频：${info?.videoPort ?: "—"}", fontSize = 16.sp)
                Text("会话号：${info?.scid ?: "—"}", fontSize = 16.sp)
                Text("地址：请见通知栏或在路由器上查看本机 IP", fontSize = 16.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 18.sp) }
        },
    )
}

private data class Palette(
    val background: Color,
    val iconBackground: Color,
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
    return when (phase) {
        AssistPhase.Ready -> Palette(
            background = bg,
            iconBackground = if (dark) Tokens.DarkSuccessContainer else Tokens.LightSuccessContainer,
            title = if (dark) Tokens.DarkSuccess else Tokens.LightSuccess,
            body = body,
            accent = if (dark) Tokens.DarkSuccess else Tokens.LightSuccess,
            button = if (dark) Tokens.DarkSurfaceHigh else Tokens.LightSurfaceHigh,
            onButton = title,
        )
        AssistPhase.Active -> Palette(
            background = bg,
            iconBackground = if (dark) Tokens.DarkInfoContainer else Tokens.LightInfoContainer,
            title = if (dark) Tokens.DarkInfo else Tokens.LightInfo,
            body = body,
            accent = if (dark) Tokens.DarkInfo else Tokens.LightInfo,
            button = if (dark) Tokens.DarkDanger else Tokens.LightDanger,
            onButton = Color.White,
        )
        AssistPhase.Error -> Palette(
            background = bg,
            iconBackground = if (dark) Tokens.DarkDangerContainer else Tokens.LightDangerContainer,
            title = if (dark) Tokens.DarkDanger else Tokens.LightDanger,
            body = body,
            accent = if (dark) Tokens.DarkDanger else Tokens.LightDanger,
            button = if (dark) Tokens.DarkDanger else Tokens.LightDanger,
            onButton = Color.White,
        )
        else -> Palette(
            background = bg,
            iconBackground = if (dark) Tokens.DarkSurfaceHigh else Tokens.LightSurfaceHigh,
            title = title,
            body = body,
            accent = body,
            button = if (dark) Tokens.DarkPrimary else Tokens.LightPrimary,
            onButton = if (dark) Color(0xFF06305F) else Color.White,
        )
    }
}

private fun iconFor(phase: AssistPhase): String = when (phase) {
    AssistPhase.Idle -> "🛡"
    AssistPhase.Preparing -> ""
    AssistPhase.Ready -> "✅"
    AssistPhase.Active -> "👁"
    AssistPhase.Error -> "⚠"
}

private fun titleFor(state: AgentUiState): String = when (state.phase) {
    AssistPhase.Idle -> "未开启协助"
    AssistPhase.Preparing -> "正在准备…"
    AssistPhase.Ready -> "已准备好协助"
    AssistPhase.Active -> "正在被协助"
    AssistPhase.Error -> state.error ?: "出了点问题"
}

private fun subtitleFor(state: AgentUiState): String = when (state.phase) {
    AssistPhase.Idle -> "点下面的按钮，家人就可以帮你"
    AssistPhase.Preparing -> "请稍候，不要关机"
    AssistPhase.Ready -> "家人现在可以连接这台手机"
    AssistPhase.Active -> "家人正在看你的手机，有任何不舒服可以随时结束"
    AssistPhase.Error -> "请让家人看看，或者点下面的按钮重试"
}

private fun buttonTextFor(phase: AssistPhase): String = when (phase) {
    AssistPhase.Idle -> "开启协助"
    AssistPhase.Preparing -> "正在准备"
    AssistPhase.Ready -> "结束协助"
    AssistPhase.Active -> "结束协助"
    AssistPhase.Error -> "重试"
}

private fun accessibilityText(state: AgentUiState): String = when (state.phase) {
    AssistPhase.Idle -> "状态：未开启协助。点开启协助按钮，家人就可以帮你"
    AssistPhase.Preparing -> "状态：正在准备，请稍候"
    AssistPhase.Ready -> "状态：已准备好协助。家人现在可以连接这台手机"
    AssistPhase.Active -> "正在被协助。家人正在看你的手机。双击可结束"
    AssistPhase.Error -> "出错了：${state.error ?: "未知原因"}。双击可重试"
}
