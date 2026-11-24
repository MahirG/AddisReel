package et.addisreel.app

import androidx.compose.ui.graphics.Color

/** WCAG 2.x relative luminance math; pure Kotlin, usable in JVM unit tests. */
internal object AddisContrast {
    private fun linear(c: Float): Double {
        val x = c.toDouble().coerceIn(0.0, 1.0)
        return if (x <= 0.04045) x / 12.92
        else Math.pow((x + 0.055) / 1.055, 2.4)
    }

    fun relativeLuminance(color: Color): Double =
        0.2126 * linear(color.red) +
        0.7152 * linear(color.green) +
        0.0722 * linear(color.blue)

    fun ratio(a: Color, b: Color): Double {
        val max = maxOf(relativeLuminance(a), relativeLuminance(b))
        val min = minOf(relativeLuminance(a), relativeLuminance(b))
        return (max + 0.05) / (min + 0.05)
    }

    fun normalText(foreground: Color, background: Color): Boolean =
        ratio(foreground, background) >= 4.5

    fun largeText(foreground: Color, background: Color): Boolean =
        ratio(foreground, background) >= 3.0
}
