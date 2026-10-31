package com.senzaproject.senzaai.ui.theme

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
    bgTop = Color(0xFF070A1C),
    bgBottom = Color(0xFF0C1230),
    blob1 = Color(0xFF5B6CFF),
    blob2 = Color(0xFF22D3EE),
    blob3 = Color(0xFFB45CFF),
    glassFill = Color(0x1AFFFFFF),
    glassFillStrong = Color(0x2EFFFFFF),
    borderHi = Color(0x80FFFFFF),
    borderLo = Color(0x1AFFFFFF),
    text = Color(0xFFF4F6FF),
    textDim = Color(0xFFAEB6D6),
    accent = Color(0xFF7C8CFF),
    accent2 = Color(0xFF3BD9F0),
    good = Color(0xFF4ADE80),
    warn = Color(0xFFFBBF24),
    bad = Color(0xFFFF6B7A)
)

val LightPalette = GlassPalette(
    dark = false,
    bgTop = Color(0xFFE8EEFF),
    bgBottom = Color(0xFFF6F0FF),
    blob1 = Color(0xFF8FA0FF),
    blob2 = Color(0xFF7FE3F5),
    blob3 = Color(0xFFD6A8FF),
    glassFill = Color(0x8CFFFFFF),
    glassFillStrong = Color(0xB8FFFFFF),
    borderHi = Color(0xFFFFFFFF),
    borderLo = Color(0x66FFFFFF),
    text = Color(0xFF0E1330),
    textDim = Color(0xFF4A5278),
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
