package com.senzaproject.senzaai.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.senzaproject.senzaai.di.ServiceLocator
import com.senzaproject.senzaai.platform.root.SafeAction
import com.senzaproject.senzaai.ui.components.*
import com.senzaproject.senzaai.utils.Sys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Salin teks ke clipboard (dipakai juga oleh ToolsScreen). */
fun copyText(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private val TABS = listOf("Diagnostik", "Properti", "Daya", "Ekspor", "Pintasan", "Game", "DPI", "RAM", "Sinyal", "Charger")

/** Alat bantu praktis untuk pengembang ROM, tanpa internet. */
@Composable
fun UtilitiesScreen(s: ServiceLocator) {
    var tab by rememberSaveable { mutableStateOf(0) }
    ScreenScaffold("Toolkit", "Diagnostik, mode game, DPI, RAM, sinyal, fast charger, dan lainnya") {
        ChipRow(TABS, tab) { tab = it }
        when (tab) {
            0 -> DiagTab(s)
            1 -> PropsTab()
            2 -> PowerTab(s)
            3 -> ExportTab(s)
            4 -> ShortcutsTab()
            5 -> GameFocusTab(s)
            6 -> DpiTab(s)
            7 -> RamTab(s)
            8 -> SignalTab(s)
            else -> ChargerTab(s)
        }
    }
}

// ----------------------------------------------------------------------------- Diagnostik

@Composable
private fun DiagTab(s: ServiceLocator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val report = s.session.lastDiagnostic
    GlassCard(strong = true) {
        Heading("Senza Smart Diagnostics")
        Body("Pemeriksaan lokal (tanpa internet): baterai, RAM, penyimpanan, suhu, SELinux, patch keamanan, dan konfigurasi build. Setiap temuan menyertakan bukti dan tingkat kepastian.", dim = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (busy) "Memeriksa…" else "Jalankan diagnostik", {
                if (!busy) scope.launch {
                    busy = true
                    s.session.lastDiagnostic = withContext(Dispatchers.IO) { s.diagnostics.run() }
                    busy = false
                }
            }, Modifier.weight(1f), primary = true, enabled = !busy)
            GlassButton("Bagikan laporan", {
                val txt = report?.let { s.diagnostics.toText(it) } ?: return@GlassButton
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, txt) }
                ctx.startActivity(Intent.createChooser(send, "Bagikan laporan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, Modifier.weight(1f), enabled = report != null)
        }
    }
    report?.findings?.forEach { f ->
        GlassCard {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Heading(f.title, Modifier.weight(1f))
                SeverityPill(f.severity)
            }
            KeyValue("Bukti", f.evidence)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Caption("Kepastian")
                CertaintyPill(f.certainty)
            }
            Body("Rekomendasi: ${f.recommendation}", dim = true)
        }
    }
}

// ----------------------------------------------------------------------------- Properti

@Composable
private fun PropsTab() {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var copied by remember { mutableStateOf<String?>(null) }
    val props by produceState<List<Pair<String, String>>?>(null, reload) {
        value = withContext(Dispatchers.IO) {
            Sys.refreshProps().entries.map { it.key to it.value }.sortedBy { it.first }
        }
    }
    GlassCard(strong = true) {
        Heading("Pencari properti sistem")
        Body("Cari seluruh getprop (ro.*, persist.*, sys.*, vendor.*). Ketuk baris untuk menyalin.", dim = true)
        GlassTextField(q, { q = it }, "Cari kunci atau nilai (mis. ro.product, mt6789)", mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Muat ulang", { reload++ }, Modifier.weight(1f), primary = true)
            GlassButton("Salin hasil", {
                val list = props?.filter { q.isBlank() || it.first.contains(q, true) || it.second.contains(q, true) }.orEmpty()
                copyText(ctx, "getprop", list.joinToString("\n") { "[${it.first}]: [${it.second}]" })
                copied = "${list.size} properti disalin"
            }, Modifier.weight(1f), enabled = props != null)
        }
        copied?.let { Caption(it) }
    }
    val list = props?.filter { q.isBlank() || it.first.contains(q, true) || it.second.contains(q, true) }
    if (list == null) Spinner("Membaca properti…") else {
        Caption("${list.size} dari ${props?.size ?: 0} properti")
        LimitedList(list, 60) { (k, v) ->
            Column(Modifier.fillMaxWidth().clickableNoRipple {
                copyText(ctx, k, "$k=$v"); copied = "Disalin: $k"
            }) {
                Mono(k, size = 11)
                Caption(v.ifBlank { "(kosong)" })
            }
        }
    }
}

