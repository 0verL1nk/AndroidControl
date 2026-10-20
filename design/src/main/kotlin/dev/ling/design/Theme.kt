package dev.ling.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 设计令牌（来自 docs/design/ui-spec.md §2）。
 *
 * 这里同时是 **Material 3 配色方案的唯一来源**：所有界面只通过
 * `MaterialTheme.colorScheme.*` 取色，缺角色时不会掉回 M3 的默认紫色基线。
 * 状态色（成功/警告/危险）作为额外令牌保留，用于"颜色 + 图标 + 文字"三重编码。
 */
object Tokens {
    // ── 浅色 ────────────────────────────────────────────────
    val LightBase = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceLow = Color(0xFFF7F8FA)
    val LightSurfaceContainer = Color(0xFFEFF1F5)
    val LightSurfaceHigh = Color(0xFFE3E6EE)
    val LightSurfaceHighest = Color(0xFFD8DCE5)
    val LightTextPrimary = Color(0xFF1A1C1E)
    val LightTextSecondary = Color(0xFF44474E)
    val LightPrimary = Color(0xFF0B57D0)
    val LightPrimaryContainer = Color(0xFFD3E3FD)
    val LightOnPrimaryContainer = Color(0xFF041E49)
    val LightOutline = Color(0xFF74777F)
    val LightOutlineVariant = Color(0xFFC4C6D0)
    val LightSuccess = Color(0xFF1B5E20)
    val LightSuccessContainer = Color(0xFFC6F0CB)
    val LightDanger = Color(0xFFB3261E)
    val LightDangerContainer = Color(0xFFF9DEDC)
    val LightOnDangerContainer = Color(0xFF410E0B)
    val LightWarning = Color(0xFF7A4F01)
    val LightWarningContainer = Color(0xFFFFDEA6)
    val LightInfo = Color(0xFF0B57D0)
    val LightInfoContainer = Color(0xFFD3E3FD)

    // ── 深色 ────────────────────────────────────────────────
    val DarkBase = Color(0xFF101418)
    val DarkSurface = Color(0xFF1B1F24)
    val DarkSurfaceLow = Color(0xFF14181D)
    val DarkSurfaceContainer = Color(0xFF1B1F24)
    val DarkSurfaceHigh = Color(0xFF252A31)
    val DarkSurfaceHighest = Color(0xFF2E343C)
    val DarkTextPrimary = Color(0xFFE3E2E6)
    val DarkTextSecondary = Color(0xFFC4C6CF)
    val DarkPrimary = Color(0xFFA8C7FA)
    val DarkOnPrimary = Color(0xFF06305F)
    val DarkPrimaryContainer = Color(0xFF0842A0)
    val DarkOnPrimaryContainer = Color(0xFFD3E3FD)
    val DarkOutline = Color(0xFF8E9099)
    val DarkOutlineVariant = Color(0xFF44474F)
    val DarkSuccess = Color(0xFF7BDF8E)
    val DarkSuccessContainer = Color(0xFF0C3A17)
    val DarkDanger = Color(0xFFFFB4AB)
    val DarkDangerContainer = Color(0xFF5E1400)
    val DarkOnDangerContainer = Color(0xFFFFDAD6)
    val DarkWarning = Color(0xFFF5C56B)
    val DarkWarningContainer = Color(0xFF4A3400)
    val DarkInfo = Color(0xFFA8C7FA)
    val DarkInfoContainer = Color(0xFF0A2E5C)
}

private val LightScheme = lightColorScheme(
    primary = Tokens.LightPrimary,
    onPrimary = Color.White,
    primaryContainer = Tokens.LightPrimaryContainer,
    onPrimaryContainer = Tokens.LightOnPrimaryContainer,
    inversePrimary = Tokens.DarkPrimary,

    secondary = Color(0xFF525F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E3F7),
    onSecondaryContainer = Color(0xFF0E1D2F),

    tertiary = Color(0xFF00696E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF9CF1F6),
    onTertiaryContainer = Color(0xFF002022),

    background = Tokens.LightBase,
    onBackground = Tokens.LightTextPrimary,

    surface = Tokens.LightSurface,
    onSurface = Tokens.LightTextPrimary,
    surfaceVariant = Tokens.LightSurfaceContainer,
    onSurfaceVariant = Tokens.LightTextSecondary,
    surfaceTint = Tokens.LightPrimary,
    surfaceDim = Tokens.LightSurfaceHighest,
    surfaceBright = Tokens.LightBase,
    surfaceContainerLowest = Tokens.LightBase,
    surfaceContainerLow = Tokens.LightSurfaceLow,
    surfaceContainer = Tokens.LightSurfaceContainer,
    surfaceContainerHigh = Tokens.LightSurfaceHigh,
    surfaceContainerHighest = Tokens.LightSurfaceHighest,

    error = Tokens.LightDanger,
    onError = Color.White,
    errorContainer = Tokens.LightDangerContainer,
    onErrorContainer = Tokens.LightOnDangerContainer,

    outline = Tokens.LightOutline,
    outlineVariant = Tokens.LightOutlineVariant,
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    scrim = Color.Black,
)

private val DarkScheme = darkColorScheme(
    primary = Tokens.DarkPrimary,
    onPrimary = Tokens.DarkOnPrimary,
    primaryContainer = Tokens.DarkPrimaryContainer,
    onPrimaryContainer = Tokens.DarkOnPrimaryContainer,
    inversePrimary = Tokens.LightPrimary,

    secondary = Color(0xFFBCC7DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4858),
    onSecondaryContainer = Color(0xFFD8E3F8),

    tertiary = Color(0xFF7BD0D6),
    onTertiary = Color(0xFF003739),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFF9CF1F6),

    background = Tokens.DarkBase,
    onBackground = Tokens.DarkTextPrimary,

    surface = Tokens.DarkSurface,
    onSurface = Tokens.DarkTextPrimary,
    surfaceVariant = Tokens.DarkSurfaceHigh,
    onSurfaceVariant = Tokens.DarkTextSecondary,
    surfaceTint = Tokens.DarkPrimary,
    surfaceDim = Tokens.DarkBase,
    surfaceBright = Color(0xFF363B43),
    surfaceContainerLowest = Color(0xFF0B0E12),
    surfaceContainerLow = Tokens.DarkSurfaceLow,
    surfaceContainer = Tokens.DarkSurfaceContainer,
    surfaceContainerHigh = Tokens.DarkSurfaceHigh,
    surfaceContainerHighest = Tokens.DarkSurfaceHighest,

    error = Tokens.DarkDanger,
    onError = Color(0xFF690005),
    errorContainer = Tokens.DarkDangerContainer,
    onErrorContainer = Tokens.DarkOnDangerContainer,

    outline = Tokens.DarkOutline,
    outlineVariant = Tokens.DarkOutlineVariant,
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    scrim = Color.Black,
)

@Composable
fun AppTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}
