package com.senzaproject.senzatools.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.data.BatteryHealthInfo
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.domain.model.InfoRow
import com.senzaproject.senzatools.domain.model.InfoSection
import com.senzaproject.senzatools.ui.components.Body
import com.senzaproject.senzatools.ui.components.Caption
import com.senzaproject.senzatools.ui.components.Chip
import com.senzaproject.senzatools.ui.components.ChipRow
import com.senzaproject.senzatools.ui.components.ExpandableCard
import com.senzaproject.senzatools.ui.components.GlassButton
import com.senzaproject.senzatools.ui.components.GlassCard
import com.senzaproject.senzatools.ui.components.Heading
import com.senzaproject.senzatools.ui.components.InfoSectionCard
import com.senzaproject.senzatools.ui.components.KeyValue
import com.senzaproject.senzatools.ui.components.Mono
import com.senzaproject.senzatools.ui.components.ScreenScaffold
import com.senzaproject.senzatools.ui.components.Spinner
import com.senzaproject.senzatools.ui.components.StatusPill
import com.senzaproject.senzatools.ui.components.Title
import com.senzaproject.senzatools.ui.theme.LocalGlass
import com.senzaproject.senzatools.utils.f1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val TABS = listOf("Perangkat", "CPU", "GPU", "Memori", "Baterai", "Layar", "Jaringan")
private val LIVE_TABS = setOf(1, 3, 4, 5)

@Composable
fun DeviceScreen(s: ServiceLocator) {
    var tab by remember { mutableStateOf(0) }
    val sections by produceState<List<InfoSection>?>(null, tab) {
        value = null
        while (true) {
            value = withContext(Dispatchers.IO) {
                try {
                    when (tab) {
                        0 -> s.device.deviceSections()
                        1 -> s.device.cpuSections()
                        2 -> s.device.gpuSections()
                        3 -> s.device.memorySections() + s.device.storageSections()
                        4 -> s.device.batterySections()
                        5 -> s.device.displaySections()
                        else -> s.device.networkSections()
                    }
                } catch (t: Throwable) {
                    listOf(InfoSection("Gagal membaca data", listOf(InfoRow("Alasan", t.message ?: t.javaClass.simpleName))))
                }
            }
            if (tab in LIVE_TABS) delay(3000) else break
        }
    }

    ScreenScaffold("Device", "Informasi perangkat dari API Android standar (Normal Access)") {
        ChipRow(TABS, tab) { tab = it }
        if (tab == 4) BatteryHealthCard(s)
        if (tab == 5) RefreshRateCard(s)
        val list = sections
        if (list == null) Spinner("Membaca…") else list.forEach { InfoSectionCard(it) }
        if (tab == 6) NetworkDiagnostics(s)
        if (tab in LIVE_TABS) Caption("Data diperbarui otomatis setiap 3 detik selama tab ini terbuka.")
        Caption("Nilai \"Tidak tersedia\" berarti perangkat atau SELinux tidak mengizinkan membacanya pada Normal Access; bukan tebakan.")
    }
}

// ----------------------------------------------------------------------------- Kesehatan baterai

private fun mahText(v: Float?): String = v?.let { "${it.roundToInt()} mAh" } ?: "Tidak tersedia"

@Composable
private fun BatteryHealthCard(s: ServiceLocator) {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    val scope = rememberCoroutineScope()
    val rootOk = s.access.isRootVerified
    var info by remember { mutableStateOf<BatteryHealthInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    suspend fun load() {
        busy = true
        info = s.batteryHealth.read(rootOk)
        busy = false
    }
    // Baca ulang otomatis begitu root disetujui (root diminta async saat aplikasi dibuka).
    LaunchedEffect(rootOk) { load() }

    GlassCard(strong = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Heading("Kesehatan baterai")
            val i = info
            if (i != null) StatusPill(if (i.viaRoot) "Sumber: root" else "Sumber: normal", if (i.viaRoot) g.warn else g.good)
        }
        val i = info
        if (i == null) {
            Spinner("Membaca node baterai…")
        } else {
            val pr = i.primary
            if (pr != null) {
                Title("${pr.percent.f1()} %")
                Caption("Dihitung dari ${pr.label} · ${pr.detail}")
            } else {
                Body(i.missing ?: "Tidak tersedia", dim = true)
            }
            KeyValue("Kapasitas penuh (aktual)", mahText(i.fullMah))
            KeyValue("Kapasitas desain", mahText(i.designMah))
            KeyValue("Siklus pengisian", i.cycles?.let { c -> "$c (${i.cycleSource})" } ?: "Tidak tersedia")
            KeyValue("Status kesehatan (sistem)", i.statusText ?: "Tidak tersedia")
            i.others.forEach { o -> KeyValue("Pembanding: ${o.label}", "${o.percent.f1()} %") }
            i.notes.forEach { n -> Caption(n) }
            Caption("Semua angka dibaca dari node sysfs / dumpsys / API sistem, bukan diperkirakan. Buka \"Bukti mentah\" di bawah untuk mencocokkan dengan Termux.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(if (busy) "Membaca…" else "Baca ulang", { scope.launch { load() } }, enabled = !busy)
                GlassButton(if (copied) "Perintah disalin" else "Salin perintah Termux", {
                    copyText(ctx, "termux", i.verifyCommand)
                    copied = true
                })
            }
        }
    }
    info?.let { i ->
        ExpandableCard("Bukti mentah (cocokkan dengan Termux)", false, trailing = "${i.evidence.size}") {
            if (i.evidence.isEmpty()) Body("Tidak ada node baterai yang bisa dibaca.", dim = true)
            i.evidence.forEach { KeyValue(it.label, it.value) }
            Caption("Jalankan di Termux (butuh root) untuk nilai mentah yang sama:")
            Mono(i.verifyCommand, size = 11)
        }
    }
}

