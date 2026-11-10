package com.lilt.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Fixed colours shared by both themes: the player's backdrop, artwork placeholders and the daily card.
object Radix {
    val White = Color.White
    val Black = Color.Black
    val Gray8 = Color(0xFFB4B8BF)
    val PlayerBase = Color(0xFF5A3440)
    val PlayerTint = Color(0xFF3A1F2A)
    val PlayerShade = Color(0xFF1A0F14)
    val Like = Color(0xFFF0384B)
    val Ink = Color(0xFF1B211E)
    val ChartRise = Color(0xFF1FB86A)
    val ChartHot = Color(0xFFF2994A)
    val ChartNew = Color(0xFF5B8DEF)
    val Clover = Color(0xFF2BB673)
    val VipStart = Color(0xFF2BC77A)
    val VipEnd = Color(0xFF16A35C)
}

/** The Pencil design's tokens that Material's colour scheme has no slot for. */
@Immutable
data class LiltColors(
    val textTertiary: Color,
    val divider: Color,
    val like: Color,
    val glass: Color,
    val glassEdge: Color,
    val chipGlass: Color,
    val shadow: Color,
)

private val LightTokens = LiltColors(
    textTertiary = Color(0xFFB4B8BF), divider = Color(0xFFECEEF1), like = Color(0xFFF0384B),
    glass = Color(0xC2F8F9FB), glassEdge = Color(0x1216181C), chipGlass = Color(0x8CFFFFFF), shadow = Color(0xFF16181C),
)
private val DarkTokens = LiltColors(
    textTertiary = Color(0xFF5A5E66), divider = Color(0xFF26272B), like = Color(0xFFFF5A6A),
    glass = Color(0xC21A1B1E), glassEdge = Color(0x14FFFFFF), chipGlass = Color(0x8C1A1B1E), shadow = Color(0xFF000000),
)
val LocalLiltColors = staticCompositionLocalOf { LightTokens }

enum class ThemeMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }

private val Light = lightColorScheme(
    primary = Color(0xFF1FB86A), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F6EC), onPrimaryContainer = Color(0xFF1FB86A),
    secondary = Color(0xFF8A8F98), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3F6EC), onSecondaryContainer = Color(0xFF16181C),
    tertiary = Color(0xFF1FB86A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE3F6EC), onTertiaryContainer = Color(0xFF16181C),
    background = Color(0xFFF5F6F8), onBackground = Color(0xFF16181C),
    surface = Color.White, onSurface = Color(0xFF16181C),
    surfaceVariant = Color(0xFFEEF0F3), onSurfaceVariant = Color(0xFF8A8F98),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF16181C), inverseOnSurface = Color(0xFFF5F6F8), inversePrimary = Color(0xFF3DD68C),
    surfaceBright = Color.White, surfaceDim = Color(0xFFEEF0F3),
    surfaceContainer = Color.White, surfaceContainerLow = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFEEF0F3),
    outline = Color(0xFFB4B8BF), outlineVariant = Color(0xFFECEEF1),
    error = Color(0xFFE5484D), onError = Color.White,
    errorContainer = Color(0xFFFFEDEE), onErrorContainer = Color(0xFFE5484D),
    scrim = Color.Black,
)
private val Dark = darkColorScheme(
    primary = Color(0xFF3DD68C), onPrimary = Color(0xFF06210F),
    primaryContainer = Color(0xFF1C3328), onPrimaryContainer = Color(0xFF3DD68C),
    secondary = Color(0xFF8A8F98), onSecondary = Color(0xFF0F1012),
    secondaryContainer = Color(0xFF1C3328), onSecondaryContainer = Color(0xFFF2F3F5),
    tertiary = Color(0xFF3DD68C), onTertiary = Color(0xFF06210F),
    tertiaryContainer = Color(0xFF1C3328), onTertiaryContainer = Color(0xFFF2F3F5),
    background = Color(0xFF0F1012), onBackground = Color(0xFFF2F3F5),
    surface = Color(0xFF1A1B1E), onSurface = Color(0xFFF2F3F5),
    surfaceVariant = Color(0xFF242529), onSurfaceVariant = Color(0xFF8A8F98),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF2F3F5), inverseOnSurface = Color(0xFF1A1B1E), inversePrimary = Color(0xFF1FB86A),
    surfaceBright = Color(0xFF242529), surfaceDim = Color(0xFF0F1012),
    surfaceContainer = Color(0xFF1A1B1E), surfaceContainerLow = Color(0xFF1A1B1E),
    surfaceContainerLowest = Color(0xFF0F1012), surfaceContainerHigh = Color(0xFF242529),
    surfaceContainerHighest = Color(0xFF2E2F33),
    outline = Color(0xFF5A5E66), outlineVariant = Color(0xFF26272B),
    error = Color(0xFFFF6369), onError = Color(0xFF0F1012),
    errorContainer = Color(0xFF3B1219), onErrorContainer = Color(0xFFFF6369),
    scrim = Color.Black,
)
val LiltTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun LiltTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.Dark || (mode == ThemeMode.System && isSystemInDarkTheme())
    CompositionLocalProvider(LocalLiltColors provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = LiltTypography, content = content)
    }
}

/** The design's extra tokens for the current theme. */
val MaterialTheme.lilt: LiltColors
    @Composable @ReadOnlyComposable get() = LocalLiltColors.current
