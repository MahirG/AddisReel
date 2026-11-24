package et.addisreel.app

import android.app.Activity
import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Source of truth for the native Android visual identity.
 * The amber/near-black brand is retained, with a separately tuned light theme.
 * System sans-serif uses the platform's Ethiopic fallback; no fonts are bundled.
 */
internal data class AddisPalette(
    val isDark: Boolean,
    val background: Color, val surface: Color, val elevated: Color,
    val navigation: Color, val selected: Color,
    val textPrimary: Color, val textSecondary: Color, val textMuted: Color,
    val accent: Color, val onAccent: Color, val border: Color,
    val posterPlaceholder: Color, val playerBackground: Color,
    val success: Color, val warning: Color, val error: Color, val info: Color,
    val scrim: Color
)

internal object AddisPalettes {
    val Dark = AddisPalette(
        isDark = true,
        background = Color(0xFF0C0C10), surface = Color(0xFF191920),
        elevated = Color(0xFF24242D), navigation = Color(0xFF121217),
        selected = Color(0xFF3C3022),
        textPrimary = Color(0xFFF6F4F9), textSecondary = Color(0xFFC5C3CF),
        textMuted = Color(0xFFA8A7B8), accent = Color(0xFFFFD17A),
        onAccent = Color(0xFF241B0B), border = Color(0xFF656474),
        posterPlaceholder = Color(0xFF302536), playerBackground = Color(0xFF08080C),
        success = Color(0xFF81D8AA), warning = Color(0xFFFFCF77),
        error = Color(0xFFFFB4AB), info = Color(0xFFABC9FF),
        scrim = Color(0xCC08080C)
    )
    val Light = AddisPalette(
        isDark = false,
        background = Color(0xFFF8F7F4), surface = Color(0xFFFFFFFF),
        elevated = Color(0xFFF0EFF2), navigation = Color(0xFFF3F0EB),
        selected = Color(0xFFF9EDDB),
        textPrimary = Color(0xFF201D25), textSecondary = Color(0xFF54515D),
        textMuted = Color(0xFF686472), accent = Color(0xFF775000),
        onAccent = Color(0xFFFFFFFF), border = Color(0xFF8D8993),
        posterPlaceholder = Color(0xFFE9DFE9), playerBackground = Color(0xFF08080C),
        success = Color(0xFF13653D), warning = Color(0xFF805400),
        error = Color(0xFFB3261E), info = Color(0xFF245EAD),
        scrim = Color(0x8808080C)
    )
}

internal val LocalAddisPalette = staticCompositionLocalOf { AddisPalettes.Dark }

/** Semantic color roles, never color literals inside reusable components. */
internal object AddisColors {
    val current: AddisPalette
        @Composable get() = LocalAddisPalette.current
    val accent: Color
        @Composable get() = current.accent
    val background: Color
        @Composable get() = current.background
    val surface: Color
        @Composable get() = current.surface
    val elevated: Color
        @Composable get() = current.elevated
    val navigation: Color
        @Composable get() = current.navigation
    val selected: Color
        @Composable get() = current.selected
    val textPrimary: Color
        @Composable get() = current.textPrimary
    val textSecondary: Color
        @Composable get() = current.textSecondary
    val textMuted: Color
        @Composable get() = current.textMuted
    val border: Color
        @Composable get() = current.border
    val posterPlaceholder: Color
        @Composable get() = current.posterPlaceholder
    val success: Color
        @Composable get() = current.success
    val warning: Color
        @Composable get() = current.warning
    val error: Color
        @Composable get() = current.error
    val info: Color
        @Composable get() = current.info
}

internal object AddisSpacing {
    val micro: Dp = 4.dp
    val small: Dp = 8.dp
    val compact: Dp = 12.dp
    val standard: Dp = 16.dp
    val comfortable: Dp = 20.dp
    val section: Dp = 24.dp
    val large: Dp = 32.dp
    val hero: Dp = 48.dp
    val touch: Dp = 48.dp
}

internal object AddisRadius {
    val small: Dp = 8.dp
    val medium: Dp = 12.dp
    val large: Dp = 18.dp
    val card: Dp = 20.dp
    val sheet: Dp = 24.dp
}

internal object AddisIcons {
    val compact: Dp = 18.dp
    val standard: Dp = 24.dp
    val feature: Dp = 32.dp
    val empty: Dp = 48.dp
}

