package com.senzaproject.senzatools.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** Palet Liquid Glass. Teks selalu memiliki kontras tinggi terhadap permukaan kaca di mode terang maupun gelap. */
data class GlassPalette(
    val dark: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val blob1: Color,
    val blob2: Color,
    val blob3: Color,
    val glassFill: Color,
    val glassFillStrong: Color,
    val borderHi: Color,
    val borderLo: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val accent2: Color,
    val good: Color,
    val warn: Color,
    val bad: Color
)

val DarkPalette = GlassPalette(
    dark = true,
    bgTop = Color(0xFF0E1015),
    bgBottom = Color(0xFF0E1015),
    blob1 = Color(0xFF0E1015),
    blob2 = Color(0xFF0E1015),
    blob3 = Color(0xFF0E1015),
    glassFill = Color(0xFF171A22),
    glassFillStrong = Color(0xFF1F232D),
    borderHi = Color(0xFF2D323F),
    borderLo = Color(0xFF242835),
    text = Color(0xFFEEF0F6),
    textDim = Color(0xFF8D95A9),
    accent = Color(0xFF6C7BFF),
    accent2 = Color(0xFF4FB8D0),
    good = Color(0xFF4ADE80),
    warn = Color(0xFFFBBF24),
    bad = Color(0xFFFF6B7A)
)

val LightPalette = GlassPalette(
    dark = false,
    bgTop = Color(0xFFF3F4F8),
    bgBottom = Color(0xFFF3F4F8),
    blob1 = Color(0xFFF3F4F8),
    blob2 = Color(0xFFF3F4F8),
    blob3 = Color(0xFFF3F4F8),
    glassFill = Color(0xFFFFFFFF),
    glassFillStrong = Color(0xFFEBEDF4),
    borderHi = Color(0xFFD5D9E4),
    borderLo = Color(0xFFE3E6EE),
    text = Color(0xFF14182B),
    textDim = Color(0xFF5B627F),
    accent = Color(0xFF3F51F0),
    accent2 = Color(0xFF0891B2),
    good = Color(0xFF15803D),
    warn = Color(0xFFB45309),
    bad = Color(0xFFC81E3A)
)

val LocalGlass = staticCompositionLocalOf { DarkPalette }
val LocalReducedEffects = staticCompositionLocalOf { false }
/** [themeMode]: 0 = ikuti sistem, 1 = terang, 2 = gelap. */
@Composable
fun SenzaTheme(themeMode: Int, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = Color.White, background = p.bgTop, surface = p.glassFill,
        onBackground = p.text, onSurface = p.text, error = p.bad
    ) else lightColorScheme(
        primary = p.accent, onPrimary = Color.White, background = p.bgTop, surface = p.glassFill,
        onBackground = p.text, onSurface = p.text, error = p.bad
    )
    // Ukuran font sistem dibatasi maksimal 1.0x supaya tata letak tetap rapi dan konsisten.
    val d = LocalDensity.current
    val fixed = Density(d.density, fontScale = d.fontScale.coerceAtMost(1f))
    CompositionLocalProvider(LocalDensity provides fixed, LocalGlass provides p) {
        MaterialTheme(colorScheme = scheme) {
            // Material3 memberi jarak antar huruf (0.5sp) pada teks bawaan; dinolkan agar teks tidak renggang.
            ProvideTextStyle(TextStyle(letterSpacing = 0.sp), content)
        }
    }
}
