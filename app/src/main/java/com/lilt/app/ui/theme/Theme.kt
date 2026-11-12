package com.lilt.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Radix Colors sRGB scales. Steps 1–2: surfaces, 3–5: controls, 6–8: borders, 11–12: text.
// https://www.radix-ui.com/colors/docs/palette-composition/understanding-the-scale
object Radix {
    val White = Color.White
    val Black = Color.Black
    val Slate1 = Color(0xFFFCFCFD)
    val Slate2 = Color(0xFFF9F9FB)
    val Slate3 = Color(0xFFF0F0F3)
    val Slate6 = Color(0xFFD9D9E0)
    val Slate8 = Color(0xFFB9BBC6)
    val Slate11 = Color(0xFF60646C)
    val Slate12 = Color(0xFF1C2024)
    val SlateDark1 = Color(0xFF111113)
    val SlateDark2 = Color(0xFF18191B)
    val SlateDark3 = Color(0xFF212225)
    val SlateDark4 = Color(0xFF272A2D)
    val SlateDark5 = Color(0xFF2E3135)
    val SlateDark6 = Color(0xFF363A3F)
    val SlateDark8 = Color(0xFF5A6169)
    val SlateDark11 = Color(0xFFB0B4BA)
    val SlateDark12 = Color(0xFFEDEEF0)
    val Green3 = Color(0xFFE6F6EB)
    val Green9 = Color(0xFF30A46C)
    val Green11 = Color(0xFF218358)
    val Green12 = Color(0xFF193B2D)
    val GreenDark1 = Color(0xFF0E1512)
    val GreenDark3 = Color(0xFF132D21)
    val GreenDark11 = Color(0xFF3DD68C)
    val Ruby3 = Color(0xFFFEEAED)
    val Ruby11 = Color(0xFFCA244D)
    val RubyDark3 = Color(0xFF3A141E)
    val RubyDark7 = Color(0xFF883447)
    val RubyDark9 = Color(0xFFE54666)
    val RubyDark11 = Color(0xFFFF949D)
    val PlayerBase = RubyDark7
    val PlayerTint = RubyDark3
    val PlayerShade = Color(0xFF191113) // rubyDark1
    val DailyStart = Color(0xFF853A2D) // tomatoDark7
    val DailyEnd = Color(0xFFAC4D39) // tomatoDark8
    val DailyShade = Color(0xFF391714) // tomatoDark3
    val Like = RubyDark11
    val Ink = Slate12
    val ChartRise = Green11
    val ChartHot = Color(0xFFCC4E00) // orange11
    val ChartNew = Color(0xFF0D74CE) // blue11
    val Clover = Green11
    val VipStart = Green9
    val VipEnd = Green11
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
    textTertiary = Radix.Slate11, divider = Radix.Slate6, like = Radix.Ruby11,
    glass = Radix.Slate2, glassEdge = Radix.Slate6, chipGlass = Radix.White.copy(alpha = .55f), shadow = Radix.Slate12,
)
private val DarkTokens = LiltColors(
    textTertiary = Radix.SlateDark11, divider = Radix.SlateDark6, like = Radix.RubyDark11,
    glass = Radix.SlateDark2, glassEdge = Radix.SlateDark6, chipGlass = Radix.SlateDark2.copy(alpha = .55f), shadow = Radix.Black,
)
val LocalLiltColors = staticCompositionLocalOf { LightTokens }

enum class ThemeMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }

private val Light = lightColorScheme(
    primary = Radix.Green11, onPrimary = Radix.White,
    primaryContainer = Radix.Green3, onPrimaryContainer = Radix.Green11,
    secondary = Radix.Slate11, onSecondary = Radix.White,
    secondaryContainer = Radix.Green3, onSecondaryContainer = Radix.Slate12,
    tertiary = Radix.Green11, onTertiary = Radix.White,
    tertiaryContainer = Radix.Green3, onTertiaryContainer = Radix.Slate12,
    background = Radix.Slate2, onBackground = Radix.Slate12,
    surface = Radix.White, onSurface = Radix.Slate12,
    surfaceVariant = Radix.Slate3, onSurfaceVariant = Radix.Slate11,
    surfaceTint = Color.Transparent,
    inverseSurface = Radix.Slate12, inverseOnSurface = Radix.Slate2, inversePrimary = Radix.GreenDark11,
    surfaceBright = Radix.White, surfaceDim = Radix.Slate3,
    surfaceContainer = Radix.White, surfaceContainerLow = Radix.White,
    surfaceContainerLowest = Radix.White, surfaceContainerHigh = Radix.White,
    surfaceContainerHighest = Radix.Slate3,
    outline = Radix.Slate8, outlineVariant = Radix.Slate6,
    error = Radix.Ruby11, onError = Radix.White,
    errorContainer = Radix.Ruby3, onErrorContainer = Radix.Ruby11,
    scrim = Radix.Black,
)
private val Dark = darkColorScheme(
    primary = Radix.GreenDark11, onPrimary = Radix.GreenDark1,
    primaryContainer = Radix.GreenDark3, onPrimaryContainer = Radix.GreenDark11,
    secondary = Radix.SlateDark11, onSecondary = Radix.SlateDark1,
    secondaryContainer = Radix.GreenDark3, onSecondaryContainer = Radix.SlateDark12,
    tertiary = Radix.GreenDark11, onTertiary = Radix.GreenDark1,
    tertiaryContainer = Radix.GreenDark3, onTertiaryContainer = Radix.SlateDark12,
    background = Radix.SlateDark1, onBackground = Radix.SlateDark12,
    surface = Radix.SlateDark2, onSurface = Radix.SlateDark12,
    surfaceVariant = Radix.SlateDark3, onSurfaceVariant = Radix.SlateDark11,
    surfaceTint = Color.Transparent,
    inverseSurface = Radix.SlateDark12, inverseOnSurface = Radix.SlateDark2, inversePrimary = Radix.Green11,
    surfaceBright = Radix.SlateDark4, surfaceDim = Radix.SlateDark1,
    surfaceContainer = Radix.SlateDark2, surfaceContainerLow = Radix.SlateDark2,
    surfaceContainerLowest = Radix.SlateDark1, surfaceContainerHigh = Radix.SlateDark4,
    surfaceContainerHighest = Radix.SlateDark5,
    outline = Radix.SlateDark8, outlineVariant = Radix.SlateDark6,
    error = Radix.RubyDark11, onError = Radix.SlateDark1,
    errorContainer = Radix.RubyDark3, onErrorContainer = Radix.RubyDark11,
    scrim = Radix.Black,
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