/** Exactly one non-bundled font family, allowing script-specific OS fallback. */
internal object AddisType {
    private val family = FontFamily.SansSerif
    val display = TextStyle(
        fontFamily = family, fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp
    )
    val headline = TextStyle(
        fontFamily = family, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.35).sp
    )
    val title = TextStyle(
        fontFamily = family, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp
    )
    val subtitle = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Medium,
        fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.sp
    )
    val body = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp
    )
    val description = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp
    )
    val action = TextStyle(
        fontFamily = family, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    )
    val nav = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp
    )
    val metadata = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp
    )
    val caption = TextStyle(
        fontFamily = family, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp
    )
    val statistics = TextStyle(
        fontFamily = family, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 25.sp, letterSpacing = 0.sp
    )
    val error = description.copy(fontWeight = FontWeight.Medium)
    val success = description.copy(fontWeight = FontWeight.Medium)

    val material = Typography(
        displayLarge = display, displayMedium = display.copy(fontSize=30.sp),
        displaySmall = display.copy(fontSize=28.sp),
        headlineLarge = headline, headlineMedium = title.copy(fontSize=22.sp),
        headlineSmall = title,
        titleLarge = title, titleMedium = subtitle, titleSmall = action,
        bodyLarge = body, bodyMedium = description,
        bodySmall = caption, labelLarge = action, labelMedium = metadata,
        labelSmall = caption
    )
}

/** Ethiopic occupies more vertical space than Latin. Never add letter spacing
 * to Amharic glyph clusters; Oromo/English keep the standard Latin metrics.
 */
internal object AddisScript {
    fun hasEthiopic(text: String): Boolean = text.any {
        it.code in 0x1200..0x139F || it.code in 0x2D80..0x2DDF ||
            it.code in 0xAB00..0xAB2F
    }
    fun lineHeightSp(base: Float, text: String): Float =
        if (hasEthiopic(text)) base * 1.16f else base
    fun letterSpacingSp(base: Float, text: String): Float =
        if (hasEthiopic(text)) 0f else base

    @Composable
    fun style(text: String, base: TextStyle = MaterialTheme.typography.bodyLarge): TextStyle {
        val line = if (base.lineHeight == TextUnit.Unspecified) 24f else base.lineHeight.value
        val spacing = if (base.letterSpacing == TextUnit.Unspecified) 0f else base.letterSpacing.value
        return base.copy(
            lineHeight = lineHeightSp(line, text).sp,
            letterSpacing = letterSpacingSp(spacing, text).sp
        )
    }
}

internal object AddisMotion {
    const val quickMs = 140
    const val standardMs = 220
    const val slowMs = 300

    fun duration(defaultMs: Int, reduced: Boolean): Int = if (reduced) 0 else defaultMs
    @Composable
    fun isReduced(): Boolean {
        val context = LocalContext.current
        return remember(context) {
            Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()
        }
    }
}

internal val AddisShapes = Shapes(
    extraSmall = RoundedCornerShape(AddisRadius.small),
    small = RoundedCornerShape(AddisRadius.medium),
    medium = RoundedCornerShape(AddisRadius.large),
    large = RoundedCornerShape(AddisRadius.card),
    extraLarge = RoundedCornerShape(AddisRadius.sheet)
)

internal data class AddisThemeSelection(val value: String, val change: (String) -> Unit)
internal val LocalAddisThemeSelection = staticCompositionLocalOf {
    AddisThemeSelection("System") {}
}

/** Applies to splash-adjacent dialogs, Watch, Home, Library, Premium and updater. */
@Composable
internal fun AddisReelTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val pref = remember(context) { context.getSharedPreferences("addisreel_appearance", 0) }
    var setting by remember { mutableStateOf(pref.getString("theme", "System") ?: "System") }
    val dark = when (setting) {
        "Light" -> false
        "Dark" -> true
        else -> isSystemInDarkTheme()
    }
    val p = if (dark) AddisPalettes.Dark else AddisPalettes.Light
    val scheme: ColorScheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent,
        primaryContainer = p.selected, onPrimaryContainer = p.accent,
        secondary = p.textSecondary, onSecondary = p.background,
        background = p.background, onBackground = p.textPrimary,
        surface = p.surface, onSurface = p.textPrimary,
        surfaceVariant = p.elevated, onSurfaceVariant = p.textSecondary,
        outline = p.border, outlineVariant = p.border,
        error = p.error, onError = p.background,
        surfaceTint = Color.Transparent
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent,
        primaryContainer = p.selected, onPrimaryContainer = p.accent,
        secondary = p.textSecondary, onSecondary = p.surface,
        background = p.background, onBackground = p.textPrimary,
        surface = p.surface, onSurface = p.textPrimary,
        surfaceVariant = p.elevated, onSurfaceVariant = p.textSecondary,
        outline = p.border, outlineVariant = p.border,
        error = p.error, onError = p.surface,
        surfaceTint = Color.Transparent
    )
    val updateSetting: (String) -> Unit = { selected ->
        if (selected in listOf("System","Light","Dark")) {
            setting = selected
            pref.edit().putString("theme", selected).apply()
        }
    }
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalAddisPalette provides p,
        LocalAddisThemeSelection provides AddisThemeSelection(setting, updateSetting)
    ) {
        MaterialTheme(colorScheme = scheme, typography = AddisType.material, shapes = AddisShapes) {
            content()
        }
    }
}
