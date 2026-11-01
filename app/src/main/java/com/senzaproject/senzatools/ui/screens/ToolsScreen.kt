package com.senzaproject.senzatools.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import com.senzaproject.senzatools.domain.model.AccessMode
import com.senzaproject.senzatools.monitor.PerfSample
import com.senzaproject.senzatools.monitor.ProcInfo
import com.senzaproject.senzatools.platform.root.SafeAction
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.ui.theme.LocalGlass
import com.senzaproject.senzatools.utils.Sys
import com.senzaproject.senzatools.ui.screens.copyText
import com.senzaproject.senzatools.utils.f1
import com.senzaproject.senzatools.utils.formatBytes
import com.senzaproject.senzatools.utils.formatDuration
import com.senzaproject.senzatools.utils.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TABS = listOf("Akses", "Shell & ADB", "Monitor", "Performa", "Log")

@Composable
fun ToolsScreen(s: ServiceLocator) {
    var tab by rememberSaveable { mutableStateOf(0) }
    ScreenScaffold("Tools", "Akses, shell, monitor, performa") {
        ChipRow(TABS, tab) { tab = it }
        when (tab) {
            0 -> AccessTab(s)
            1 -> ShellTab(s)
            2 -> MonitorTab(s)
            3 -> PerfTab(s)
            else -> LogTab(s)
        }
    }
}

// ============================================================================ Akses

@Composable
private fun AccessTab(s: ServiceLocator) {
    val g = LocalGlass.current
    val st by s.access.state.collectAsState()
    val scope = rememberCoroutineScope()
    val busy = s.access.rootChecking
    var msg by remember { mutableStateOf<String?>(null) }

    GlassCard(strong = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Heading("Senza Access Manager")
            StatusPill(st.active.label, if (st.active == AccessMode.ROOT) g.warn else g.good)
        }
        Body("Tiga tingkat akses diperlakukan berbeda: Normal (API standar), ADB/Shizuku, dan Root.", dim = true)
    }

    ExpandableCard("Lingkungan root", true) {
        st.root.details.forEach { Body("• $it") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (busy) "Menunggu izin root…" else "Minta izin root", {
                scope.launch {
                    val r = s.access.verifyRoot()
                    msg = if (s.access.isRootVerified) "Akses root aktif (uid=0)." else "Root tidak tersedia atau ditolak. ${r.output.take(120)}"
                }
            }, primary = true, enabled = !busy && !st.root.verified)
            if (st.root.verified) GlassButton("Akhiri sesi root", { s.access.revokeRootSession() }, danger = true)
            GlassButton("Deteksi ulang", { s.access.refresh() })
        }
        if (st.root.verified) Caption("Root aktif. Izin cukup diberikan sekali di root manager; selama tidak dicabut di sana, semua fitur tetap berjalan.")
        else Caption("Root diminta otomatis sekali saat apk dibuka. Kalau tadi ditolak atau terlewat, tekan Minta izin root.")
        msg?.let { Caption(it) }
    }
    ExpandableCard("Kemampuan mode aktif", true) { st.capabilities.forEach { Body("✓ $it") } }
    ExpandableCard("Batasan", true) { st.limits.forEach { Body("• $it", dim = true) } }
    GlassCard {
        Heading("ADB / Shizuku")
        KeyValue("Shizuku", if (st.shizukuInstalled) "Terpasang" else "Tidak terdeteksi")
        Caption("Integrasi eksekusi lewat Shizuku belum disertakan di versi ini. Senza Tools tidak menganggap Shizuku, ADB, dan Root setara; fitur yang butuh root tidak akan berjalan hanya dengan Shizuku.")
    }
    GlassCard {
        Heading("Prinsip keamanan")
        Body("• Read-only secara default; perubahan sistem hanya lewat fitur yang kamu jalankan sendiri\n• Tidak ada operasi root tersembunyi atau diam-diam\n• Perintah berbahaya (format, tulis ke partisi, hapus sistem) diblokir\n• Seluruh operasi sensitif dicatat", dim = true)
    }
}

// ============================================================================ Shell & ADB

@Composable
private fun ShellTab(s: ServiceLocator) {
    AdbInfoCard(s)
    PackageCard(s)
    LogcatCard(s)
    ConsoleCard(s)
    FastbootCard()
}

