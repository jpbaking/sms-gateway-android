package io.github.jpbaking.smsgateway.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colours sampled from the app icon: deep space navy, neon green, violet and cyan. */
private object Brand {
    val Navy = Color(0xFF010C27)
    val NavyRaised = Color(0xFF071A34)
    val NavyHigh = Color(0xFF132A4A)
    val Steel = Color(0xFF5479A8)
    val SteelLight = Color(0xFFA9B8D6)
    val Ink = Color(0xFFE3ECFF)
    val NeonGreen = Color(0xFF5CE07A)
    val GreenDeep = Color(0xFF0E5A2C)
    val Violet = Color(0xFFA45CF0)
    val VioletDeep = Color(0xFF3B1B66)
    val Cyan = Color(0xFF6EE8F0)
    val CyanDeep = Color(0xFF0B4F57)
}

private val GatewayColors = darkColorScheme(
    primary = Brand.NeonGreen,
    onPrimary = Color(0xFF00210D),
    primaryContainer = Brand.GreenDeep,
    onPrimaryContainer = Color(0xFFB8FFC6),
    secondary = Brand.Violet,
    onSecondary = Color.White,
    secondaryContainer = Brand.VioletDeep,
    onSecondaryContainer = Color(0xFFEBD9FF),
    tertiary = Brand.Cyan,
    onTertiary = Color(0xFF00363B),
    tertiaryContainer = Brand.CyanDeep,
    onTertiaryContainer = Color(0xFFC8FAFF),
    background = Brand.Navy,
    onBackground = Brand.Ink,
    surface = Brand.Navy,
    onSurface = Brand.Ink,
    surfaceVariant = Brand.NavyHigh,
    onSurfaceVariant = Brand.SteelLight,
    surfaceContainerLowest = Brand.Navy,
    surfaceContainerLow = Brand.NavyRaised,
    surfaceContainer = Brand.NavyRaised,
    surfaceContainerHigh = Brand.NavyHigh,
    surfaceContainerHighest = Brand.NavyHigh,
    outline = Brand.Steel,
    outlineVariant = Brand.NavyHigh,
    error = Color(0xFFFF6B8A),
    onError = Color(0xFF3D0014),
)

/** The app always uses its own dark, icon-matched palette, regardless of wallpaper or system theme. */
@Composable
fun GatewayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GatewayColors, content = content)
}
