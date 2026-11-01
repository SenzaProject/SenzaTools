package com.senzaproject.senzatools.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
    bgTop = Color(0xFF0B0E1A),
    bgBottom = Color(0xFF0E1224),
    blob1 = Color(0xFF4F5FE0),
    blob2 = Color(0xFF1FB6CC),
    blob3 = Color(0xFF7C4FE0),
    glassFill = Color(0x14FFFFFF),
    glassFillStrong = Color(0x1FFFFFFF),
    borderHi = Color(0x33FFFFFF),
    borderLo = Color(0x14FFFFFF),
    text = Color(0xFFF2F4FC),
    textDim = Color(0xFF9AA3C2),
    accent = Color(0xFF7C8CFF),
    accent2 = Color(0xFF5CC8E0),
    good = Color(0xFF4ADE80),
    warn = Color(0xFFFBBF24),
    bad = Color(0xFFFF6B7A)
)

val LightPalette = GlassPalette(
    dark = false,
    bgTop = Color(0xFFF3F5FC),
    bgBottom = Color(0xFFEEF1FA),
    blob1 = Color(0xFFB4BEFF),
    blob2 = Color(0xFFA6E6F2),
    blob3 = Color(0xFFD9C2FF),
    glassFill = Color(0xB3FFFFFF),
    glassFillStrong = Color(0xD9FFFFFF),
    borderHi = Color(0xFFFFFFFF),
    borderLo = Color(0x80FFFFFF),
    text = Color(0xFF111631),
    textDim = Color(0xFF59618A),
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
fun SenzaTheme(themeMode: Int, reducedEffects: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = Color.White, background = p.bgTop, surface = p.bgBottom,
        onBackground = p.text, onSurface = p.text, error = p.bad
    ) else lightColorScheme(
        primary = p.accent, onPrimary = Color.White, background = p.bgTop, surface = p.bgBottom,
        onBackground = p.text, onSurface = p.text, error = p.bad
    )
    CompositionLocalProvider(LocalGlass provides p, LocalReducedEffects provides reducedEffects) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
