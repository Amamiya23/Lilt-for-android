package com.lilt.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Radix Colors: steps 1–2 backgrounds, 3–5 surfaces, 6–8 borders, 9–10 solids, 11–12 text.
object Radix {
    val Gray1 = Color(0xFFFCFCFC)
    val Gray2 = Color(0xFFF9F9F9)
    val Gray3 = Color(0xFFF0F0F0)
    val Gray6 = Color(0xFFE2E2E2)
    val Gray8 = Color(0xFFBBBBBB)
    val Gray11 = Color(0xFF646464)
    val Gray12 = Color(0xFF202020)
    val Green3 = Color(0xFFE6F6EB)
    val Green9 = Color(0xFF30A46C)
    val Green10 = Color(0xFF2B9A66)
    val Green11 = Color(0xFF218358)
    val Green12 = Color(0xFF193B2D)
    val GrayDark1 = Color(0xFF111111)
    val GrayDark2 = Color(0xFF191919)
    val GrayDark3 = Color(0xFF222222)
    val GrayDark4 = Color(0xFF2A2A2A)
    val GrayDark6 = Color(0xFF3A3A3A)
    val GrayDark8 = Color(0xFF606060)
    val GrayDark11 = Color(0xFFB4B4B4)
    val GrayDark12 = Color(0xFFEEEEEE)
    val GreenDark3 = Color(0xFF132D21)
    val GreenDark11 = Color(0xFF3DD68C)
    val Ruby3 = Color(0xFFFFEDEF)
    val RubyDark3 = Color(0xFF3B1219)
    val RubyDark11 = Color(0xFFFF949D)
    val White = Color.White
    val Black = Color.Black
    val Ruby9 = Color(0xFFE54666)
    val Ruby11 = Color(0xFFCA244D)
    val Ruby12 = Color(0xFF64172B)
    val Mauve12 = Color(0xFF211F26)
    val MauveDark2 = Color(0xFF1A191B)
    val Plum12 = Color(0xFF53195D)
    val Sand12 = Color(0xFF21201C)
    val Sand11 = Color(0xFF63635E)
}

enum class ThemeMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }

private val Light = lightColorScheme(
    primary = Radix.Green11, onPrimary = Radix.White,
    primaryContainer = Radix.Green3, onPrimaryContainer = Radix.Green11,
    secondary = Radix.Gray11, onSecondary = Radix.White,
    secondaryContainer = Radix.Green3, onSecondaryContainer = Radix.Green12,
    tertiary = Radix.Green11, onTertiary = Radix.White,
    tertiaryContainer = Radix.Green3, onTertiaryContainer = Radix.Green12,
    background = Radix.Gray2, onBackground = Radix.Gray12,
    surface = Radix.White, onSurface = Radix.Gray12,
    surfaceVariant = Radix.Gray3, onSurfaceVariant = Radix.Gray11,
    surfaceTint = Radix.Green11,
    inverseSurface = Radix.Gray12, inverseOnSurface = Radix.Gray2, inversePrimary = Radix.GreenDark11,
    surfaceBright = Radix.White, surfaceDim = Radix.Gray3,
    surfaceContainer = Radix.Gray3, surfaceContainerLow = Radix.Gray1,
    surfaceContainerLowest = Radix.White, surfaceContainerHigh = Radix.Gray3,
    surfaceContainerHighest = Radix.Gray6,
    outline = Radix.Gray8, outlineVariant = Radix.Gray6,
    error = Radix.Ruby11, onError = Radix.White,
    errorContainer = Radix.Ruby3, onErrorContainer = Radix.Ruby12,
    scrim = Radix.Black,
    primaryFixed = Radix.Green3, primaryFixedDim = Radix.Green3,
    onPrimaryFixed = Radix.Green12, onPrimaryFixedVariant = Radix.Green11,
    secondaryFixed = Radix.Gray3, secondaryFixedDim = Radix.Gray6,
    onSecondaryFixed = Radix.Gray12, onSecondaryFixedVariant = Radix.Gray11,
    tertiaryFixed = Radix.Green3, tertiaryFixedDim = Radix.Green3,
    onTertiaryFixed = Radix.Green12, onTertiaryFixedVariant = Radix.Green11,
)
private val Dark = darkColorScheme(
    primary = Radix.GreenDark11, onPrimary = Radix.GreenDark3,
    primaryContainer = Radix.GreenDark3, onPrimaryContainer = Radix.GreenDark11,
    secondary = Radix.GrayDark11, onSecondary = Radix.GrayDark1,
    secondaryContainer = Radix.GreenDark3, onSecondaryContainer = Radix.GreenDark11,
    tertiary = Radix.GreenDark11, onTertiary = Radix.GreenDark3,
    tertiaryContainer = Radix.GreenDark3, onTertiaryContainer = Radix.GreenDark11,
    background = Radix.GrayDark1, onBackground = Radix.GrayDark12,
    surface = Radix.GrayDark2, onSurface = Radix.GrayDark12,
    surfaceVariant = Radix.GrayDark3, onSurfaceVariant = Radix.GrayDark11,
    surfaceTint = Radix.GreenDark11,
    inverseSurface = Radix.GrayDark12, inverseOnSurface = Radix.GrayDark2, inversePrimary = Radix.Green11,
    surfaceBright = Radix.GrayDark4, surfaceDim = Radix.GrayDark1,
    surfaceContainer = Radix.GrayDark3, surfaceContainerLow = Radix.GrayDark2,
    surfaceContainerLowest = Radix.GrayDark1, surfaceContainerHigh = Radix.GrayDark4,
    surfaceContainerHighest = Radix.GrayDark6,
    outline = Radix.GrayDark8, outlineVariant = Radix.GrayDark6,
    error = Radix.RubyDark11, onError = Radix.Ruby12,
    errorContainer = Radix.RubyDark3, onErrorContainer = Radix.RubyDark11,
    scrim = Radix.Black,
    primaryFixed = Radix.Green3, primaryFixedDim = Radix.Green3,
    onPrimaryFixed = Radix.Green12, onPrimaryFixedVariant = Radix.Green11,
    secondaryFixed = Radix.Gray3, secondaryFixedDim = Radix.Gray6,
    onSecondaryFixed = Radix.Gray12, onSecondaryFixedVariant = Radix.Gray11,
    tertiaryFixed = Radix.Green3, tertiaryFixedDim = Radix.Green3,
    onTertiaryFixed = Radix.Green12, onTertiaryFixedVariant = Radix.Green11,
)
val LiltTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun LiltTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.Dark || (mode == ThemeMode.System && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = LiltTypography, content = content)
}
