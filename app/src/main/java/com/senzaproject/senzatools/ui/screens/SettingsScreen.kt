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
import com.senzaproject.senzatools.update.UpdateState

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
            Caption("Semua analisis berjalan di perangkat. Tidak ada telemetri, iklan, atau akun. Jaringan keluar hanya untuk cek pembaruan ke GitHub dan tes jaringan yang kamu jalankan.")
            KeyValue("Cache analisis", cacheSize.formatBytes())
            GlassButton("Bersihkan cache", {
                Saf.clearCache(ctx)
                cacheSize = 0L
            })
        }

        UpdateCard(s)

        GlassCard {
            Heading("Tentang")
            KeyValue("Versi", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            KeyValue("Build CI", if (BuildConfig.BUILD_NUMBER > 0) "#${BuildConfig.BUILD_NUMBER}" else "Build lokal")
            KeyValue("Pengembang", "SenzaProject")
            KeyValue("Perangkat", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
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
        Caption("Sumber: GitHub Actions SenzaProject/SenzaTools.")
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
