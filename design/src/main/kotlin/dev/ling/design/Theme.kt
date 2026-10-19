package dev.ling.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 设计令牌（来自 docs/design/ui-spec.md §2），集中在这里，避免界面里散落魔法色值。
 * 语义色同时用于"状态必须三重编码"（色 + 图标 + 文字）。
 */
object Tokens {
    // 浅色
    val LightBase = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFF2F3F7)
    val LightSurfaceHigh = Color(0xFFE3E6EE)
    val LightTextPrimary = Color(0xFF1A1C1E)
    val LightTextSecondary = Color(0xFF44474E)
    val LightPrimary = Color(0xFF0B57D0)
    val LightSuccess = Color(0xFF1B5E20)
    val LightSuccessContainer = Color(0xFFC6F0CB)
    val LightDanger = Color(0xFFB3261E)
    val LightDangerContainer = Color(0xFFFFDAD6)
    val LightWarning = Color(0xFF7A4F01)
    val LightWarningContainer = Color(0xFFFFDEA6)
    val LightInfo = Color(0xFF0B57D0)
    val LightInfoContainer = Color(0xFFD3E3FD)

    // 深色
    val DarkBase = Color(0xFF101418)
    val DarkSurface = Color(0xFF1B1F24)
    val DarkSurfaceHigh = Color(0xFF252A31)
    val DarkTextPrimary = Color(0xFFE3E2E6)
    val DarkTextSecondary = Color(0xFFC4C6CF)
    val DarkPrimary = Color(0xFFA8C7FA)
    val DarkSuccess = Color(0xFF7BDF8E)
    val DarkSuccessContainer = Color(0xFF0C3A17)
    val DarkDanger = Color(0xFFFFB4AB)
    val DarkDangerContainer = Color(0xFF5E1400)
    val DarkWarning = Color(0xFFF5C56B)
    val DarkWarningContainer = Color(0xFF4A3400)
    val DarkInfo = Color(0xFFA8C7FA)
    val DarkInfoContainer = Color(0xFF0A2E5C)
}

@Composable
fun AppTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (dark) {
        darkColorScheme(
            primary = Tokens.DarkPrimary,
            onPrimary = Color(0xFF06305F),
            background = Tokens.DarkBase,
            onBackground = Tokens.DarkTextPrimary,
            surface = Tokens.DarkSurface,
            onSurface = Tokens.DarkTextPrimary,
            surfaceVariant = Tokens.DarkSurfaceHigh,
            onSurfaceVariant = Tokens.DarkTextSecondary,
            error = Tokens.DarkDanger,
            errorContainer = Tokens.DarkDangerContainer,
        )
    } else {
        lightColorScheme(
            primary = Tokens.LightPrimary,
            onPrimary = Color.White,
            background = Tokens.LightBase,
            onBackground = Tokens.LightTextPrimary,
            surface = Tokens.LightSurface,
            onSurface = Tokens.LightTextPrimary,
            surfaceVariant = Tokens.LightSurfaceHigh,
            onSurfaceVariant = Tokens.LightTextSecondary,
            error = Tokens.LightDanger,
            errorContainer = Tokens.LightDangerContainer,
        )
    }

    MaterialTheme(colorScheme = scheme, content = content)
}
