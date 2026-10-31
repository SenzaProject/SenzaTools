package com.senzaproject.senzaai.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.senzaproject.senzaai.BuildConfig
import com.senzaproject.senzaai.ai.AiProvider
import com.senzaproject.senzaai.ai.AiSettings
import com.senzaproject.senzaai.di.ServiceLocator
import com.senzaproject.senzaai.ui.components.*
import com.senzaproject.senzaai.utils.Saf
import com.senzaproject.senzaai.utils.formatBytes
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(s: ServiceLocator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { s.aiSettings.load() }
    var provider by remember { mutableStateOf(initial.provider) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(initial.apiKey.isNotBlank()) }
    var status by remember { mutableStateOf<String?>(null) }
    var testBusy by remember { mutableStateOf(false) }
    var confirmClearKey by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { File(ctx.cacheDir, "analysis").walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }

    if (confirmClearKey) ConfirmDialog(
        "Hapus API key?", "Kunci API yang tersimpan akan dihapus dari perangkat ini.", "Hapus", true,
        { s.aiSettings.clearKey(); hasKey = false; key = ""; confirmClearKey = false; status = "API key dihapus." },
        { confirmClearKey = false }
    )

    ScreenScaffold("Settings", "Tampilan, AI, privasi") {
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
            Heading("AI")
            Body("Senza AI memakai layanan AI milikmu sendiri (bring-your-own-key). Tidak ada data yang dikirim kecuali kamu mengirim pesan, dan hanya lampiran yang kamu centang.", dim = true)
            Caption("Penyedia")
            ChipRow(AiProvider.values().map { it.label }, provider.ordinal) {
                val p = AiProvider.values()[it]
                provider = p
                baseUrl = p.defaultUrl
                model = p.defaultModel
            }
            GlassTextField(baseUrl, { baseUrl = it }, "Base URL (harus https://)", mono = true)
            GlassTextField(model, { model = it }, "Nama model", mono = true)
            GlassTextField(key, { key = it }, if (hasKey) "API key (tersimpan — isi untuk mengganti)" else "API key", password = true)
            Caption("Kunci disimpan terenkripsi (AES-GCM, Android Keystore) dan tidak pernah ditampilkan kembali atau dicatat.")
            val urlOk = baseUrl.trim().startsWith("https://")
            if (!urlOk) Caption("Base URL harus diawali https://", color = com.senzaproject.senzaai.ui.theme.LocalGlass.current.bad)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Simpan", {
                    val existing = s.aiSettings.load().apiKey
                    s.aiSettings.save(AiSettings(provider, baseUrl, model, if (key.isNotBlank()) key else existing))
                    hasKey = s.aiSettings.load().apiKey.isNotBlank()
                    key = ""
                    status = "Pengaturan disimpan."
                }, Modifier.weight(1f), primary = true, enabled = urlOk && model.isNotBlank())
                GlassButton(if (testBusy) "Menguji…" else "Tes koneksi", {
                    scope.launch {
                        testBusy = true
                        val cfg = AiSettings(provider, baseUrl.trim().trimEnd('/'), model.trim(), if (key.isNotBlank()) key.trim() else s.aiSettings.load().apiKey)
                        status = try {
                            withContext(Dispatchers.IO) {
                                s.aiClient.chat(cfg, "Balas hanya dengan kata: OK", listOf(com.senzaproject.senzaai.ai.ChatMessage("user", "ping")))
                            }
                            "Koneksi berhasil."
                        } catch (t: Throwable) {
                            "Gagal: ${t.message}"
                        }
                        testBusy = false
                    }
                }, Modifier.weight(1f), enabled = urlOk && !testBusy)
            }
            if (hasKey) GlassButton("Hapus API key", { confirmClearKey = true }, danger = true)
            status?.let { Caption(it) }
        }

        GlassCard {
            Heading("Privasi & data")
            Body("• Semua analisis berjalan di perangkat.\n• Tidak ada telemetri, iklan, atau akun.\n• Kredensial jaringan (SSID/BSSID/sandi) tidak dikumpulkan.\n• Backup otomatis Android dinonaktifkan untuk aplikasi ini.\n• Satu-satunya lalu lintas jaringan keluar: tes diagnostik jaringan yang kamu jalankan, dan pesan ke layanan AI yang kamu atur.", dim = true)
            KeyValue("Cache analisis", cacheSize.formatBytes())
            GlassButton("Bersihkan cache", {
                Saf.clearCache(ctx)
                cacheSize = 0L
            })
        }

        GlassCard {
            Heading("Tentang")
            KeyValue("Aplikasi", "Senza AI")
            KeyValue("Versi", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            KeyValue("Pengembang", "SenzaProject")
            KeyValue("Perangkat", "${Build.MANUFACTURER} ${Build.MODEL}")
            KeyValue("Android", "${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
            Caption("Intelligent Android. Smarter System Control.")
        }
    }
}
