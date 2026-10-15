package com.elevman.ui.theme

/**
 * The colour tokens of the app, as 0xAARRGGBB values.
 *
 * They are plain numbers, not Compose colours, so a JVM test can check their contrast without
 * Compose (see `ContrastTest`). `Theme.kt` turns them into the Material 3 colour schemes, and it
 * is the only place that does.
 *
 * Every text colour reaches a contrast ratio of at least 7:1 (WCAG AAA) on the colour it is drawn
 * on, in both schemes, so the screens stay readable in a dim machine room and in direct sun.
 * Change a value and `ContrastTest` tells you if it no longer holds.
 */
internal object Palette {

    // Light scheme: near-black text on white.
    const val LightBackground = 0xFFFFFFFFL
    const val LightOnBackground = 0xFF121212L
    const val LightSurface = 0xFFFFFFFFL
    const val LightOnSurface = 0xFF121212L
    const val LightSurfaceVariant = 0xFFE3E7ECL
    const val LightOnSurfaceVariant = 0xFF2B3036L
    const val LightSurfaceDim = 0xFFD9DEE3L
    const val LightSurfaceBright = 0xFFFFFFFFL
    const val LightSurfaceContainerLowest = 0xFFFFFFFFL
    const val LightSurfaceContainerLow = 0xFFF6F8FAL
    const val LightSurfaceContainer = 0xFFEEF1F4L
    const val LightSurfaceContainerHigh = 0xFFE8ECF0L
    const val LightSurfaceContainerHighest = 0xFFE3E7ECL
    const val LightOutline = 0xFF5B6168L
    const val LightOutlineVariant = 0xFFB8BEC5L
    const val LightPrimary = 0xFF0B3D91L
    const val LightOnPrimary = 0xFFFFFFFFL
    const val LightPrimaryContainer = 0xFFD6E3FFL
    const val LightOnPrimaryContainer = 0xFF001A41L
    const val LightSecondary = 0xFF2F3B4DL
    const val LightOnSecondary = 0xFFFFFFFFL
    const val LightSecondaryContainer = 0xFFCAD9F5L
    const val LightOnSecondaryContainer = 0xFF0A1B38L
    const val LightTertiary = 0xFF5B2C00L
    const val LightOnTertiary = 0xFFFFFFFFL
    const val LightTertiaryContainer = 0xFFFFDCC2L
    const val LightOnTertiaryContainer = 0xFF2A1200L
    const val LightError = 0xFF8C0009L
    const val LightOnError = 0xFFFFFFFFL
    const val LightErrorContainer = 0xFFFFDAD6L
    const val LightOnErrorContainer = 0xFF410002L
    const val LightInverseSurface = 0xFF20252BL
    const val LightInverseOnSurface = 0xFFF5F7FAL
    const val LightInversePrimary = 0xFFA9C7FFL
    const val LightScrim = 0xFF000000L

    // Dark scheme: near-white text on near-black.
    const val DarkBackground = 0xFF0E1114L
    const val DarkOnBackground = 0xFFF5F7FAL
    const val DarkSurface = 0xFF0E1114L
    const val DarkOnSurface = 0xFFF5F7FAL
    const val DarkSurfaceVariant = 0xFF2A3037L
    const val DarkOnSurfaceVariant = 0xFFD5DAE0L
    const val DarkSurfaceDim = 0xFF0E1114L
    const val DarkSurfaceBright = 0xFF343A41L
    const val DarkSurfaceContainerLowest = 0xFF080A0DL
    const val DarkSurfaceContainerLow = 0xFF151A1EL
    const val DarkSurfaceContainer = 0xFF1A1E23L
    const val DarkSurfaceContainerHigh = 0xFF252A30L
    const val DarkSurfaceContainerHighest = 0xFF30363DL
    const val DarkOutline = 0xFF9AA1A9L
    const val DarkOutlineVariant = 0xFF4A5159L
    const val DarkPrimary = 0xFFA9C7FFL
    const val DarkOnPrimary = 0xFF00214FL
    const val DarkPrimaryContainer = 0xFF1B3A73L
    const val DarkOnPrimaryContainer = 0xFFDCE6FFL
    const val DarkSecondary = 0xFFB9CBEAL
    const val DarkOnSecondary = 0xFF0E2140L
    const val DarkSecondaryContainer = 0xFF2D4670L
    const val DarkOnSecondaryContainer = 0xFFE3ECFFL
    const val DarkTertiary = 0xFFFFB784L
    const val DarkOnTertiary = 0xFF3A1B00L
    const val DarkTertiaryContainer = 0xFF6B3400L
    const val DarkOnTertiaryContainer = 0xFFFFE6D2L
    const val DarkError = 0xFFFFB4ABL
    const val DarkOnError = 0xFF540003L
    const val DarkErrorContainer = 0xFF93000AL
    const val DarkOnErrorContainer = 0xFFFFDAD6L
    const val DarkInverseSurface = 0xFFE3E7ECL
    const val DarkInverseOnSurface = 0xFF20252BL
    const val DarkInversePrimary = 0xFF0B3D91L
    const val DarkScrim = 0xFF000000L
}
