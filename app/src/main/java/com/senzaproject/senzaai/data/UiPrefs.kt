package com.senzaproject.senzaai.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.senzaproject.senzaai.domain.model.DiagnosticReport

/** Preferensi tampilan (0 = ikuti sistem, 1 = terang, 2 = gelap). */
class UiPrefs(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("senza_ui", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(prefs.getInt("theme", 0))
        private set
    var reducedEffects by mutableStateOf(prefs.getBoolean("reduced", false))
        private set

    @JvmName("applyThemeMode")
    fun setThemeMode(v: Int) {
        themeMode = v
        prefs.edit().putInt("theme", v).apply()
    }

    @JvmName("applyReducedEffects")
    fun setReducedEffects(v: Boolean) {
        reducedEffects = v
        prefs.edit().putBoolean("reduced", v).apply()
    }
}

/** Ringkasan analisis terakhir yang dapat (atas pilihan pengguna) dilampirkan ke layar lain. */
class SessionContext {
    var lastPropSummary by mutableStateOf<String?>(null)
    var lastApkSummary by mutableStateOf<String?>(null)
    var lastRomSummary by mutableStateOf<String?>(null)
    var lastLog by mutableStateOf<String?>(null)
    var lastDiagnostic by mutableStateOf<DiagnosticReport?>(null)
}
