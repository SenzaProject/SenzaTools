package com.senzaproject.senzaai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.senzaproject.senzaai.di.ServiceLocator
import com.senzaproject.senzaai.platform.root.PerfTuner
import com.senzaproject.senzaai.ui.components.*
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
        msg = if (o.failures.isEmpty()) "$label: perintah dijalankan (${o.commands.size}). Nilai di bawah sudah dibaca ulang; kernel/thermal bisa saja membatasi."
        else "$label: ada yang gagal:\n" + o.failures.joinToString("\n") { "• $it" }
    }

    fun profile(p: PerfTuner.Profile) {
        scope.launch {
            busy = true; msg = null
            if (cpu == null || !gpuScanned) refresh()
            val node = nodes?.getOrNull(nodeIdx)
            val eff = if (p.bypass) p.copy(bypass = bypassInGame) else p
            val o = perf.applyProfile(eff, cpu.orEmpty(), gpu, node)
            refresh()
            report(p.name, o)
            busy = false
        }
    }

    GlassCard(strong = true) {
        Heading("Performa CPU & GPU")
        Body("Atur frekuensi, pilih profil, bypass charger, dan driver grafis. Perubahan berlaku langsung dan kembali normal setelah restart, kecuali \"Terapkan saat boot\" diaktifkan. Dukungan tergantung SoC/kernel; hasil selalu dibaca ulang dari sistem.", dim = true)
        if (!rootOk) Caption("Root belum diverifikasi: verifikasi di tab Akses agar bisa mengubah pengaturan.")
    }
    msg?.let { GlassCard { Body(it) } }
    if (busy) Spinner("Memproses…")

    // ---------------------------------------------------------------- Profil
    GlassCard {
        Heading("Profil")
        Caption("Terakhir diterapkan: ${perf.lastProfile.ifEmpty { "belum ada" }}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Game", { profile(PerfTuner.GAME) }, Modifier.weight(1f), primary = true, enabled = rootOk && !busy)
            GlassButton("Seimbang", { profile(PerfTuner.BALANCED) }, Modifier.weight(1f), enabled = rootOk && !busy)
            GlassButton("Hemat", { profile(PerfTuner.SAVER) }, Modifier.weight(1f), enabled = rootOk && !busy)
        }
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
                        refresh()
                        report(pol.name, o)
                        busy = false
                    }
                }
            }
            if (list.isNotEmpty()) Caption("Beberapa SoC MediaTek juga membatasi lewat PPM/thermal sehingga nilai bisa kembali sendiri; cek hasil bacaan ulang.")
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
                refresh()
                report("GPU", o)
                busy = false
            }
        } }
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
    var minI by remember(pol) { mutableStateOf(freqs.indexOf(pol.curMin).coerceAtLeast(0)) }
    var maxI by remember(pol) { mutableStateOf(freqs.indexOf(pol.curMax).let { if (it < 0) freqs.lastIndex else it }) }
    var govI by remember(pol) { mutableStateOf(pol.govs.indexOf(pol.gov).coerceAtLeast(0)) }
    val labels = freqs.map { "${it / 1000}" }

    Heading("${pol.name} (cpu ${pol.cpus})")
    Caption("Sekarang: ${pol.curMin / 1000}–${pol.curMax / 1000} MHz · governor ${pol.gov.ifEmpty { "?" }}")
    Caption("Minimum (MHz)")
    ChipRow(labels, minI) { minI = it; if (maxI < minI) maxI = minI }
    Caption("Maksimum (MHz)")
    ChipRow(labels, maxI) { maxI = it; if (minI > maxI) minI = maxI }
    if (pol.govs.isNotEmpty()) {
        Caption("Governor")
        ChipRow(pol.govs, govI) { govI = it }
    }
    GlassButton("Terapkan ${pol.name}", { onApply(freqs[minI], freqs[maxI], pol.govs.getOrNull(govI)) }, Modifier.fillMaxWidth(), primary = true, enabled = enabled)
}

@Composable
private fun GpuEditor(g: PerfTuner.GpuInfo, enabled: Boolean, onApply: (Long, Long, Long?) -> Unit) {
    val freqs = g.freqs
    val labels = freqs.map { "${it / g.div}" }
    var minI by remember(g) { mutableStateOf(g.curMin?.let { freqs.indexOf(it) }?.coerceAtLeast(0) ?: 0) }
    var maxI by remember(g) { mutableStateOf(g.curMax?.let { freqs.indexOf(it) }?.takeIf { it >= 0 } ?: freqs.lastIndex) }
    var lockI by remember(g) { mutableStateOf(freqs.lastIndex) }

    Caption("Antarmuka: ${if (g.kind == "DEVFREQ") "devfreq ${g.base}" else "MediaTek /proc/gpufreq (kunci frekuensi)"}")
    if (g.kind == "DEVFREQ") {
        Caption("Sekarang: ${(g.curMin ?: 0) / g.div}–${(g.curMax ?: 0) / g.div} MHz${g.gov?.let { " · governor $it" } ?: ""}")
        Caption("Minimum (MHz)")
        ChipRow(labels, minI) { minI = it; if (maxI < minI) maxI = minI }
        Caption("Maksimum (MHz)")
        ChipRow(labels, maxI) { maxI = it; if (minI > maxI) minI = maxI }
        GlassButton("Terapkan GPU", { onApply(freqs[minI], freqs[maxI], null) }, Modifier.fillMaxWidth(), primary = true, enabled = enabled)
    } else {
        Caption("Kunci frekuensi GPU (MHz)")
        ChipRow(labels, lockI) { lockI = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Kunci", { onApply(freqs.first(), freqs.last(), freqs[lockI]) }, Modifier.weight(1f), primary = true, enabled = enabled)
            GlassButton("Lepas kunci", { onApply(freqs.first(), freqs.last(), null) }, Modifier.weight(1f), enabled = enabled)
        }
    }
}