@Composable
private fun AdbInfoCard(s: ServiceLocator) {
    val ctx = LocalContext.current
    val rows by produceState<List<Pair<String, String>>?>(null) {
        value = withContext(Dispatchers.IO) {
            fun gInt(k: String) = try {
                Settings.Global.getInt(ctx.contentResolver, k).let { if (it == 1) "Aktif" else "Nonaktif" }
            } catch (_: Throwable) {
                "Tidak tersedia"
            }
            val p = Sys.refreshProps()
            listOf(
                "Opsi pengembang" to gInt("development_settings_enabled"),
                "USB debugging" to gInt("adb_enabled"),
                "Debugging nirkabel" to gInt("adb_wifi_enabled"),
                "Mode USB (persist.sys.usb.config)" to (p["persist.sys.usb.config"]?.ifBlank { null } ?: "Tidak tersedia"),
                "Status USB (sys.usb.state)" to (p["sys.usb.state"]?.ifBlank { null } ?: "Tidak tersedia"),
                "Otorisasi RSA adb (ro.adb.secure)" to (p["ro.adb.secure"]?.ifBlank { null } ?: "Tidak tersedia"),
                "Port TCP adb" to (p["service.adb.tcp.port"]?.ifBlank { null } ?: "Tidak diatur"),
                "Debuggable (ro.debuggable)" to (p["ro.debuggable"]?.ifBlank { null } ?: "Tidak tersedia")
            )
        }
    }
    ExpandableCard("ADB — status & koneksi", true) {
        rows?.forEach { KeyValue(it.first, it.second) } ?: Spinner("Membaca…")
        Caption("Senza Tools berjalan di perangkat itu sendiri, bukan sebagai klien adb dari komputer. Daftar perangkat adb lain tidak tersedia di sini.")
    }
}

@Composable
private fun PackageCard(s: ServiceLocator) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val apps by produceState<List<Pair<String, String>>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(i, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
        }
    }

    fun show(pkg: String) {
        @Suppress("DEPRECATION")
        val pi = try {
            ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        } catch (_: Throwable) {
            null
        }
        selected = if (pi == null) listOf("Paket" to "$pkg tidak terlihat oleh aplikasi ini")
        else {
            val ai = pi.applicationInfo
            val installer = try {
                if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(pkg).installingPackageName else null
            } catch (_: Throwable) {
                null
            }
            listOf(
                "Paket" to pkg,
                "Versi" to "${pi.versionName ?: "?"} (${pi.longVersionCode})",
                "Min / Target SDK" to "${ai?.minSdkVersion ?: "?"} / ${ai?.targetSdkVersion ?: "?"}",
                "UID" to (ai?.uid?.toString() ?: "?"),
                "Terpasang" to pi.firstInstallTime.formatTime(),
                "Diperbarui" to pi.lastUpdateTime.formatTime(),
                "Installer" to (installer ?: "Tidak diketahui"),
                "Lokasi APK" to (ai?.sourceDir ?: "?"),
                "Izin diminta" to (pi.requestedPermissions?.size ?: 0).toString(),
                "Sistem" to if (ai != null && (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) "Ya" else "Tidak"
            )
        }
    }

    ExpandableCard("Informasi aplikasi", false) {
        Caption("Menampilkan aplikasi yang memiliki ikon peluncur. Android membatasi visibilitas paket lain (package visibility).")
        GlassTextField(q, { q = it }, "Cari nama atau paket")
        val list = apps?.filter { q.isBlank() || it.first.contains(q, true) || it.second.contains(q, true) }
        if (list == null) Spinner("Memuat…") else LimitedList(list, 15) { (label, pkg) ->
            Column(Modifier.fillMaxWidth().clickableNoRipple { show(pkg) }) {
                Body(label)
                Caption(pkg)
            }
        }
        selected?.let { sel ->
            GlassDivider()
            sel.forEach { KeyValue(it.first, it.second) }
        }
    }
}

@Composable
private fun LogcatCard(s: ServiceLocator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0) }
    val levels = listOf("Semua", "W+", "E+")
    val rootOk = s.access.isRootVerified
    ExpandableCard("Logcat viewer", false) {
        Caption(if (rootOk) "Mode root: log sistem penuh." else "Normal Access: Android hanya memberi log milik aplikasi ini. Untuk log sistem, verifikasi root atau ekspor log lewat adb dari komputer.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (busy) "Mengambil…" else "Ambil logcat", {
                scope.launch {
                    busy = true
                    text = s.access.run(SafeAction.Logcat, rootOk).output
                    busy = false
                }
            }, primary = true, enabled = !busy)
            GlassButton("Salin log", { text?.let { copyText(ctx, "logcat", it.takeLast(60_000)) } }, enabled = text != null)
        }
        ChipRow(levels, level) { level = it }
        GlassTextField(filter, { filter = it }, "Filter teks")
        text?.let { t ->
            val lines = remember(t, filter, level) {
                t.lines().filter { l ->
                    (filter.isBlank() || l.contains(filter, true)) && when (level) {
                        1 -> Regex("""\s[WEF]\s""").containsMatchIn(l)
                        2 -> Regex("""\s[EF]\s""").containsMatchIn(l)
                        else -> true
                    }
                }.takeLast(400)
            }
            Caption("${lines.size} baris (maks 400 terakhir)")
            SelectionContainer { Mono(lines.joinToString("\n").ifBlank { "(kosong)" }, size = 10) }
        }
    }
}

