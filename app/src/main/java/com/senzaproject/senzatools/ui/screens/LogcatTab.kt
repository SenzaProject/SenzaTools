package com.senzaproject.senzatools.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.platform.root.LogDump
import com.senzaproject.senzatools.platform.root.LogLevel
import com.senzaproject.senzatools.platform.root.LogParse
import com.senzaproject.senzatools.platform.root.LogSource
import com.senzaproject.senzatools.platform.root.LogTools
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.ui.theme.LocalGlass
import kotlinx.coroutines.launch

private const val MAX_SHOWN = 300

@Composable
fun LogcatTab(s: ServiceLocator) {
    val t = s.logs
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootOk = s.access.state.collectAsState().let { s.access.isRootVerified }

    val sources = LogSource.values()
    val levels = LogLevel.values()
    var src by remember { mutableStateOf(0) }
    var level by remember { mutableStateOf(0) }
    var countIdx by remember { mutableStateOf(1) }
    var keyword by remember { mutableStateOf("") }
    var dump by remember { mutableStateOf<LogDump?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgOk by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }

    GlassCard(strong = true) {
        Heading("Log sistem")
        Caption("Ambil log untuk debug ROM. Butuh root agar log seluruh sistem terbaca.")
        ChipRow(sources.map { it.label }, src) { src = it }
        if (sources[src] == LogSource.LOGCAT) ChipRow(levels.map { it.label }, level) { level = it }
        Caption("Jumlah baris terakhir")
        ChipRow(LogTools.COUNTS.map { it.toString() }, countIdx) { countIdx = it }
        GlassTextField(keyword, { keyword = it }, "Filter kata kunci (opsional)")
        if (!rootOk) Caption("Fitur ini butuh akses root. Izinkan di root manager, lalu tekan Minta izin root di Tools → Akses.", color = g.warn)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (busy) "Memuat…" else "Muat log", {
                scope.launch {
                    busy = true; msg = null
                    val d = t.read(sources[src], levels[level], LogTools.COUNTS[countIdx])
                    if (d.ok) dump = d else { dump = null; msg = d.message; msgOk = false }
                    busy = false
                }
            }, Modifier.weight(1f), primary = true, enabled = rootOk && !busy)
            GlassButton("Bersihkan buffer", { confirmClear = true }, Modifier.weight(1f), enabled = rootOk && !busy)
        }
        GlassButton("Simpan paket log ke file", {
            scope.launch {
                busy = true; msg = null
                val r = t.saveBundle()
                msg = r.message; msgOk = r.ok; busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = rootOk && !busy)
        msg?.let { Body(it, color = if (msgOk) g.good else g.bad) }
        Caption("Paket log berisi logcat, crash, dmesg, dan getprop dalam satu folder bertanggal di Download/SenzaTools/logs. Praktis untuk laporan bug.")
    }

    dump?.let { snap ->
        val all = snap.lines
        val matched = remember(all, keyword) { LogTools.filter(all, keyword) }
        val shown = remember(matched) { matched.takeLast(MAX_SHOWN) }
        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Heading("Hasil")
                Caption("${shown.size} dari ${all.size} baris")
            }
            Caption("Snapshot ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(snap.capturedAt))} · sidik jari ${snap.fingerprint} · ${snap.total} baris cocok di buffer. Isi buffer yang sama selalu memberi sidik jari yang sama.")
            GlassButton("Salin", { copyText(ctx, "log", LogParse.joinTail(matched, 400_000)) }, Modifier.fillMaxWidth(), enabled = matched.isNotEmpty())
            SelectionContainer { Mono(shown.joinToString("\n").ifBlank { "(tidak ada baris yang cocok)" }, size = 10) }
            if (matched.size > shown.size) Caption("Layar menampilkan $MAX_SHOWN baris terakhir dari ${matched.size}. Tombol Salin menyalin semua ${matched.size} baris.")
        }
    }

    if (confirmClear) ConfirmDialog(
        "Bersihkan buffer logcat?",
        "Semua isi buffer logcat dihapus. Berguna sebelum mereproduksi bug supaya log bersih. Log kernel (dmesg) tidak ikut terhapus.",
        confirmText = "Bersihkan",
        onConfirm = {
            confirmClear = false
            scope.launch {
                busy = true
                val r = t.clearBuffers()
                msg = r.message; msgOk = r.ok; dump = null; busy = false
            }
        },
        onDismiss = { confirmClear = false }
    )
}
