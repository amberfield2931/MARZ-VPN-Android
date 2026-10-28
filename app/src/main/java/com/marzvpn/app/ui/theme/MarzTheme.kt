package com.marzvpn.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marzvpn.app.settings.ThemeMode

// marzvpn.com palette: paper #f3f3ee, lime #d5f15f, green #476443, ink #20231f.
private val Paper = Color(0xFFF3F3EE)
private val Lime = Color(0xFFD5F15F)
private val Green = Color(0xFF476443)
private val GreenDeep = Color(0xFF2E4A2B)
private val Ink = Color(0xFF20231F)
private val Muted = Color(0xFF596151)
private val Line = Color(0xFFD9DCD4)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE8D6),
    onPrimaryContainer = GreenDeep,
    secondary = Lime,
    onSecondary = Ink,
    secondaryContainer = Color(0xFFEFF8C6),
    onSecondaryContainer = Ink,
    tertiary = GreenDeep,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7E8E0),
    onSurfaceVariant = Muted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAF6),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDEEE7),
    outline = Color(0xFFB9BEB2),
    outlineVariant = Line,
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9CC9C),
    onPrimary = Color(0xFF15281A),
    primaryContainer = Color(0xFF2E4A2B),
    onPrimaryContainer = Color(0xFFDDE8D6),
    secondary = Lime,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF3B4521),
    onSecondaryContainer = Color(0xFFEFF8C6),
    tertiary = Color(0xFFC4DDB8),
    background = Color(0xFF121411),
    onBackground = Color(0xFFE6E8E1),
    surface = Color(0xFF121411),
    onSurface = Color(0xFFE6E8E1),
    surfaceVariant = Color(0xFF2A2E28),
    onSurfaceVariant = Color(0xFFB8BDB1),
    surfaceContainerLowest = Color(0xFF0D0F0C),
    surfaceContainerLow = Color(0xFF171A16),
    surfaceContainer = Color(0xFF1C1F1B),
    surfaceContainerHigh = Color(0xFF252924),
    outline = Color(0xFF747A6F),
    outlineVariant = Color(0xFF3A3F37),
    error = Color(0xFFF2B8B5),
)

/** Status colours used by the connect button and badges. */
@Immutable
data class MarzStatusColors(val connected: Color, val onConnected: Color, val idle: Color, val onIdle: Color)

val LocalStatusColors = staticCompositionLocalOf {
    MarzStatusColors(connected = Lime, onConnected = Ink, idle = Green, onIdle = Color.White)
}

private val MarzTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    )
}

private val MarzShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val PriceStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun MarzTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    val status = if (dark) {
        MarzStatusColors(connected = Lime, onConnected = Ink, idle = Color(0xFF3F5E3B), onIdle = Color.White)
    } else {
        MarzStatusColors(connected = Lime, onConnected = Ink, idle = Green, onIdle = Color.White)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = colors, typography = MarzTypography, shapes = MarzShapes, content = content)
    }
}