@Composable
private fun ConsoleCard(s: ServiceLocator) {
    val scope = rememberCoroutineScope()
    var cmd by remember { mutableStateOf("") }
    var asRoot by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val rootOk = s.access.isRootVerified

    if (confirm) ConfirmDialog(
        title = if (asRoot) "Jalankan sebagai ROOT?" else "Jalankan perintah?",
        message = "Perintah:\n\n$cmd\n\nPerintah dijalankan persis seperti yang kamu ketik. Senza Tools tidak memeriksa efek sampingnya selain daftar blokir dasar. Pastikan kamu memahami risikonya dan punya backup bila menyentuh sistem.",
        confirmText = "Jalankan",
        danger = asRoot,
        onConfirm = {
            confirm = false
            scope.launch {
                busy = true
                val r = s.access.runUserCommand(cmd, asRoot)
                output = "exit=${r.exitCode}${if (r.timedOut) " (timeout)" else ""}\n" + r.output.take(30_000)
                busy = false
            }
        },
        onDismiss = { confirm = false }
    )

    ExpandableCard("Shell console", false) {
        Caption("Hanya menjalankan perintah yang kamu ketik sendiri dan konfirmasi. Perintah destruktif umum (format, tulis ke partisi blok, hapus /system, dll.) diblokir.")
        GlassTextField(cmd, { cmd = it }, "Perintah", singleLine = false, mono = true, minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (asRoot) "Sebagai root" else "Sebagai aplikasi", asRoot) { if (rootOk) asRoot = !asRoot }
            if (!rootOk) Caption("Root belum aktif (izinkan di root manager)")
        }
        GlassButton(if (busy) "Berjalan…" else "Jalankan…", { confirm = true }, primary = true, enabled = cmd.isNotBlank() && !busy, danger = asRoot)
        output?.let { SelectionContainer { Mono(it, size = 11) } }
    }
}

@Composable
private fun FastbootCard() {
    val rows by produceState<List<Pair<String, String>>?>(null) {
        value = withContext(Dispatchers.IO) {
            val p = Sys.props()
            fun v(k: String) = p[k]?.ifBlank { null } ?: "Tidak tersedia"
            listOf(
                "Bootloader" to Build.BOOTLOADER,
                "Device / codename" to Build.DEVICE,
                "Slot aktif" to v("ro.boot.slot_suffix"),
                "Bootloader terkunci (flash.locked)" to v("ro.boot.flash.locked"),
                "Status vbmeta" to v("ro.boot.vbmeta.device_state"),
                "Verified boot" to v("ro.boot.verifiedbootstate"),
                "OEM unlock diizinkan" to v("sys.oem_unlock_allowed"),
                "Dynamic partitions" to v("ro.boot.dynamic_partitions")
            )
        }
    }
    ExpandableCard("Fastboot & bootloader (informasi)", false) {
        rows?.forEach { KeyValue(it.first, it.second) } ?: Spinner("Membaca…")
        Caption("Kontrol fastboot/bootloader (flash, unlock, boot image) dijalankan dari komputer atau aplikasi pendamping dan tidak dijanjikan berjalan langsung di semua perangkat. Senza Tools hanya menampilkan informasinya.")
    }
}

// ============================================================================ Monitor

