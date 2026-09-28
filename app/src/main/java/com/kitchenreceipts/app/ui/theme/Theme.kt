package com.kitchenreceipts.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF1E5B4F)
private val GreenLight = Color(0xFF8FD3C1)
private val Amber = Color(0xFFE0A526)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEDE4),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635C),
    tertiary = Amber,
    background = Color(0xFFF8FAF8),
    surface = Color(0xFFF8FAF8),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF0E4A3E),
    onPrimaryContainer = Color(0xFFCDEDE4),
    secondary = Color(0xFFB1CCC3),
    tertiary = Amber,
)

/** Colours for the review states: missing and uncertain must stand out during a busy shift. */
@Immutable
data class StatusColors(
    val missingContainer: Color,
    val onMissing: Color,
    val uncertainBorder: Color,
    val uncertainContainer: Color,
    val onUncertain: Color,
    val ok: Color,
)

private val LightStatus = StatusColors(
    missingContainer = Color(0xFFFFF1CC), onMissing = Color(0xFF5C4300),
    uncertainBorder = Color(0xFFE06C00), uncertainContainer = Color(0xFFFFE3CC), onUncertain = Color(0xFF5A2600),
    ok = Color(0xFF1B7F3B),
)
private val DarkStatus = StatusColors(
    missingContainer = Color(0xFF4A3A10), onMissing = Color(0xFFFFE3A0),
    uncertainBorder = Color(0xFFFFA25C), uncertainContainer = Color(0xFF4F2A0C), onUncertain = Color(0xFFFFD2B0),
    ok = Color(0xFF7ED99A),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val BaseTypography = Typography()

// Slightly larger body text: read at arm's length in a kitchen.
private val AppTypography = BaseTypography.copy(
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontSize = 16.sp),
    titleMedium = BaseTypography.titleMedium.copy(fontSize = 18.sp),
)

@Composable
fun KitchenReceiptsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}
