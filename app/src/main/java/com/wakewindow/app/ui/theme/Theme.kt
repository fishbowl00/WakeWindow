package com.wakewindow.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.wakewindow.app.domain.settings.AppearanceMode

/**
 * Nautical palette (see docs/PRODUCT.md). Both color schemes are hand-authored rather than
 * derived from Material dynamic color: a NO_GO/warning card must read the same regardless of
 * the device wallpaper, which dynamic color would otherwise re-tint (the same reasoning
 * RideCast documents for its own severity colors - see docs/RIDECAST_REFERENCE_AUDIT.md).
 */
object WakeWindowColors {
    val DeepNavy = Color(0xFF071A2B)
    val OceanNavy = Color(0xFF0B2F4A)
    val DeepWater = Color(0xFF0E4F6E)
    val SeaTeal = Color(0xFF159A9C)
    val NavigationCyan = Color(0xFF35BFEF)
    val Seafoam = Color(0xFF8EDDD5)
    val Foam = Color(0xFFEFF8FA)
    val SignalAmber = Color(0xFFF2A93B)
    val MarineWarningCoral = Color(0xFFE75B52)
    val DarkSurface = Color(0xFF0B1722)

    // Darker variants used only where a brand color sits on a light background and needs
    // more contrast than its raw hex gives - the same calibration lesson RideCast's own
    // Theme.kt documents finding the hard way.
    val DeepWaterOnLight = Color(0xFF0A3D57)
    val CoralOnLight = Color(0xFFB13228)
}

/**
 * Fixed, non-theme-tinted colors for [com.wakewindow.app.domain.scoring.BoatingCategory].
 * These never derive from the active ColorScheme - a CAUTION chip must look the same in
 * light and dark mode, the same way RideCast's ride/don't-ride colors do.
 */
object CategoryColors {
    val Excellent = Color(0xFF3FA66B)
    val Good = WakeWindowColors.SeaTeal
    val Caution = WakeWindowColors.SignalAmber
    val Poor = Color(0xFFD9722E)
    val NoGo = WakeWindowColors.MarineWarningCoral
    val Unavailable = Color(0xFF8A97A3)
}

private val DarkColorScheme = darkColorScheme(
    primary = WakeWindowColors.NavigationCyan,
    onPrimary = WakeWindowColors.DeepNavy,
    primaryContainer = WakeWindowColors.DeepWater,
    onPrimaryContainer = WakeWindowColors.Foam,
    secondary = WakeWindowColors.Seafoam,
    onSecondary = WakeWindowColors.DeepNavy,
    secondaryContainer = WakeWindowColors.OceanNavy,
    onSecondaryContainer = WakeWindowColors.Seafoam,
    tertiary = WakeWindowColors.SignalAmber,
    onTertiary = WakeWindowColors.DeepNavy,
    tertiaryContainer = Color(0xFF4A3110),
    onTertiaryContainer = WakeWindowColors.SignalAmber,
    background = WakeWindowColors.DarkSurface,
    onBackground = WakeWindowColors.Foam,
    surface = WakeWindowColors.OceanNavy,
    onSurface = WakeWindowColors.Foam,
    surfaceVariant = WakeWindowColors.DeepWater,
    onSurfaceVariant = WakeWindowColors.Seafoam,
    outline = WakeWindowColors.DeepWater,
    error = WakeWindowColors.MarineWarningCoral,
    onError = WakeWindowColors.DeepNavy,
    errorContainer = Color(0xFF5C221D),
    onErrorContainer = WakeWindowColors.MarineWarningCoral,
)

private val LightColorScheme = lightColorScheme(
    primary = WakeWindowColors.DeepWaterOnLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE9F2),
    onPrimaryContainer = WakeWindowColors.DeepWaterOnLight,
    secondary = WakeWindowColors.SeaTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7EFEC),
    onSecondaryContainer = WakeWindowColors.OceanNavy,
    tertiary = Color(0xFF8A5A16),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE3C2),
    onTertiaryContainer = Color(0xFF6B4310),
    background = WakeWindowColors.Foam,
    onBackground = WakeWindowColors.DeepNavy,
    surface = Color.White,
    onSurface = WakeWindowColors.DeepNavy,
    surfaceVariant = Color(0xFFDCEAEF),
    onSurfaceVariant = WakeWindowColors.OceanNavy,
    outline = Color(0xFF6B8494),
    error = WakeWindowColors.CoralOnLight,
    onError = Color.White,
    errorContainer = Color(0xFFF8D5D1),
    onErrorContainer = WakeWindowColors.CoralOnLight,
)

@Composable
fun AppearanceMode.resolveDarkTheme(): Boolean = when (this) {
    AppearanceMode.LIGHT -> false
    AppearanceMode.DARK -> true
    AppearanceMode.SYSTEM -> isSystemInDarkTheme()
}

val LocalWakeWindowDarkTheme = compositionLocalOf { false }

fun TextStyle.tabularNumerals(): TextStyle =
    copy(fontFeatureSettings = "tnum")

val WakeWindowTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    )
}

@Composable
fun WakeWindowTheme(
    appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = appearanceMode.resolveDarkTheme()
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalWakeWindowDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WakeWindowTypography,
            content = content,
        )
    }
}