@Composable
private fun MonitorTab(s: ServiceLocator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    val samples = remember { mutableStateListOf<PerfSample>() }
    var procs by remember { mutableStateOf<Pair<List<ProcInfo>, String>?>(null) }
    var history by remember { mutableStateOf<List<PerfSample>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }
    val rootOk = s.access.isRootVerified

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            try {
                val f = s.history.exportFile()
                if (f != null) ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            } catch (_: Throwable) {
            }
        }
    }

    LaunchedEffect(Unit) { history = withContext(Dispatchers.IO) { s.history.load() } }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val pending = mutableListOf<PerfSample>()
        try {
            s.monitor.samples(2000).collect { smp ->
                samples.add(smp)
                if (samples.size > 90) samples.removeAt(0)
                pending.add(smp)
                if (pending.size >= 15) {
                    s.history.append(pending.toList())
                    pending.clear()
                }
            }
        } finally {
            if (pending.isNotEmpty()) s.history.append(pending.toList())
        }
    }

    if (confirmClear) ConfirmDialog(
        title = "Hapus riwayat?", message = "Seluruh riwayat sesi monitoring akan dihapus.", confirmText = "Hapus", danger = true,
        onConfirm = { s.history.clear(); history = emptyList(); confirmClear = false },
        onDismiss = { confirmClear = false }
    )

    val g = LocalGlass.current
    GlassCard(strong = true) {
        Heading("Performance Monitor")
        Body("Pengambilan data hanya berjalan selama monitor dinyalakan dan layar ini terbuka (tidak ada layanan latar belakang) agar hemat baterai.", dim = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (running) "Hentikan" else "Mulai monitor", { running = !running }, primary = true, danger = running)
            GlassButton("Muat riwayat", { scope.launch { history = withContext(Dispatchers.IO) { s.history.load() } } })
        }
        KeyValue("Uptime sistem", s.monitor.uptimeMs().formatDuration())
        KeyValue("Uptime aktif (tanpa deep sleep)", s.monitor.uptimeAwakeMs().formatDuration())
    }

    if (samples.isNotEmpty()) {
        val list = samples.toList()
        GlassCard {
            Heading("Penggunaan (sesi berjalan)")
            LineChart(
                listOf("CPU" to list.map { it.cpu }, "RAM" to list.map { it.ramPercent }),
                listOf(g.accent2, g.accent), minY = 0f, maxY = 100f, unit = "%"
            )
            if (list.any { it.cpuEstimate }) Caption("CPU diestimasi dari frekuensi core karena /proc/stat tidak dapat dibaca.")
        }
        GlassCard {
            Heading("Suhu")
            LineChart(
                listOf("Baterai" to list.map { it.batteryTemp }, "Perangkat" to list.map { it.deviceTemp }),
                listOf(g.warn, g.bad), unit = "°"
            )
        }
        GlassCard {
            Heading("Baterai & beban")
            LineChart(listOf("Baterai" to list.map { it.batteryPercent.toFloat() }), listOf(g.good), minY = 0f, maxY = 100f, unit = "%")
            list.lastOrNull()?.load1?.let { KeyValue("Load average (1 mnt)", it.f1()) }
        }
    }

    GlassCard {
        Heading("Proses")
        Caption(if (rootOk) "Mode root dapat melihat seluruh proses." else "Normal Access hanya melihat proses yang terlihat oleh aplikasi ini.")
        GlassButton("Muat daftar proses", { scope.launch { procs = s.monitor.processes(rootOk) } })
        procs?.let { (list, note) ->
            Caption(note)
            if (list.isEmpty()) Body("Tidak ada data proses yang dapat dibaca.", dim = true)
            LimitedList(list, 25) { p -> KeyValue("${p.name} (${p.pid})", "${(p.rssKb * 1024).formatBytes()} · ${p.user}") }
        }
    }

    if (history.isNotEmpty()) GlassCard {
        Heading("Riwayat")
        Caption(s.history.statsText(history))
        LineChart(
            listOf("CPU" to history.takeLast(300).map { it.cpu }, "RAM" to history.takeLast(300).map { it.ramPercent }),
            listOf(g.accent2, g.accent), minY = 0f, maxY = 100f, unit = "%"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Ekspor CSV", { export.launch("senza-performance.csv") }, Modifier.weight(1f))
            GlassButton("Hapus", { confirmClear = true }, Modifier.weight(1f), danger = true)
        }
    }
}

// ============================================================================ Log

@Composable
private fun LogTab(s: ServiceLocator) {
    val ctx = LocalContext.current
    val entries by s.operationLog.entries.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    if (confirm) ConfirmDialog(
        "Hapus log operasi?", "Seluruh catatan operasi akan dihapus.", "Hapus", true,
        { s.operationLog.clear(); confirm = false }, { confirm = false }
    )
    GlassCard {
        Heading("Log operasi")
        Body("Setiap aksi root dan shell dicatat di sini (hanya di perangkat ini). Isi file tidak dicatat.", dim = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Bagikan", {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, s.operationLog.asText().ifBlank { "(kosong)" })
                }
                ctx.startActivity(Intent.createChooser(send, "Bagikan log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, Modifier.weight(1f), enabled = entries.isNotEmpty())
            GlassButton("Hapus", { confirm = true }, Modifier.weight(1f), danger = true, enabled = entries.isNotEmpty())
        }
    }
    if (entries.isEmpty()) Body("Belum ada operasi tercatat.", dim = true)
    LimitedList(entries.asReversed(), 30) { e ->
        GlassCard(padding = 12.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatusPill(e.mode, if (e.mode == "ROOT") LocalGlass.current.warn else LocalGlass.current.accent2)
                Caption(e.time.formatTime())
            }
            Mono(e.action, size = 11)
            Caption("Hasil: ${e.result}")
        }
    }
}
