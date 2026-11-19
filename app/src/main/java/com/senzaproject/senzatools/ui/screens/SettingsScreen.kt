package com.senzaproject.senzatools.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.BuildConfig
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.utils.Saf
import com.senzaproject.senzatools.utils.formatBytes
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.senzaproject.senzatools.i18n.Lang

@Composable
fun SettingsScreen(s: ServiceLocator, onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { File(ctx.cacheDir, "analysis").walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }

    var showLang by remember { mutableStateOf(false) }

    ScreenScaffold(
        "Settings", "Tampilan, privasi, dan pembaruan",
        action = {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.Close, "Tutup", tint = com.senzaproject.senzatools.ui.theme.LocalGlass.current.textDim)
            }
        }
    ) {
        GlassCard {
            Heading("Tampilan")
            Caption("Bahasa")
            GlassButton(Lang.displayName(), { showLang = true }, Modifier.fillMaxWidth())
            Caption("Tema")
            ChipRow(listOf("Ikuti sistem", "Terang", "Gelap"), s.uiPrefs.themeMode) { s.uiPrefs.setThemeMode(it) }
        }
        if (showLang) LanguageDialog { showLang = false }

        GlassCard {
            Heading("Privasi & data")
            Caption("Semua analisis berjalan di perangkat. Tidak ada telemetri, iklan, atau akun. Jaringan keluar hanya untuk tes jaringan yang kamu jalankan.")
            KeyValue("Cache analisis", cacheSize.formatBytes())
            GlassButton("Bersihkan cache", {
                Saf.clearCache(ctx)
                cacheSize = 0L
            })
        }

        GlassCard {
            Heading("Tentang")
            KeyValue("Versi", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            KeyValue("Build CI", if (BuildConfig.BUILD_NUMBER > 0) "#${BuildConfig.BUILD_NUMBER}" else "Build lokal")
            KeyValue("Pengembang", "SenzaProject")
            KeyValue("Perangkat", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        }
    }
}
