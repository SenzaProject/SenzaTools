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
import com.senzaproject.senzatools.platform.root.RootEditor
import com.senzaproject.senzatools.platform.root.RootEditor.ApplyMode
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

private val TABS = listOf("Akses", "File Root", "Shell & ADB", "Monitor", "Performa", "Log")

@Composable
fun ToolsScreen(s: ServiceLocator) {
    var tab by rememberSaveable { mutableStateOf(0) }
    ScreenScaffold("Tools", "Akses, penjelajah file, shell, ADB, monitor performa") {
        ChipRow(TABS, tab) { tab = it }
        when (tab) {
            0 -> AccessTab(s)
            1 -> FilesTab(s)
            2 -> ShellTab(s)
            3 -> MonitorTab(s)
            4 -> PerfTab(s)
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
        Body("• Read-only secara default; edit file hanya lewat Editor File Root (simpan dulu, terapkan manual, backup otomatis)\n• Tidak ada operasi root tersembunyi atau diam-diam\n• Perintah berbahaya (format, tulis ke partisi, hapus sistem) diblokir\n• Seluruh operasi sensitif dicatat", dim = true)
    }
}

// ============================================================================ File Root

private val QUICK_FILES = listOf(
    "/system/build.prop", "/system_ext/build.prop", "/vendor/build.prop", "/product/build.prop", "/odm/build.prop",
    "/tr_product/etc/build.prop", "/system/tr_product/etc/build.prop", "/tr_region/etc/build.prop", "/system/tr_region/etc/build.prop",
    "/tr_system/etc/build.prop", "/system/tr_system/etc/build.prop", "/tr_preload/etc/build.prop", "/system/tr_preload/etc/build.prop",
    "/tr_manifest/etc/build.prop", "/system/tr_manifest/etc/build.prop",
    "/system/etc/permissions/platform.xml", "/vendor/etc/media_codecs.xml", "/vendor/etc/media_codecs_performance.xml",
    "/vendor/etc/audio_policy_configuration.xml", "/system/etc/init/hw/init.rc"
)

private data class FileEntry(val name: String, val isDir: Boolean, val isLink: Boolean, val perm: String, val owner: String, val size: String)

private fun parseLs(out: String): List<FileEntry> = out.lineSequence().mapNotNull { line ->
    val l = line.trim()
    if (l.isEmpty() || l.startsWith("total")) return@mapNotNull null
    val t = l.split(Regex("\\s+"), 8)
    if (t.size < 8 || t[0].length < 10) return@mapNotNull null
    val name = t[7].substringBefore(" -> ")
    if (name == "." || name == "..") return@mapNotNull null
    FileEntry(name, t[0][0] == 'd', t[0][0] == 'l', t[0], "${t[2]}:${t[3]}", t[4])
}.sortedWith(compareBy({ !it.isDir && !it.isLink }, { it.name.lowercase() })).toList()

@Composable
private fun FilesTab(s: ServiceLocator) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/system") }
    var asRoot by remember { mutableStateOf(s.access.isRootVerified) }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var rawOut by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf<Pair<String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<String>?>(null) }
    val rootOk = s.access.isRootVerified
    val editor = s.editor
    val pending by editor.pending.collectAsState()
    var contentIsFile by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var editOrig by remember { mutableStateOf("") }
    var confirmApply by remember { mutableStateOf(false) }
    var applyMsg by remember { mutableStateOf<String?>(null) }
    var lastApply by remember { mutableStateOf<RootEditor.ApplyResult?>(null) }
    var modeIdx by remember { mutableStateOf(0) }
    var quickFound by remember { mutableStateOf<List<String>?>(null) }
    var parts by remember { mutableStateOf<List<RootEditor.PartInfo>?>(null) }
    var overlays by remember { mutableStateOf<List<String>?>(null) }
    val modeNames = listOf("Otomatis", "Langsung", "Overlay Magisk")
    val modes = listOf(ApplyMode.AUTO, ApplyMode.DIRECT, ApplyMode.OVERLAY)

    fun open(p: String, fallbackRead: Boolean = false) {
        scope.launch {
            busy = true; error = null; content = null; found = null
            try {
                val a = SafeAction.ListDir(p)
                val r = s.access.run(a, asRoot)
                val parsed = parseLs(r.output)
                if (fallbackRead && !r.ok && parsed.isEmpty()) {
                    // symlink ke file, bukan direktori: tampilkan isinya
                    val fa = SafeAction.ReadFile(p)
                    val fr = s.access.run(fa, asRoot)
                    contentIsFile = true
                    content = fa.path to fr.output.take(60_000).ifEmpty { "Tidak dapat dibaca (izin ditolak?)" }
                    busy = false
                    return@launch
                }
                path = a.path
                entries = parsed
                rawOut = if (parsed.isEmpty()) r.output.take(2000) else null
                if (!r.ok && parsed.isEmpty()) error = "Tidak dapat membuka direktori (izin ditolak atau tidak ada)."
            } catch (e: IllegalArgumentException) {
                error = e.message
            }
            busy = false
        }
    }

    fun readFile(p: String) {
        scope.launch {
            busy = true; error = null
            try {
                val a = SafeAction.ReadFile(p)
                val r = s.access.run(a, asRoot)
                contentIsFile = true
                content = if (r.output.contains('\u0000')) a.path to "(file biner; tidak ditampilkan)"
                else a.path to r.output.take(60_000).ifEmpty { if (r.ok) "(kosong)" else "Tidak dapat dibaca (izin ditolak?)" }
            } catch (e: IllegalArgumentException) {
                error = e.message
            }
            busy = false
        }
    }

    fun startEdit(p: String) {
        scope.launch {
            busy = true; error = null; applyMsg = null
            val staged = editor.pendingFor(p)
            val r = editor.read(p, asRoot)
            r.onSuccess { orig ->
                editing = p
                editOrig = orig
                editText = staged ?: orig
                content = null
            }.onFailure { error = it.message ?: "Gagal membuka file untuk diedit." }
            busy = false
        }
    }

    fun info(p: String) {
        scope.launch {
            busy = true; error = null
            try {
                val a = SafeAction.Stat(p)
                contentIsFile = false
                content = a.path to s.access.run(a, asRoot).output.take(4000)
            } catch (e: IllegalArgumentException) {
                error = e.message
            }
            busy = false
        }
    }

    GlassCard {
        Heading("Root File Explorer")
        Body("Menjelajah, membaca, mencari, dan melihat info file. File teks (build.prop, XML, rc, dll.) bisa diedit dengan root: Edit → Simpan perubahan → Terapkan perubahan (perangkat restart).", dim = true)
        GlassTextField(path, { path = it }, "Path absolut", mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (asRoot && rootOk) "Mode: Root" else "Mode: Normal", asRoot && rootOk) { if (rootOk) asRoot = !asRoot }
            if (!rootOk) Caption("Root belum aktif (izinkan di root manager)")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Buka", { open(path) }, Modifier.weight(1f), primary = true, enabled = !busy)
            GlassButton("Naik", { open(path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }) }, Modifier.weight(1f), enabled = !busy)
            GlassButton("Info", { info(path) }, Modifier.weight(1f), enabled = !busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(pattern, { pattern = it }, "Cari nama (mis. build.prop, *.rc)", Modifier.weight(1f))
            GlassButton("Cari", {
                scope.launch {
                    busy = true; error = null; content = null
                    try {
                        val a = SafeAction.Find(path, pattern)
                        found = s.access.run(a, asRoot).output.lines().filter { it.isNotBlank() }
                    } catch (e: IllegalArgumentException) {
                        error = e.message
                    }
                    busy = false
                }
            }, enabled = !busy && pattern.isNotBlank())
        }
    }
    GlassCard {
        Heading("Partisi")
        Body("Menampilkan partisi di perangkat, termasuk Transsion (tr_product, tr_region, tr_manifest, tr_system, tr_overlayfs, tr_preload) baik di root maupun di dalam /system (mis. /system/tr_product).", dim = true)
        GlassButton("Pindai partisi", {
            scope.launch {
                busy = true
                parts = editor.listPartitions(asRoot)
                busy = false
            }
        }, Modifier.fillMaxWidth(), primary = true, enabled = !busy)
        parts?.let { list ->
            if (list.isEmpty()) Body("Tidak ada partisi terbaca.", dim = true)
            list.forEach { pi ->
                Column(Modifier.fillMaxWidth().clickableNoRipple { open(pi.path, true) }) {
                    Mono(pi.path, size = 12)
                    Caption("${pi.fs} · ${if (pi.readOnly) "hanya-baca" else "rw"} · mount ${pi.mountPoint}${if (pi.overlayOk) "" else " · overlay lewat bind-mount"}")
                    if (pi.aliases.isNotEmpty()) Caption("juga muncul sebagai: ${pi.aliases.joinToString(", ")}")
                }
            }
            Caption("${list.size} partisi unik (path symlink digabung). Tap partisi untuk membuka isinya, mis. tr_product/etc/build.prop. Partisi erofs/squashfs tidak bisa ditulis; hasil edit dipasang lewat overlay/bind-mount module Magisk/KernelSU/APatch.")
        }
    }

    GlassCard {
        Heading("Edit cepat")
        Body("Pintasan build.prop, XML, dan init.rc umum. Tekan Pindai untuk melihat file yang ada di perangkat ini.", dim = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Pindai file", {
                scope.launch {
                    busy = true
                    quickFound = s.access.existingFiles(QUICK_FILES, asRoot)
                    busy = false
                }
            }, Modifier.weight(1f), primary = true, enabled = !busy)
            GlassButton("Folder etc", { open("/system/etc") }, Modifier.weight(1f), enabled = !busy)
        }
        quickFound?.let { list ->
            if (list.isEmpty()) Body("Tidak ada file pintasan yang ditemukan / terbaca.", dim = true)
            list.forEach { p ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Mono(p, Modifier.weight(1f), 11)
                    GlassButton("Edit", { startEdit(p) }, enabled = !busy)
                }
            }
        }
        Caption("File lain: cari namanya di kolom Cari, buka file, lalu tekan Edit file ini.")
    }

    if (busy) Spinner("Memproses…")
    error?.let { ErrorCard(it) }

    if (confirmApply) ConfirmDialog(
        title = "Terapkan perubahan & restart?",
        message = "${pending.size} file akan diterapkan dengan mode ${modeNames[modeIdx]} (backup otomatis disimpan di aplikasi), lalu perangkat RESTART SENDIRI.\n\n" + pending.joinToString("\n") { "• ${it.path}" } +
            "\n\nKesalahan di file sistem (mis. build.prop atau XML) bisa menyebabkan bootloop. Pastikan kamu punya cara pemulihan (recovery/backup). Simpan pekerjaan di aplikasi lain dulu.",
        confirmText = "Terapkan & restart",
        danger = true,
        onConfirm = {
            confirmApply = false
            scope.launch {
                busy = true
                lastApply = null
                val r = editor.apply(modes[modeIdx]) { applyMsg = it }
                lastApply = r
                applyMsg = buildString {
                    if (r.applied.isNotEmpty()) {
                        append("Berhasil ditulis: ${r.applied.size} file.")
                        if (r.live.isNotEmpty()) append(" ${r.live.size} file sudah terlihat berubah di partisi asli sekarang (diverifikasi dengan cmp).")
                        val notLive = r.applied.size - r.live.size
                        if (notLive > 0) append(" $notLive file baru berlaku setelah restart.")
                        if (r.overlaid.isNotEmpty()) append(" (${r.overlaid.size} lewat overlay/bind-mount module)")
                        append("\n")
                    }
                    if (r.failed.isNotEmpty()) {
                        append("Tidak ada restart karena ada yang gagal:\n")
                        r.failed.forEach { append("• ${it.first}: ${it.second}\n") }
                    }
                    r.rebootError?.let { append(it) }
                }
                busy = false
            }
        },
        onDismiss = { confirmApply = false }
    )

    editing?.let { ep ->
        GlassCard(strong = true) {
            Heading("Edit file")
            Mono(ep, size = 11)
            GlassTextField(editText, { editText = it }, "Isi file", singleLine = false, mono = true, minLines = 12)
            Caption("Perubahan baru disimpan di aplikasi. File sistem tidak disentuh sampai kamu menekan Terapkan perubahan.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Simpan perubahan", {
                    try {
                        editor.stage(ep, editText)
                        editing = null
                        applyMsg = null
                    } catch (e: IllegalArgumentException) {
                        error = e.message
                    }
                }, Modifier.weight(1f), primary = true, enabled = editText != editOrig)
                GlassButton("Batal", { editing = null }, Modifier.weight(1f))
            }
        }
    }

    if (pending.isNotEmpty() || applyMsg != null) GlassCard(strong = true) {
        Heading("Perubahan tertunda (${pending.size})")
        Body("Sudah disimpan di aplikasi, belum diterapkan. Setelah diterapkan, perubahan langsung terlihat di path aslinya (diverifikasi) dan dipertahankan setelah restart.", dim = true)
        pending.forEach { p ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Mono(p.path, Modifier.weight(1f), 11)
                GlassButton("Edit", { startEdit(p.path) }, enabled = !busy)
                GlassButton("Buang", { editor.discard(p.path) }, danger = true, enabled = !busy)
            }
        }
        if (pending.isNotEmpty()) {
            Caption("Mode terapkan")
            ChipRow(modeNames, modeIdx) { modeIdx = it }
            Caption(when (modeIdx) {
                0 -> "Otomatis: tulis langsung; bila partisi erofs/hanya-baca, otomatis pakai overlay Magisk."
                1 -> "Langsung: tulis ke partisi asli (remount rw). Gagal di partisi erofs."
                else -> "Overlay: partisi asli tidak disentuh. File ditaruh di module systemless (/data/adb/modules/senza_edit), butuh Magisk/KernelSU/APatch."
            })
            GlassButton("Terapkan perubahan (restart)", { confirmApply = true }, Modifier.fillMaxWidth(), primary = true, danger = true, enabled = rootOk && !busy)
            if (!rootOk) Caption("Root belum aktif. Izinkan di root manager, lalu tekan Minta izin root di tab Akses.")
        }
        applyMsg?.let { Body(it) }
        val la = lastApply
        if (la != null && la.applied.isNotEmpty() && !busy) {
            GlassButton("Restart sekarang", {
                scope.launch {
                    busy = true
                    val rr = s.access.rebootDevice()
                    if (!rr.ok) error = "Perintah restart gagal: ${rr.output.take(120)}"
                    busy = false
                }
            }, Modifier.fillMaxWidth(), primary = true, danger = true, enabled = rootOk)
        }
    }

    if (rootOk) ExpandableCard("Overlay Magisk (hasil edit)", false, trailing = overlays?.size?.toString()) {
        Caption("File yang saat ini di-overlay oleh Senza Tools. Menghapus overlay langsung mengembalikan file asli (bind-mount dilepas) dan tidak dipasang lagi saat boot.")
        GlassButton("Muat daftar overlay", { scope.launch { overlays = editor.overlays() } }, enabled = !busy)
        overlays?.let { list ->
            if (list.isEmpty()) Body("Belum ada overlay.", dim = true)
            list.forEach { p ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Mono(p, Modifier.weight(1f), 11)
                    GlassButton("Hapus", {
                        scope.launch {
                            val ok = editor.removeOverlay(p)
                            if (!ok) error = "Gagal menghapus overlay."
                            overlays = editor.overlays()
                        }
                    }, danger = true, enabled = !busy)
                }
            }
        }
        Caption("Jika terjadi bootloop setelah overlay: nonaktifkan/hapus module \"Senza Tools Edit Overlay\" lewat recovery atau mode aman Magisk.")
    }

    content?.let { (title, body) ->
        GlassCard(strong = true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Mono(title, Modifier.weight(1f), 11)
                GlassButton("Tutup", { content = null })
            }
            if (contentIsFile && rootOk && RootEditor.isEditable(title)) {
                GlassButton("Edit file ini", { startEdit(title) }, enabled = !busy)
            }
            SelectionContainer { Mono(body, size = 11) }
            if (body.length >= 60_000) Caption("Ditampilkan sebagian.")
            Caption("Isi file bisa memuat data sensitif. Jangan bagikan tanpa memeriksanya.")
        }
    }
    found?.let { list ->
        ExpandableCard("Hasil pencarian", true, trailing = "${list.size}") {
            if (list.isEmpty()) Body("Tidak ada hasil.", dim = true)
            LimitedList(list, 40) { p -> Mono(p, Modifier.clickableNoRipple { readFile(p) }, 11) }
        }
    }
    entries?.let { list ->
        ExpandableCard(path, true, trailing = "${list.size} item") {
            if (list.isEmpty()) Body(rawOut ?: "Kosong.", dim = true)
            LimitedList(list, 60) { e ->
                val full = (if (path == "/") "" else path.trimEnd('/')) + "/" + e.name
                Column(Modifier.fillMaxWidth().clickableNoRipple { if (e.isDir) open(full) else if (e.isLink) open(full, true) else readFile(full) }) {
                    Mono((if (e.isDir) "▸ " else if (e.isLink) "↪ " else "  ") + e.name, size = 12)
                    Caption("${e.perm} ${e.owner} ${e.size}")
                }
            }
        }
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
