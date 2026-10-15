package com.elevman.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Every role is set from the palette, so no Material 3 default (a purple-tinted grey) leaks in
// where the palette has no say. Dynamic colour is not used: the contrast guarantee needs fixed colours.
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(Palette.LightPrimary),
    onPrimary = Color(Palette.LightOnPrimary),
    primaryContainer = Color(Palette.LightPrimaryContainer),
    onPrimaryContainer = Color(Palette.LightOnPrimaryContainer),
    inversePrimary = Color(Palette.LightInversePrimary),
    secondary = Color(Palette.LightSecondary),
    onSecondary = Color(Palette.LightOnSecondary),
    secondaryContainer = Color(Palette.LightSecondaryContainer),
    onSecondaryContainer = Color(Palette.LightOnSecondaryContainer),
    tertiary = Color(Palette.LightTertiary),
    onTertiary = Color(Palette.LightOnTertiary),
    tertiaryContainer = Color(Palette.LightTertiaryContainer),
    onTertiaryContainer = Color(Palette.LightOnTertiaryContainer),
    background = Color(Palette.LightBackground),
    onBackground = Color(Palette.LightOnBackground),
    surface = Color(Palette.LightSurface),
    onSurface = Color(Palette.LightOnSurface),
    surfaceVariant = Color(Palette.LightSurfaceVariant),
    onSurfaceVariant = Color(Palette.LightOnSurfaceVariant),
    surfaceTint = Color(Palette.LightPrimary),
    inverseSurface = Color(Palette.LightInverseSurface),
    inverseOnSurface = Color(Palette.LightInverseOnSurface),
    error = Color(Palette.LightError),
    onError = Color(Palette.LightOnError),
    errorContainer = Color(Palette.LightErrorContainer),
    onErrorContainer = Color(Palette.LightOnErrorContainer),
    outline = Color(Palette.LightOutline),
    outlineVariant = Color(Palette.LightOutlineVariant),
    scrim = Color(Palette.LightScrim),
    surfaceBright = Color(Palette.LightSurfaceBright),
    surfaceDim = Color(Palette.LightSurfaceDim),
    surfaceContainer = Color(Palette.LightSurfaceContainer),
    surfaceContainerHigh = Color(Palette.LightSurfaceContainerHigh),
    surfaceContainerHighest = Color(Palette.LightSurfaceContainerHighest),
    surfaceContainerLow = Color(Palette.LightSurfaceContainerLow),
    surfaceContainerLowest = Color(Palette.LightSurfaceContainerLowest),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(Palette.DarkPrimary),
    onPrimary = Color(Palette.DarkOnPrimary),
    primaryContainer = Color(Palette.DarkPrimaryContainer),
    onPrimaryContainer = Color(Palette.DarkOnPrimaryContainer),
    inversePrimary = Color(Palette.DarkInversePrimary),
    secondary = Color(Palette.DarkSecondary),
    onSecondary = Color(Palette.DarkOnSecondary),
    secondaryContainer = Color(Palette.DarkSecondaryContainer),
    onSecondaryContainer = Color(Palette.DarkOnSecondaryContainer),
    tertiary = Color(Palette.DarkTertiary),
    onTertiary = Color(Palette.DarkOnTertiary),
    tertiaryContainer = Color(Palette.DarkTertiaryContainer),
    onTertiaryContainer = Color(Palette.DarkOnTertiaryContainer),
    background = Color(Palette.DarkBackground),
    onBackground = Color(Palette.DarkOnBackground),
    surface = Color(Palette.DarkSurface),
    onSurface = Color(Palette.DarkOnSurface),
    surfaceVariant = Color(Palette.DarkSurfaceVariant),
    onSurfaceVariant = Color(Palette.DarkOnSurfaceVariant),
    surfaceTint = Color(Palette.DarkPrimary),
    inverseSurface = Color(Palette.DarkInverseSurface),
    inverseOnSurface = Color(Palette.DarkInverseOnSurface),
    error = Color(Palette.DarkError),
    onError = Color(Palette.DarkOnError),
    errorContainer = Color(Palette.DarkErrorContainer),
    onErrorContainer = Color(Palette.DarkOnErrorContainer),
    outline = Color(Palette.DarkOutline),
    outlineVariant = Color(Palette.DarkOutlineVariant),
    scrim = Color(Palette.DarkScrim),
    surfaceBright = Color(Palette.DarkSurfaceBright),
    surfaceDim = Color(Palette.DarkSurfaceDim),
    surfaceContainer = Color(Palette.DarkSurfaceContainer),
    surfaceContainerHigh = Color(Palette.DarkSurfaceContainerHigh),
    surfaceContainerHighest = Color(Palette.DarkSurfaceContainerHighest),
    surfaceContainerLow = Color(Palette.DarkSurfaceContainerLow),
    surfaceContainerLowest = Color(Palette.DarkSurfaceContainerLowest),
)

/**
 * The high-contrast Material 3 theme, light or dark by the system setting.
 *
 * Type uses the Material 3 defaults, which are in `sp`, so text follows the system font size.
 */
@Composable
fun ElevManTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