// ----------------------------------------------------------------------------- Kunci refresh rate

@Composable
private fun RefreshRateCard(s: ServiceLocator) {
    val d = s.display
    val scope = rememberCoroutineScope()
    val rootOk = s.access.isRootVerified
    val supported = remember { d.supportedHz() }
    var locked by remember { mutableStateOf(d.lockedHz) }
    var cur by remember { mutableStateOf(d.currentHz()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var guardRun by remember { mutableStateOf<Boolean?>(null) }
    var bootOn by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            cur = d.currentHz()
            delay(2000)
        }
    }

    fun lock(hz: Int) {
        scope.launch {
            busy = true; msg = null
            val o = d.lock(hz)
            locked = d.lockedHz
            delay(1500)
            cur = d.currentHz()
            val shown = cur?.roundToInt()
            msg = buildString {
                if (o.failures.isEmpty()) append("Pengaturan sistem diterapkan: min & peak refresh rate = $hz Hz. ")
                else append("Gagal menulis: ${o.failures.joinToString("; ")}. ")
                if (shown == null) append("Refresh rate aktual tidak terbaca dari layar.")
                else if (abs(shown - hz) <= 1) append("Layar melaporkan $shown Hz: kunci bekerja.")
                else append("Tetapi layar melaporkan $shown Hz. Firmware/ROM kemungkinan menimpa pengaturan (mode hemat daya, thermal, atau mode adaptif). Penjaga menulis ulang tiap 10 detik; cek lagi beberapa detik lagi.")
                if (hz !in supported && supported.isNotEmpty()) append(" Catatan: layar ini tidak melaporkan $hz Hz sebagai mode yang didukung.")
            }
            guardRun = d.guardRunning()
            busy = false
        }
    }

    GlassCard(strong = true) {
        Heading("Kunci refresh rate")
        Body("Kunci layar di 60, 90, atau 120 Hz lewat root. Berguna bila pengaturan refresh rate bawaan tidak bekerja (sering akibat bug pembaruan firmware). Penjaga memeriksa dan menulis ulang pengaturan tiap 10 detik bila firmware menimpanya.", dim = true)
        if (!rootOk) Caption("Butuh root. Izinkan di root manager, lalu tekan Minta izin root di Tools → Akses.")
        KeyValue("Refresh rate layar sekarang", cur?.let { "${it.roundToInt()} Hz" } ?: "Tidak tersedia")
        KeyValue("Kunci Senza Tools", if (locked > 0) "$locked Hz" else "Tidak aktif (otomatis)")
        KeyValue("Didukung layar", if (supported.isEmpty()) "Tidak tersedia" else supported.joinToString(", ") { "$it Hz" })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60, 90, 120).forEach { hz ->
                GlassButton("$hz Hz", { lock(hz) }, Modifier.weight(1f), primary = locked == hz, enabled = rootOk && !busy)
            }
        }
        GlassButton("Lepas kunci (kembali ke pengaturan awal)", {
            scope.launch {
                busy = true; msg = null
                val o = d.unlock()
                locked = d.lockedHz
                bootOn = false
                guardRun = false
                msg = if (o.failures.isEmpty()) "Kunci dilepas dan pengaturan awal dikembalikan." else "Gagal melepas kunci: ${o.failures.joinToString("; ")}"
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = rootOk && !busy && locked > 0)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (bootOn == true) "Kunci saat boot: AKTIF" else "Kunci saat boot", bootOn == true) {
                if (!rootOk || busy) return@Chip
                scope.launch {
                    busy = true
                    if (bootOn == true) {
                        bootOn = !d.removeBoot()
                        msg = if (bootOn == false) "Profil boot dihapus." else "Gagal menghapus profil boot."
                    } else {
                        val err = d.installBoot()
                        bootOn = err == null
                        msg = err ?: "Kunci refresh rate akan diterapkan otomatis setiap boot (module systemless Magisk/KernelSU/APatch)."
                    }
                    busy = false
                }
            }
            GlassButton("Cek status", {
                scope.launch {
                    guardRun = d.guardRunning()
                    bootOn = d.bootInstalled()
                    cur = d.currentHz()
                }
            }, enabled = rootOk && !busy)
        }
        guardRun?.let { Caption(if (it) "Penjaga sedang berjalan." else "Penjaga tidak berjalan.") }
        if (busy) Spinner("Memproses…")
        msg?.let { Body(it) }
    }
}

// ----------------------------------------------------------------------------- Jaringan

@Composable
private fun NetworkDiagnostics(s: ServiceLocator) {
    var rows by remember { mutableStateOf<List<InfoRow>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    GlassCard {
        Heading("Diagnostik jaringan")
        Body("Menguji DNS, koneksi TCP, dan HTTPS ke server publik. Hanya berjalan saat kamu menekan tombol.", dim = true)
        GlassButton(if (busy) "Menguji…" else "Jalankan tes", {
            if (!busy) scope.launch {
                busy = true
                rows = withContext(Dispatchers.IO) { s.device.networkDiagnostics() }
                busy = false
            }
        }, primary = true, enabled = !busy)
        rows?.forEach { KeyValue(it.label, it.value) }
    }
}
