package com.elevman.ui.theme

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text of the app has to be readable in a dim machine room and in bright sun, so every text
 * colour must reach a WCAG contrast ratio of at least 7:1 (the AAA level) on the colour behind it.
 *
 * Plain JVM test: it reads the palette tokens and does the arithmetic itself, no Compose needed.
 */
class ContrastTest {

    private class TextPair(val name: String, val text: Long, val background: Long)

    private val lightRequired = listOf(
        TextPair("light onSurface on surface", Palette.LightOnSurface, Palette.LightSurface),
        TextPair("light onBackground on background", Palette.LightOnBackground, Palette.LightBackground),
    )

    private val darkRequired = listOf(
        TextPair("dark onSurface on surface", Palette.DarkOnSurface, Palette.DarkSurface),
        TextPair("dark onBackground on background", Palette.DarkOnBackground, Palette.DarkBackground),
    )

    // Every other pairing where the shell puts text on a coloured background.
    private val lightShell = listOf(
        TextPair("light onSurface on surfaceContainer", Palette.LightOnSurface, Palette.LightSurfaceContainer),
        TextPair("light onSurfaceVariant on surfaceContainer", Palette.LightOnSurfaceVariant, Palette.LightSurfaceContainer),
        TextPair("light onSurfaceVariant on surfaceVariant", Palette.LightOnSurfaceVariant, Palette.LightSurfaceVariant),
        TextPair("light onPrimary on primary", Palette.LightOnPrimary, Palette.LightPrimary),
        TextPair("light onPrimaryContainer on primaryContainer", Palette.LightOnPrimaryContainer, Palette.LightPrimaryContainer),
        TextPair("light onSecondary on secondary", Palette.LightOnSecondary, Palette.LightSecondary),
        TextPair("light onSecondaryContainer on secondaryContainer", Palette.LightOnSecondaryContainer, Palette.LightSecondaryContainer),
        TextPair("light onTertiary on tertiary", Palette.LightOnTertiary, Palette.LightTertiary),
        TextPair("light onTertiaryContainer on tertiaryContainer", Palette.LightOnTertiaryContainer, Palette.LightTertiaryContainer),
        TextPair("light onError on error", Palette.LightOnError, Palette.LightError),
        TextPair("light onErrorContainer on errorContainer", Palette.LightOnErrorContainer, Palette.LightErrorContainer),
        TextPair("light inverseOnSurface on inverseSurface", Palette.LightInverseOnSurface, Palette.LightInverseSurface),
    )

    private val darkShell = listOf(
        TextPair("dark onSurface on surfaceContainer", Palette.DarkOnSurface, Palette.DarkSurfaceContainer),
        TextPair("dark onSurfaceVariant on surfaceContainer", Palette.DarkOnSurfaceVariant, Palette.DarkSurfaceContainer),
        TextPair("dark onSurfaceVariant on surfaceVariant", Palette.DarkOnSurfaceVariant, Palette.DarkSurfaceVariant),
        TextPair("dark onPrimary on primary", Palette.DarkOnPrimary, Palette.DarkPrimary),
        TextPair("dark onPrimaryContainer on primaryContainer", Palette.DarkOnPrimaryContainer, Palette.DarkPrimaryContainer),
        TextPair("dark onSecondary on secondary", Palette.DarkOnSecondary, Palette.DarkSecondary),
        TextPair("dark onSecondaryContainer on secondaryContainer", Palette.DarkOnSecondaryContainer, Palette.DarkSecondaryContainer),
        TextPair("dark onTertiary on tertiary", Palette.DarkOnTertiary, Palette.DarkTertiary),
        TextPair("dark onTertiaryContainer on tertiaryContainer", Palette.DarkOnTertiaryContainer, Palette.DarkTertiaryContainer),
        TextPair("dark onError on error", Palette.DarkOnError, Palette.DarkError),
        TextPair("dark onErrorContainer on errorContainer", Palette.DarkOnErrorContainer, Palette.DarkErrorContainer),
        TextPair("dark inverseOnSurface on inverseSurface", Palette.DarkInverseOnSurface, Palette.DarkInverseSurface),
    )

    @Test
    fun onSurfaceOnSurfaceReachesSevenToOneInBothSchemes() {
        assertAllReachSevenToOne(listOf(lightRequired[0], darkRequired[0]))
    }

    @Test
    fun onBackgroundOnBackgroundReachesSevenToOneInBothSchemes() {
        assertAllReachSevenToOne(listOf(lightRequired[1], darkRequired[1]))
    }

    @Test
    fun everyTextPairTheShellUsesReachesSevenToOne() {
        assertAllReachSevenToOne(lightShell + darkShell)
    }

    @Test
    fun contrastRatioMatchesTheWcagReferenceValues() {
        // The WCAG definition: black on white is 21:1, a colour on itself is 1:1.
        assertEquals(21.0, contrastRatio(0xFF000000, 0xFFFFFFFF), 0.001)
        assertEquals(21.0, contrastRatio(0xFFFFFFFF, 0xFF000000), 0.001)
        assertEquals(1.0, contrastRatio(0xFF777777, 0xFF777777), 0.001)
        // A widely quoted value: #777777 on white is 4.48:1, just short of AA.
        assertEquals(4.48, contrastRatio(0xFF777777, 0xFFFFFFFF), 0.01)
    }

    private fun assertAllReachSevenToOne(pairs: List<TextPair>) {
        for (pair in pairs) {
            val ratio = contrastRatio(pair.text, pair.background)
            assertTrue("${pair.name} is ${"%.2f".format(ratio)}:1, below 7:1", ratio >= 7.0)
        }
    }

    // Colours are 0xAARRGGBB longs; the alpha byte is not part of the ratio.
    private fun contrastRatio(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun relativeLuminance(argb: Long): Double {
        val r = linear(((argb shr 16) and 0xFF).toInt())
        val g = linear(((argb shr 8) and 0xFF).toInt())
        val b = linear((argb and 0xFF).toInt())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
