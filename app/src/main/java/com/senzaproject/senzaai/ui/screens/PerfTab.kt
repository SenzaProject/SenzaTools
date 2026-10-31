package com.senzaproject.senzaai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.senzaproject.senzaai.di.ServiceLocator
import com.senzaproject.senzaai.platform.root.PerfTuner
import com.senzaproject.senzaai.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PerfTab(s: ServiceLocator) {
    val scope = rememberCoroutineScope()
    val perf = s.perf
    val rootOk = s.access.isRootVerified

    var cpu by remember { mutableStateOf<List<PerfTuner.CpuPolicy>?>(null) }
    var gpu by remember { mutableStateOf<PerfTuner.GpuInfo?>(null) }
    var gpuScanned by remember { mutableStateOf(false) }
    var nodes by remember { mutableStateOf<List<PerfTuner.BypassNode>?>(null) }
    var nodeIdx by remember { mutableStateOf(0) }
    var bypassInGame by remember { mutableStateOf(true) }
    var bootOn by remember { mutableStateOf<Boolean?>(null) }
    var guardOn by remember { mutableStateOf(perf.guardOn) }
    var guardRun by remember { mutableStateOf<Boolean?>(null) }
    var thermalOff by remember { mutableStateOf(perf.ignoreThermal) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    var cPath by remember { mutableStateOf(perf.customNode?.path ?: "") }
    var cOn by remember { mutableStateOf(perf.customNode?.on ?: "1") }
    var cOff by remember { mutableStateOf(perf.customNode?.off ?: "0") }

    var drv by remember { mutableStateOf<Map<String, String>?>(null) }
    var rendererIdx by remember { mutableStateOf(0) }
    var pkg by remember { mutableStateOf("") }
    var angleIdx by remember { mutableStateOf(0) }

    suspend fun refresh() {
        cpu = perf.readCpu(rootOk)
        gpu = perf.readGpu(rootOk)
        gpuScanned = true
    }

    fun report(label: String, o: PerfTuner.Outcome) {
        guardOn = perf.guardOn
        msg = if (o.failures.isEmpty()) "$label: diterapkan dan diperiksa. " +
            (if (guardOn) "Penjaga frekuensi aktif: nilai ditulis ulang otomatis bila ada yang menurunkannya." else "Penjaga frekuensi mati: nilai bisa berubah lagi oleh sistem.")
        else "$label: nilai ditolak/dikembalikan kernel:\n" + o.failures.joinToString("\n") { "• $it" } +
            "\nKemungkinan dibatasi thermal atau oleh kernel; coba frekuensi lebih rendah atau cek suhu."
    }

    fun profile(p: PerfTuner.Profile) {
        scope.launch {
            busy = true; msg = null
            if (cpu == null || !gpuScanned) refresh()
            val node = nodes?.getOrNull(nodeIdx)
            val eff = if (p.bypass) p.copy(bypass = bypassInGame) else p
            val o = perf.applyProfile(eff, cpu.orEmpty(), gpu, node)
            delay(800)
            refresh()
            report(p.name, o)
            busy = false
        }
    }

    GlassCard(strong = true) {
        Heading("Performa CPU & GPU")
        Body("Atur frekuensi, pilih profil, bypass charger, dan driver grafis. Perubahan berlaku langsung dan kembali normal setelah restart, kecuali \"Terapkan saat boot\" diaktifkan. Dukungan tergantung SoC/kernel; hasil selalu dibaca ulang dari sistem.", dim = true)
        if (!rootOk) Caption("Root belum aktif. Izinkan Senza Tools di root manager, lalu tekan Minta izin root di Tools → Akses.")
    }
    msg?.let { GlassCard { Body(it) } }
    if (busy) Spinner("Memproses…")

    // ---------------------------------------------------------------- Penjaga frekuensi
    GlassCard {
        Heading("Penjaga frekuensi (anti-turun)")
        Body("Frekuensi yang kamu atur dikunci dan dijaga terus: tiap 3 detik nilainya diperiksa dan ditulis ulang bila governor, PPM, atau HAL performa vendor menurunkannya. Aktif otomatis saat kamu menerapkan profil atau frekuensi.", dim = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (guardOn) "Penjaga: AKTIF" else "Penjaga: MATI", guardOn) {
                scope.launch {
                    busy = true; msg = null
                    val o = perf.setGuard(!guardOn)
                    guardRun = perf.guardRunning()
                    report("Penjaga frekuensi", o)
                    busy = false
                }
            }
            GlassButton("Cek status", { scope.launch { guardRun = perf.guardRunning() } }, enabled = rootOk && !busy)
        }
        guardRun?.let { Caption(if (it) "Proses penjaga sedang berjalan." else "Proses penjaga tidak berjalan.") }
        Caption("Mode Seimbang mematikan penjaga dan mengembalikan rentang frekuensi penuh.")
    }

    // ---------------------------------------------------------------- Profil
    GlassCard {
        Heading("Profil")
        Caption("Terakhir diterapkan: ${perf.lastProfile.ifEmpty { "belum ada" }}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Game", { profile(PerfTuner.GAME) }, Modifier.weight(1f), primary = true, enabled = rootOk && !busy)
            GlassButton("Seimbang", { profile(PerfTuner.BALANCED) }, Modifier.weight(1f), enabled = rootOk && !busy)
            GlassButton("Hemat", { profile(PerfTuner.SAVER) }, Modifier.weight(1f), enabled = rootOk && !busy)
        }
        GlassButton("Maksimal: kunci CPU & GPU di frekuensi tertinggi", { profile(PerfTuner.MAX) }, Modifier.fillMaxWidth(), enabled = rootOk && !busy)
        Caption("Game: frekuensi minimum dinaikkan, governor performa, GPU dikunci tinggi. Seimbang: rentang penuh, governor standar. Hemat: batas atas CPU sekitar 60% dan GPU sekitar 50%.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Bypass charger di Mode Game", bypassInGame) { bypassInGame = !bypassInGame }
        }
        Caption("Bypass hanya jalan bila node charger sudah dideteksi dan dipilih di kartu Bypass charger di bawah.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (bootOn == true) "Terapkan saat boot: AKTIF" else "Terapkan saat boot", bootOn == true) {
                scope.launch {
                    busy = true
                    if (bootOn == true) {
                        bootOn = !perf.removeBootScript()
                        msg = if (bootOn == false) "Profil boot dihapus." else "Gagal menghapus profil boot."
                    } else {
                        val err = perf.installBootScript()
                        bootOn = err == null
                        msg = err ?: "Pengaturan CPU/GPU terakhir akan diterapkan otomatis setiap boot (module systemless, bypass charger tidak ikut)."
                    }
                    busy = false
                }
            }
            GlassButton("Cek status", { scope.launch { bootOn = perf.bootScriptInstalled() } }, enabled = rootOk && !busy)
        }
    }

    // ---------------------------------------------------------------- CPU
    GlassCard {
        Heading("Frekuensi CPU")
        GlassButton("Baca CPU", { scope.launch { busy = true; refresh(); busy = false } }, Modifier.fillMaxWidth(), primary = true, enabled = !busy)
        cpu?.let { list ->
            if (list.isEmpty()) Body("cpufreq tidak terbaca (butuh root atau kernel tidak mengekspos policy).", dim = true)
            list.forEach { pol ->
                GlassDivider()
                CpuPolicyEditor(pol, rootOk && !busy) { min, max, gov ->
                    scope.launch {
                        busy = true; msg = null
                        val o = perf.setCpu(pol, min, max, gov)
                        delay(800)
                        refresh()
                        report(pol.name, o)
                        busy = false
                    }
                }
            }
            if (list.isNotEmpty()) Caption("Pakai Kunci (min = max) untuk menahan frekuensi tepat di angka itu. Beberapa SoC MediaTek membatasi lewat PPM/thermal; penjaga akan menulis ulang nilainya, tapi batas thermal tetap bisa menurunkan frekuensi demi keamanan.")
        }
    }

    // ---------------------------------------------------------------- GPU
    GlassCard {
        Heading("Frekuensi GPU")
        if (!gpuScanned) Caption("Tekan Baca CPU di atas untuk memindai CPU dan GPU sekaligus.")
        gpu?.let { g -> GpuEditor(g, rootOk && !busy) { min, max, lock ->
            scope.launch {
                busy = true; msg = null
                val o = perf.setGpu(g, min, max, lock)
                delay(800)
                refresh()
                report("GPU", o)
                busy = false
            }
        } }
        if (gpu?.kind == "MTK") {
            Chip(if (thermalOff) "Abaikan limit thermal GPU: AKTIF" else "Abaikan limit thermal GPU", thermalOff) {
                scope.launch {
                    busy = true; msg = null
                    val o = perf.setThermalIgnore(!thermalOff)
                    thermalOff = perf.ignoreThermal
                    report("Limit thermal GPU", o)
                    busy = false
                }
            }
            Caption("Hanya bila GPU turun karena limit thermal. Berisiko perangkat panas; matikan lagi bila suhu tinggi.")
        }
        if (gpuScanned && gpu == null) Body("Antarmuka frekuensi GPU tidak ditemukan (devfreq atau /proc/gpufreq tidak tersedia/terbaca).", dim = true)
    }

    // ---------------------------------------------------------------- Bypass charger
    GlassCard {
        Heading("Bypass charger")
        Body("Menghentikan pengisian baterai saat main game (node berbeda tiap perangkat). Charger harus terpasang; efeknya bergantung IC charger dan bisa berupa berhenti mengisi, bukan pass-through penuh. Biasanya kembali normal saat dicabut atau restart.", dim = true)
        GlassButton("Deteksi node", { scope.launch { busy = true; nodes = perf.detectBypass(rootOk); nodeIdx = 0; busy = false } }, Modifier.fillMaxWidth(), enabled = !busy)
        nodes?.let { list ->
            if (list.isEmpty()) Body("Tidak ada node bypass bawaan yang terdeteksi. Isi node kustom di bawah bila kamu tahu path-nya untuk perangkat ini.", dim = true)
            else {
                ChipRow(list.map { it.label }, nodeIdx) { nodeIdx = it }
                val n = list.getOrNull(nodeIdx)
                if (n != null) {
                    Mono(n.path, size = 11)
                    Caption("Aktif = ${n.on}, normal = ${n.off}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("Aktifkan bypass", {
                            scope.launch { busy = true; val o = perf.setBypass(n, true); report("Bypass ON", o); busy = false }
                        }, Modifier.weight(1f), primary = true, enabled = rootOk && !busy)
                        GlassButton("Normal", {
                            scope.launch { busy = true; val o = perf.setBypass(n, false); report("Bypass OFF", o); busy = false }
                        }, Modifier.weight(1f), enabled = rootOk && !busy)
                    }
                }
            }
        }
        GlassDivider()
        Caption("Node kustom (path di /sys atau /proc)")
        GlassTextField(cPath, { cPath = it }, "Path node", mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassTextField(cOn, { cOn = it }, "Nilai aktif", Modifier.weight(1f), mono = true)
            GlassTextField(cOff, { cOff = it }, "Nilai normal", Modifier.weight(1f), mono = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Simpan node", {
                perf.customNode = if (cPath.isBlank()) null else PerfTuner.BypassNode(cPath.trim(), cOn.trim(), cOff.trim(), "kustom")
                msg = if (cPath.isBlank()) "Node kustom dihapus." else "Node kustom disimpan. Tekan Deteksi node."
            }, Modifier.weight(1f))
        }
    }

    // ---------------------------------------------------------------- Driver
    GlassCard {
        Heading("Driver grafis")
        Body("Renderer UI (HWUI) dan pilihan driver OpenGL per aplikasi (ANGLE atau native). Mengganti driver vendor/Vulkan kustom dilakukan lewat module Magisk; di sini hanya pilihan yang disediakan Android.", dim = true)
        GlassButton("Baca info driver", { scope.launch { busy = true; drv = perf.readDriverInfo(rootOk); busy = false } }, Modifier.fillMaxWidth(), enabled = !busy)
        drv?.let { m ->
            listOf("ro.board.platform" to "Platform", "ro.hardware.egl" to "EGL", "ro.hardware.vulkan" to "Vulkan",
                "ro.opengles.version" to "OpenGL ES", "debug.hwui.renderer" to "Renderer HWUI", "angle_pkgs" to "ANGLE paket", "angle_vals" to "ANGLE nilai"
            ).forEach { (k, label) -> KeyValue(label, m[k]?.ifBlank { null } ?: "-") }
        }
        GlassDivider()
        Caption("Renderer UI (berlaku untuk aplikasi yang dibuka setelahnya)")
        ChipRow(listOf("Default", "OpenGL", "Vulkan"), rendererIdx) { rendererIdx = it }
        GlassButton("Terapkan renderer", {
            scope.launch {
                busy = true
                val o = perf.setRenderer(listOf("", "skiagl", "skiavk")[rendererIdx])
                report("Renderer", o)
                drv = perf.readDriverInfo(rootOk)
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = rootOk && !busy)
        GlassDivider()
        Caption("Driver OpenGL per aplikasi (Android 10+)")
        GlassTextField(pkg, { pkg = it }, "Nama paket, mis. com.mobile.legends", mono = true)
        ChipRow(listOf("ANGLE", "Native", "Hapus pilihan"), angleIdx) { angleIdx = it }
        GlassButton("Terapkan driver", {
            scope.launch {
                busy = true
                try {
                    val cur = drv ?: perf.readDriverInfo(rootOk)
                    val o = perf.setAngle(pkg.trim(), listOf("angle", "native", null)[angleIdx], cur["angle_pkgs"].orEmpty(), cur["angle_vals"].orEmpty())
                    report("Driver $pkg", o)
                    drv = perf.readDriverInfo(rootOk)
                } catch (e: IllegalArgumentException) {
                    msg = e.message
                }
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = rootOk && !busy && pkg.isNotBlank())
        Caption("Mulai ulang game setelah mengganti driver. Dukungan ANGLE tergantung ROM.")
    }
}

@Composable
private fun CpuPolicyEditor(pol: PerfTuner.CpuPolicy, enabled: Boolean, onApply: (Long, Long, String?) -> Unit) {
    val freqs = pol.freqs
    var minI by remember(pol.path, pol.curMin, pol.curMax) { mutableStateOf(freqs.indexOf(pol.curMin).coerceAtLeast(0)) }
    var maxI by remember(pol.path, pol.curMin, pol.curMax) { mutableStateOf(freqs.indexOf(pol.curMax).let { if (it < 0) freqs.lastIndex else it }) }
    var govI by remember(pol.path, pol.gov) { mutableStateOf(pol.govs.indexOf(pol.gov).coerceAtLeast(0)) }
    var lock by remember(pol.path) { mutableStateOf(pol.curMin == pol.curMax) }
    val labels = freqs.map { "${it / 1000}" }

    Heading("${pol.name} (cpu ${pol.cpus})")
    Caption("Sekarang: ${pol.curMin / 1000}–${pol.curMax / 1000} MHz · governor ${pol.gov.ifEmpty { "?" }}" +
        (pol.cur?.let { " · aktual ${it / 1000} MHz" } ?: ""))
    Caption("Minimum (MHz)")
    ChipRow(labels, minI) { minI = it; if (maxI < minI) maxI = minI }
    Caption("Maksimum (MHz)")
    ChipRow(labels, maxI) { maxI = it; if (minI > maxI) minI = maxI }
    if (pol.govs.isNotEmpty()) {
        Caption("Governor")
        ChipRow(pol.govs, govI) { govI = it }
    }
    Chip(if (lock) "Kunci (min = max): AKTIF" else "Kunci (min = max)", lock) { lock = !lock }
    if (lock) Caption("Frekuensi ditahan tepat di angka Maksimum yang dipilih.")
    GlassButton("Terapkan ${pol.name}", {
        val mx = freqs[maxI]
        onApply(if (lock) mx else freqs[minI], mx, pol.govs.getOrNull(govI))
    }, Modifier.fillMaxWidth(), primary = true, enabled = enabled)
}

@Composable
private fun GpuEditor(g: PerfTuner.GpuInfo, enabled: Boolean, onApply: (Long, Long, Long?) -> Unit) {
    val freqs = g.freqs
    val labels = freqs.map { "${it / g.div}" }
    var minI by remember(g.base, g.curMin, g.curMax) { mutableStateOf(g.curMin?.let { freqs.indexOf(it) }?.coerceAtLeast(0) ?: 0) }
    var maxI by remember(g.base, g.curMin, g.curMax) { mutableStateOf(g.curMax?.let { freqs.indexOf(it) }?.takeIf { it >= 0 } ?: freqs.lastIndex) }
    var lockI by remember(g.base) { mutableStateOf(freqs.lastIndex) }
    var lock by remember(g.base) { mutableStateOf(g.curMin != null && g.curMin == g.curMax) }

    Caption("Antarmuka: ${if (g.kind == "DEVFREQ") "devfreq ${g.base}" else "MediaTek /proc/gpufreq (kunci frekuensi)"}")
    if (g.kind == "DEVFREQ") {
        Caption("Sekarang: ${(g.curMin ?: 0) / g.div}–${(g.curMax ?: 0) / g.div} MHz${g.gov?.let { " · governor $it" } ?: ""}" +
            (g.cur?.let { " · aktual ${it / g.div} MHz" } ?: ""))
        Caption("Minimum (MHz)")
        ChipRow(labels, minI) { minI = it; if (maxI < minI) maxI = minI }
        Caption("Maksimum (MHz)")
        ChipRow(labels, maxI) { maxI = it; if (minI > maxI) minI = maxI }
        Chip(if (lock) "Kunci (min = max): AKTIF" else "Kunci (min = max)", lock) { lock = !lock }
        if (lock) Caption("Frekuensi GPU ditahan tepat di angka Maksimum yang dipilih.")
        GlassButton("Terapkan GPU", {
            val mx = freqs[maxI]
            onApply(if (lock) mx else freqs[minI], mx, null)
        }, Modifier.fillMaxWidth(), primary = true, enabled = enabled)
    } else {
        Caption("Kunci frekuensi GPU (MHz)")
        ChipRow(labels, lockI) { lockI = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Kunci", { onApply(freqs.first(), freqs.last(), freqs[lockI]) }, Modifier.weight(1f), primary = true, enabled = enabled)
            GlassButton("Lepas kunci", { onApply(freqs.first(), freqs.last(), null) }, Modifier.weight(1f), enabled = enabled)
        }
    }
}
