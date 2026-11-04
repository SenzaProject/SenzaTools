package com.senzaproject.senzatools.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.analyzer.system.AvcDenial
import com.senzaproject.senzatools.analyzer.system.SelinuxAnalyzer
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.platform.root.SafeAction
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.ui.theme.LocalGlass
import kotlinx.coroutines.launch

private const val TOP = 10

@Composable
fun SelinuxControlTab(s: ServiceLocator) {
    val c = s.selinux
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootOk = s.access.state.collectAsState().let { s.access.isRootVerified }

    var mode by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgOk by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<Boolean?>(null) }   // true = ke Permissive, false = ke Enforcing
    var denials by remember { mutableStateOf<List<AvcDenial>?>(null) }
    var scanNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(rootOk) { mode = c.status(); loaded = true }

    GlassCard(strong = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Heading("Mode SELinux")
            when {
                !loaded -> Caption("Membaca…")
                mode == null -> StatusPill("Tidak terbaca", g.textDim)
                mode == "Enforcing" -> StatusPill("Enforcing", g.good)
                else -> StatusPill(mode ?: "", g.warn)
            }
        }
        if (!rootOk) Caption("Mengubah mode butuh akses root. Izinkan di root manager, lalu tekan Minta izin root di Tools → Akses.", color = g.warn)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Permissive", { confirm = true }, Modifier.weight(1f), enabled = rootOk && !busy && mode == "Enforcing")
            GlassButton("Enforcing", { confirm = false }, Modifier.weight(1f), primary = true, enabled = rootOk && !busy && mode == "Permissive")
        }
        GlassButton("Muat ulang status", {
            scope.launch { busy = true; mode = c.status(); busy = false }
        }, Modifier.fillMaxWidth(), enabled = !busy)
        msg?.let { Body(it, color = if (msgOk) g.good else g.bad) }
        Caption("Permissive hanya mencatat pelanggaran tanpa memblokir, jadi berguna untuk mencari penyebab bug di ROM port. Perubahan bersifat sementara dan hilang saat restart.")
    }

    GlassCard {
        Heading("Denial terbaru")
        Caption("Mengambil baris avc: denied dari log kernel (dmesg), lalu logcat bila kosong. Analisis lengkap ada di Analyzer → SELinux.")
        GlassButton(if (busy) "Memindai…" else "Pindai denial", {
            scope.launch {
                busy = true; scanNote = null
                var r = s.access.run(SafeAction.Dmesg, true)
                var found = SelinuxAnalyzer.parse(r.output)
                var from = "dmesg"
                if (found.isEmpty()) {
                    r = s.access.run(SafeAction.Logcat, true)
                    found = SelinuxAnalyzer.parse(r.output)
                    from = "logcat"
                }
                denials = found
                scanNote = if (found.isEmpty()) "Tidak ada denial ditemukan. Log mungkin sudah bergulir; coba reproduksi bug lalu pindai lagi."
                else "${found.size} kelompok denial dari $from (menampilkan $TOP teratas)."
                busy = false
            }
        }, Modifier.fillMaxWidth(), primary = true, enabled = rootOk && !busy)
        scanNote?.let { Caption(it) }
    }

    denials?.take(TOP)?.let { top ->
        if (top.isNotEmpty()) {
            GlassCard {
                top.forEachIndexed { i, d ->
                    if (i > 0) GlassDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Mono("${d.sType} → ${d.tType}", Modifier.weight(1f), 11)
                        StatusPill("${d.count}×", g.warn)
                    }
                    Caption("${d.tclass} { ${d.perms} }" + (d.comm?.let { " · $it" } ?: ""))
                    Mono(SelinuxAnalyzer.suggestion(d), size = 10)
                    SelinuxAnalyzer.caution(d)?.let { Caption("⚠ $it", color = g.warn) }
                }
                GlassButton("Salin semua saran rule", {
                    copyText(ctx, "sepolicy", top.joinToString("\n") { SelinuxAnalyzer.suggestion(it) })
                }, Modifier.fillMaxWidth())
                Caption("Saran rule hanya contoh untuk dipelajari. Periksa neverallow dan risikonya sebelum dipakai di sepolicy.")
            }
        }
    }

    confirm?.let { toPermissive ->
        ConfirmDialog(
            if (toPermissive) "Ubah SELinux ke Permissive?" else "Kembalikan SELinux ke Enforcing?",
            if (toPermissive) "Pelanggaran policy tidak diblokir lagi, hanya dicatat. Keamanan perangkat menurun sampai kamu mengembalikannya atau restart. Pakai hanya untuk debugging."
            else "Policy SELinux kembali diterapkan penuh. Aplikasi atau layanan yang tadi lolos karena Permissive bisa mulai ditolak lagi.",
            confirmText = if (toPermissive) "Permissive" else "Enforcing",
            danger = toPermissive,
            onConfirm = {
                confirm = null
                scope.launch {
                    busy = true; msg = null
                    val r = c.setPermissive(toPermissive)
                    msg = r.message; msgOk = r.ok
                    mode = c.status(); busy = false
                }
            },
            onDismiss = { confirm = null }
        )
    }
}