// ----------------------------------------------------------------------------- Daya

private data class PowerAction(val label: String, val cmd: String, val note: String)

private val POWER_ACTIONS = listOf(
    PowerAction("Reboot", "svc power reboot || reboot", "Restart normal."),
    PowerAction("Reboot ke Recovery", "svc power reboot recovery || reboot recovery", "Masuk mode recovery."),
    PowerAction("Reboot ke Bootloader", "svc power reboot bootloader || reboot bootloader", "Masuk mode bootloader/fastboot."),
    PowerAction("Reboot ke Fastbootd", "reboot fastboot", "Fastbootd (userspace), butuh dynamic partitions."),
    PowerAction("Restart UI (soft reboot)", "setprop ctl.restart zygote", "Restart framework tanpa boot ulang penuh. Aplikasi akan tertutup."),
    PowerAction("Matikan", "svc power shutdown || reboot -p", "Mematikan perangkat.")
)

@Composable
private fun PowerTab(s: ServiceLocator) {
    val scope = rememberCoroutineScope()
    val rootOk = s.access.isRootVerified
    var pending by remember { mutableStateOf<PowerAction?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    pending?.let { a ->
        ConfirmDialog(
            title = "${a.label}?",
            message = "${a.note}\n\nSimpan pekerjaan di aplikasi lain dulu. Aksi dijalankan sebagai root dan dicatat di Log.",
            confirmText = "Jalankan",
            danger = true,
            onConfirm = {
                pending = null
                scope.launch {
                    val r = s.access.runRootEdit(a.cmd, a.label)
                    if (!r.ok) msg = "Gagal: ${r.output.take(120)}"
                }
            },
            onDismiss = { pending = null }
        )
    }
    GlassCard(strong = true) {
        Heading("Menu daya")
        Body("Reboot cepat ke mode apa pun tanpa kombinasi tombol. Semua aksi meminta konfirmasi.", dim = true)
        if (!rootOk) Caption("Butuh root. Verifikasi dulu di Tools → Akses.")
    }
    POWER_ACTIONS.forEach { a ->
        GlassCard(padding = 12.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Body(a.label)
                    Caption(a.note)
                }
                GlassButton("Jalankan", { pending = a }, danger = true, enabled = rootOk)
            }
        }
    }
    msg?.let { ErrorCard(it) }
}

// ----------------------------------------------------------------------------- Ekspor

private data class Report(val label: String, val file: String, val build: suspend (ServiceLocator, Boolean) -> String)

private val REPORTS = listOf(
    Report("Properti sistem (getprop)", "getprop.txt") { _, _ ->
        withContext(Dispatchers.IO) { Sys.refreshProps().entries.sortedBy { it.key }.joinToString("\n") { "[${it.key}]: [${it.value}]" } }
    },
    Report("Logcat", "logcat.txt") { s, r -> s.access.run(SafeAction.Logcat, r).output },
    Report("Log kernel (dmesg)", "dmesg.txt") { s, r -> s.access.run(SafeAction.Dmesg, r).output },
    Report("Mount point", "mounts.txt") { s, r -> s.access.run(SafeAction.Mounts, r).output },
    Report("Modul kernel", "kernel_modules.txt") { s, r -> s.access.run(SafeAction.KernelModules, r).output },
    Report("Partisi by-name", "partitions.txt") { s, r -> s.access.run(SafeAction.PartitionsByName, r).output }
)

