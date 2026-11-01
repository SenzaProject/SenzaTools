package com.senzaproject.senzatools.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
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
import com.senzaproject.senzatools.update.UpdateState

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
            Body("• Semua analisis berjalan di perangkat.\n• Tidak ada telemetri, iklan, atau akun.\n• Kredensial jaringan (SSID/BSSID/sandi) tidak dikumpulkan.\n• Backup otomatis Android dinonaktifkan untuk aplikasi ini.\n• Lalu lintas jaringan keluar hanya dua: pemeriksaan pembaruan ke GitHub (api.github.com, tanpa data pribadi) dan tes diagnostik jaringan yang kamu jalankan.", dim = true)
            KeyValue("Cache analisis", cacheSize.formatBytes())
            GlassButton("Bersihkan cache", {
                Saf.clearCache(ctx)
                cacheSize = 0L
            })
        }

        UpdateCard(s)

        GlassCard {
            Heading("Tentang")
            KeyValue("Aplikasi", "Senza Tools")
            KeyValue("Versi", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            KeyValue("Build CI", if (BuildConfig.BUILD_NUMBER > 0) "#${BuildConfig.BUILD_NUMBER}" else "Build lokal")
            KeyValue("Pengembang", "SenzaProject")
            KeyValue("Perangkat", "${Build.MANUFACTURER} ${Build.MODEL}")
            KeyValue("Android", "${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
            Caption("Intelligent Android. Smarter System Control.")
        }
    }
}

@Composable
private fun UpdateCard(s: ServiceLocator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = s.updates.state
    var busy by remember { mutableStateOf(false) }
    GlassCard {
        Heading("Pembaruan")
        KeyValue(
            "Status",
            when (st) {
                is UpdateState.UpToDate -> "Sudah versi terbaru"
                is UpdateState.DevBuild -> "Build lokal (tanpa pemeriksaan)"
                is UpdateState.Checking -> "Memeriksa…"
                is UpdateState.Required -> "Ada build baru #${st.latest.runNumber}"
                is UpdateState.Unknown -> "Tidak bisa memeriksa: ${st.reason}"
                is UpdateState.Idle -> "Belum diperiksa"
            }
        )
        Caption("Sumber: GitHub Actions SenzaProject/SenzaTools. Bila ada build yang lebih baru, aplikasi meminta pembaruan dan tidak bisa dipakai sebelum diperbarui.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (busy) "Memeriksa…" else "Periksa pembaruan", {
                scope.launch {
                    busy = true
                    s.updates.check()
                    busy = false
                }
            }, enabled = !busy && BuildConfig.BUILD_NUMBER > 0)
            GlassButton("Buka halaman build", { openUpdatePage(ctx, BuildConfig.UPDATE_PAGE) })
        }
    }
}
