package dev.ling.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ling.agent.session.AssistPhase

/**
 * 首页：一眼看清两个角色。
 *
 * 左边是自己的口令与**实时状态**——打开即待命，不需要按"开启"；
 * 右边是输入别人的口令去协助对方。
 */
@Composable
fun HomeScreen(
    myCode: String,
    phase: AssistPhase,
    error: String?,
    shizukuReady: Boolean,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenShizuku: () -> Unit,
    onHelpOther: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val status = statusOf(phase, error, shizukuReady)

    // M3 的根节点用 Surface 提供 background / onBackground：
    // 否则未显式着色的文字会退回黑色，在深色模式下直接看不见。
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(36.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
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
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                    )
                    Text("同一台手机，两种角色", fontSize = 13.sp, color = secondary)
                }
            }

            Spacer(Modifier.height(30.dp))

            SectionCard {
                Text("我的配对码", fontSize = 12.sp, letterSpacing = 1.2.sp, color = secondary)
                Spacer(Modifier.height(8.dp))
                Text(
                    myCode.chunked(3).joinToString("  "),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "别人输入这 6 位数字，就能看到并操作这台手机",
                    fontSize = 13.sp,
                    color = secondary,
                )

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(status.color, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        status.text,
                        fontSize = 13.sp,
                        color = status.color,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        phase == AssistPhase.Active -> TextButton(onClick = onStop) {
                            Text(
                                "结束协助",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        phase == AssistPhase.Error -> TextButton(onClick = onRetry) {
                            Text("重试", fontSize = 13.sp)
                        }
                        // Shizuku 没运行时，恢复只需要"打开 Shizuku → 启动"，这里直接给入口
                        !shizukuReady -> TextButton(onClick = onOpenShizuku) {
                            Text("打开 Shizuku", fontSize = 13.sp)
                        }
                        phase == AssistPhase.Idle -> TextButton(onClick = onOpenDetails) {
                            Text("详情", fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            SectionCard {
                Text("协助别人", fontSize = 12.sp, letterSpacing = 1.2.sp, color = secondary)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { input -> token = input.filter { it.isDigit() }.take(6) },
                    placeholder = { Text("对方手机上的 6 位数字", fontSize = 14.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { onHelpOther(token) },
                    enabled = token.length == 6,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("连接并开始协助", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "被协助时屏幕会显示荧光提示，随时可以点「结束协助」。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

private data class Status(val text: String, val color: Color)

@Composable
private fun statusOf(phase: AssistPhase, error: String?, shizukuReady: Boolean): Status {
    val green = Color(0xFF22A06B)
    val amber = Color(0xFFD97706)
    return when {
        !shizukuReady -> Status(
            "本机未启用 Shizuku · 暂时不能被协助",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        phase == AssistPhase.Preparing -> Status("正在准备…", amber)
        phase == AssistPhase.Ready -> Status("已就绪 · 等待对方连接", green)
        phase == AssistPhase.Active -> Status("正在被协助", MaterialTheme.colorScheme.primary)
        phase == AssistPhase.Error -> Status(
            error ?: "出了点问题",
            MaterialTheme.colorScheme.error,
        )
        else -> Status("未开启", MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}
