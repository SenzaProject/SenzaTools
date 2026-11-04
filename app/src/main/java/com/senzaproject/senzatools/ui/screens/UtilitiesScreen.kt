package com.senzaproject.senzatools.ui.screens

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
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.platform.root.RootPower
import com.senzaproject.senzatools.platform.root.SafeAction
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.utils.Sys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Salin teks ke clipboard (dipakai juga oleh ToolsScreen). */
fun copyText(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private val TABS = listOf("Daya", "Ekspor", "Pintasan", "Game", "DPI", "RAM", "zRAM", "DNS", "Logcat", "SELinux", "Sinyal", "Charger", "Kernel", "Fake Device")

/** Alat bantu praktis untuk pengembang ROM, tanpa internet. */
@Composable
fun UtilitiesScreen(s: ServiceLocator) {
    var tab by rememberSaveable { mutableStateOf(0) }
    ScreenScaffold("Toolkit", "Daya, game, DPI, RAM, zRAM, DNS, logcat, SELinux, sinyal, charger, dan fake device") {
        ChipRow(TABS, tab) { tab = it }
        when (tab) {
            0 -> PowerTab(s)
            1 -> ExportTab(s)
            2 -> ShortcutsTab()
            3 -> GameFocusTab(s)
            4 -> DpiTab(s)
            5 -> RamTab(s)
            6 -> ZramTab(s)
            7 -> DnsTab(s)
            8 -> LogcatTab(s)
            9 -> SelinuxControlTab(s)
            10 -> SignalTab(s)
            11 -> ChargerTab(s)
            12 -> KernelTab(s)
            else -> FakeDeviceTab(s)
        }
    }
}

// ----------------------------------------------------------------------------- Daya

private data class PowerAction(val label: String, val cmd: String, val note: String)

private val POWER_ACTIONS = listOf(
    PowerAction("Reboot", RootPower.command(), "Restart normal. Mencoba beberapa metode berurutan (svc, reboot, sys.powerctl, SysRq) sampai perangkat benar-benar restart."),
    PowerAction("Reboot ke Recovery", RootPower.command("recovery"), "Masuk mode recovery."),
    PowerAction("Reboot ke Bootloader", RootPower.command("bootloader"), "Masuk mode bootloader/fastboot."),
    PowerAction("Reboot ke Fastbootd", RootPower.command("fastboot"), "Fastbootd (userspace), butuh dynamic partitions."),
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
        if (!rootOk) Caption("Butuh root. Izinkan di root manager, lalu tekan Minta izin root di Tools → Akses.")
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
