package com.kitchenreceipts.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's look: pitch black, phthalo green for everything you press (with a lighter green border), dark orange for
 * annotations (hints, section labels, values to check). Always dark: it is used in a kitchen at all hours, and black
 * also saves battery on an OLED phone.
 */
object Palette {
    val Black = Color(0xFF000000)
    /** Panels on the black background: just lighter, with a hairline border. */
    val Panel = Color(0xFF0C0F0E)
    val PanelRaised = Color(0xFF121615)
    val Hairline = Color(0xFF1F2725)
    val HairlineStrong = Color(0xFF2C3734)

    /** Phthalo green (the pigment, PG7) for buttons; a lighter tint of the same hue for borders, icons and links. */
    val Phthalo = Color(0xFF123524)
    val PhthaloPressed = Color(0xFF0B2418)
    val PhthaloBorder = Color(0xFF2F8F62)
    val PhthaloBright = Color(0xFF4CC48C)
    val OnPhthalo = Color(0xFFE3F5EB)

    /** Dark orange for annotations. */
    val Orange = Color(0xFFFF8C00)
    val OrangeDim = Color(0xFFC96F00)
    val OrangeContainer = Color(0xFF1E1102)
    val OnOrangeContainer = Color(0xFFFFC37A)

    val Text = Color(0xFFF0F2F1)
    val TextDim = Color(0xFF98A39F)
    val Error = Color(0xFFFF5A4E)
    val Ok = Color(0xFF4CC48C)
}

private val Colors = darkColorScheme(
    primary = Palette.PhthaloBright,
    onPrimary = Palette.Black,
    primaryContainer = Palette.Phthalo,
    onPrimaryContainer = Palette.OnPhthalo,
    secondary = Palette.PhthaloBright,
    onSecondary = Palette.Black,
    secondaryContainer = Palette.Phthalo,
    onSecondaryContainer = Palette.OnPhthalo,
    tertiary = Palette.Orange,
    onTertiary = Palette.Black,
    tertiaryContainer = Palette.OrangeContainer,
    onTertiaryContainer = Palette.OnOrangeContainer,
    background = Palette.Black,
    onBackground = Palette.Text,
    surface = Palette.Black,
    onSurface = Palette.Text,
    surfaceVariant = Palette.PanelRaised,
    onSurfaceVariant = Palette.TextDim,
    surfaceTint = Palette.Black,
    surfaceBright = Palette.PanelRaised,
    surfaceDim = Palette.Black,
    surfaceContainerLowest = Palette.Black,
    surfaceContainerLow = Palette.Panel,
    surfaceContainer = Palette.Panel,
    surfaceContainerHigh = Palette.PanelRaised,
    surfaceContainerHighest = Palette.Panel,
    inverseSurface = Palette.Text,
    inverseOnSurface = Palette.Black,
    inversePrimary = Palette.Phthalo,
    outline = Palette.HairlineStrong,
    outlineVariant = Palette.Hairline,
    error = Palette.Error,
    onError = Palette.Black,
    errorContainer = Color(0xFF2A0906),
    onErrorContainer = Color(0xFFFFB4AB),
    scrim = Palette.Black,
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

private val Status = StatusColors(
    missingContainer = Color(0xFF171006),
    onMissing = Color(0xFFE9B26A),
    uncertainBorder = Palette.Orange,
    uncertainContainer = Palette.OrangeContainer,
    onUncertain = Palette.OnOrangeContainer,
    ok = Palette.Ok,
)

val LocalStatusColors = staticCompositionLocalOf { Status }

private val Base = Typography()

// Larger body text (read at arm's length in a kitchen), firmer titles, spaced-out small labels.
private val AppTypography = Base.copy(
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, letterSpacing = (-0.1).sp),
    titleMedium = Base.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = Base.bodySmall.copy(fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = Base.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    labelMedium = Base.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun KitchenReceiptsTheme(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides Status) {
        MaterialTheme(colorScheme = Colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
