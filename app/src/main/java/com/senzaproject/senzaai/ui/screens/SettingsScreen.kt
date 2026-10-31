package com.senzaproject.senzaai.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.senzaproject.senzaai.BuildConfig
import com.senzaproject.senzaai.di.ServiceLocator
import com.senzaproject.senzaai.ui.components.*
import com.senzaproject.senzaai.utils.Saf
import com.senzaproject.senzaai.utils.formatBytes
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.senzaproject.senzaai.i18n.Lang

@Composable
fun SettingsScreen(s: ServiceLocator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { File(ctx.cacheDir, "analysis").walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }

    var showLang by remember { mutableStateOf(false) }

    ScreenScaffold("Settings", "Tampilan dan privasi") {
        GlassCard {
            Heading("Bahasa")
            Caption("Pilih bahasa tampilan aplikasi.")
            GlassButton(Lang.displayName(), { showLang = true }, Modifier.fillMaxWidth())
        }
        if (showLang) LanguageDialog { showLang = false }

        GlassCard {
            Heading("Tampilan")
            Caption("Tema")
            ChipRow(listOf("Ikuti sistem", "Terang", "Gelap"), s.uiPrefs.themeMode) { s.uiPrefs.setThemeMode(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Body("Kurangi efek visual")
                    Caption("Mematikan animasi latar untuk hemat baterai dan keterbacaan.")
                }
                Chip(if (s.uiPrefs.reducedEffects) "Aktif" else "Nonaktif", s.uiPrefs.reducedEffects) { s.uiPrefs.setReducedEffects(!s.uiPrefs.reducedEffects) }
            }
        }

        GlassCard {
            Heading("Privasi & data")
            Body("• Semua analisis berjalan di perangkat.\n• Tidak ada telemetri, iklan, atau akun.\n• Kredensial jaringan (SSID/BSSID/sandi) tidak dikumpulkan.\n• Backup otomatis Android dinonaktifkan untuk aplikasi ini.\n• Satu-satunya lalu lintas jaringan keluar: tes diagnostik jaringan yang kamu jalankan.", dim = true)
            KeyValue("Cache analisis", cacheSize.formatBytes())
            GlassButton("Bersihkan cache", {
                Saf.clearCache(ctx)
                cacheSize = 0L
            })
        }

        GlassCard {
            Heading("Tentang")
            KeyValue("Aplikasi", "Senza Tools")
            KeyValue("Versi", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            KeyValue("Pengembang", "SenzaProject")
            KeyValue("Perangkat", "${Build.MANUFACTURER} ${Build.MODEL}")
            KeyValue("Android", "${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
            Caption("Intelligent Android. Smarter System Control.")
        }
    }
}