@Composable
private fun ExportTab(s: ServiceLocator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootOk = s.access.isRootVerified
    var idx by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val text = preview ?: REPORTS[idx].build(s, rootOk)
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                msg = "Tersimpan (${text.length} karakter)."
            } catch (e: Throwable) {
                msg = "Gagal menyimpan: ${e.message}"
            }
            busy = false
        }
    }

    GlassCard(strong = true) {
        Heading("Ekspor laporan")
        Body("Ambil data sistem untuk debugging ROM, lalu simpan ke file, bagikan, atau salin. ${if (rootOk) "Mode root: data penuh." else "Mode normal: sebagian data (logcat/dmesg) dibatasi Android."}", dim = true)
        ChipRow(REPORTS.map { it.label }, idx) { idx = it; preview = null; msg = null }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (busy) "Mengambil…" else "Ambil", {
                scope.launch {
                    busy = true; msg = null
                    preview = REPORTS[idx].build(s, rootOk).ifBlank { "(kosong / tidak dapat dibaca)" }
                    busy = false
                }
            }, Modifier.weight(1f), primary = true, enabled = !busy)
            GlassButton("Simpan", { save.launch(REPORTS[idx].file) }, Modifier.weight(1f), enabled = !busy && preview != null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Bagikan", {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, preview.orEmpty().takeLast(200_000))
                }
                ctx.startActivity(Intent.createChooser(send, "Bagikan laporan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, Modifier.weight(1f), enabled = preview != null)
            GlassButton("Salin", {
                copyText(ctx, REPORTS[idx].file, preview.orEmpty().takeLast(200_000)); msg = "Disalin ke clipboard."
            }, Modifier.weight(1f), enabled = preview != null)
        }
        msg?.let { Caption(it) }
        Caption("Laporan bisa memuat data sensitif (serial, IMEI parsial, nama aplikasi). Periksa sebelum dibagikan.")
    }
    preview?.let { t ->
        GlassCard {
            Caption("${t.lines().size} baris · ditampilkan 300 baris terakhir")
            SelectionContainer { Mono(t.lines().takeLast(300).joinToString("\n"), size = 10) }
        }
    }
}

// ----------------------------------------------------------------------------- Pintasan

private data class Shortcut(val label: String, val note: String, val action: String)

private val SHORTCUTS = listOf(
    Shortcut("Opsi pengembang", "Debugging, OEM unlock, animasi.", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
    Shortcut("Info perangkat", "Halaman tentang ponsel.", Settings.ACTION_DEVICE_INFO_SETTINGS),
    Shortcut("Pengaturan baterai", "Hemat daya & optimasi baterai.", Settings.ACTION_BATTERY_SAVER_SETTINGS),
    Shortcut("Semua aplikasi", "Kelola aplikasi terpasang.", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
    Shortcut("Penyimpanan", "Pemakaian penyimpanan internal.", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
    Shortcut("Pembaruan sistem", "Cek OTA / patch keamanan.", "android.settings.SYSTEM_UPDATE_SETTINGS")
)

@Composable
private fun ShortcutsTab() {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    GlassCard(strong = true) {
        Heading("Pintasan sistem")
        Body("Buka halaman pengaturan yang sering dipakai langsung dari satu tempat.", dim = true)
        msg?.let { Caption(it) }
    }
    SHORTCUTS.forEach { sc ->
        GlassCard(padding = 12.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Body(sc.label)
                    Caption(sc.note)
                }
                GlassButton("Buka", {
                    try {
                        ctx.startActivity(Intent(sc.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        msg = null
                    } catch (_: Throwable) {
                        msg = "${sc.label}: halaman ini tidak tersedia di perangkat."
                    }
                }, primary = true)
            }
        }
    }
}
