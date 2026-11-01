package com.senzaproject.senzatools.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.domain.model.AccessMode
import com.senzaproject.senzatools.domain.model.BatterySnapshot
import com.senzaproject.senzatools.domain.model.CpuSnapshot
import com.senzaproject.senzatools.domain.model.MemorySnapshot
import com.senzaproject.senzatools.domain.model.Severity
import com.senzaproject.senzatools.domain.model.StorageSnapshot
import com.senzaproject.senzatools.navigation.Dest
import com.senzaproject.senzatools.ui.components.Body
import com.senzaproject.senzatools.ui.components.Caption
import com.senzaproject.senzatools.ui.components.GlassButton
import com.senzaproject.senzatools.ui.components.GlassCard
import com.senzaproject.senzatools.ui.components.GlassDivider
import com.senzaproject.senzatools.ui.components.Heading
import com.senzaproject.senzatools.ui.components.KeyValue
import com.senzaproject.senzatools.ui.components.RingGauge
import com.senzaproject.senzatools.ui.components.ScreenScaffold
import com.senzaproject.senzatools.ui.components.SeverityPill
import com.senzaproject.senzatools.ui.components.Spinner
import com.senzaproject.senzatools.ui.components.StatusPill
import com.senzaproject.senzatools.ui.theme.LocalGlass
import com.senzaproject.senzatools.utils.f1
import com.senzaproject.senzatools.utils.formatBytes
import com.senzaproject.senzatools.utils.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class HomeData(
    val cpu: CpuSnapshot,
    val mem: MemorySnapshot,
    val sto: StorageSnapshot,
    val bat: BatterySnapshot,
    val devTemp: Float?,
    val uptime: Long
)

@Composable
fun HomeScreen(s: ServiceLocator, go: (Dest) -> Unit) {
    val g = LocalGlass.current
    var data by remember { mutableStateOf<HomeData?>(null) }
    val access by s.access.state.collectAsState()
    var diagBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val rootOk = s.access.isRootVerified
    var health by remember { mutableStateOf<com.senzaproject.senzatools.data.BatteryHealthInfo?>(null) }
    // Dibaca ulang saat root disetujui (root diminta async saat aplikasi dibuka).
    LaunchedEffect(rootOk) { health = withContext(Dispatchers.IO) { s.batteryHealth.read(rootOk) } }

    LaunchedEffect(Unit) {
        while (true) {
            data = withContext(Dispatchers.IO) {
                HomeData(
                    cpu = s.device.cpuSnapshot(),
                    mem = s.device.memory(),
                    sto = s.device.storage(),
                    bat = s.device.battery(),
                    devTemp = s.device.thermalZones().maxOfOrNull { it.second },
                    uptime = s.monitor.uptimeMs()
                )
            }
            delay(3000)
        }
    }

    val d = data
    ScreenScaffold(
        "Senza Tools", "Intelligent Android. Smarter System Control.",
        action = {
            IconButton(onClick = { go(Dest.SETTINGS) }) {
                Icon(Icons.Rounded.Settings, "Settings", tint = g.textDim, modifier = Modifier.size(24.dp))
            }
        }
    ) {
        // 1. Perangkat
        GlassCard(strong = true) {
            Heading("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
            Caption("Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT} · Kernel ${System.getProperty("os.version") ?: "-"}")
            if (d != null) Caption("Aktif sejak boot: ${d.uptime.formatDuration()}")
        }

        // 2. Kondisi: empat gauge + tiga baris detail dalam satu kartu
        GlassCard {
            if (d == null) {
                Spinner("Membaca data perangkat…")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RingGauge(d.cpu.usagePercent, "CPU", size = 80.dp)
                    RingGauge(d.mem.percent, "RAM", size = 80.dp)
                    RingGauge(d.bat.percent.toFloat(), "Baterai", size = 80.dp)
                    RingGauge(d.sto.percent, "Storage", size = 80.dp)
                }
                GlassDivider()
                val hp = health?.primary
                KeyValue(
                    "Kesehatan baterai",
                    hp?.let { (if (it.estimate) "≈ " else "") + "${it.percent.f1()} %" }
                        ?: (d.bat.healthPercent?.let { "${it.f1()} %" } ?: if (health == null) "Membaca…" else "Tidak tersedia")
                )
                KeyValue("Suhu baterai", d.bat.tempC?.let { "${it.f1()} °C" } ?: "Tidak tersedia")
                KeyValue("Suhu CPU", (d.cpu.tempC ?: d.devTemp)?.let { "${it.f1()} °C" } ?: "Tidak tersedia")
                KeyValue("RAM", "${d.mem.usedBytes.formatBytes()} / ${d.mem.totalBytes.formatBytes()}")
                KeyValue("Storage", "${d.sto.freeBytes.formatBytes()} bebas")
            }
        }

        // 3. Akses
        GlassCard(onClick = { go(Dest.TOOLS) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Heading("Mode akses")
                StatusPill(access.active.label, if (access.active == AccessMode.ROOT) g.warn else g.good)
            }
            Caption(
                if (access.active == AccessMode.ROOT) "Root aktif. Semua operasi root tercatat di log."
                else "Normal Access. Aktifkan root lewat Tools → Akses."
            )
        }

        // 4. Diagnostik
        GlassCard {
            Heading("Diagnostik")
            val rep = s.session.lastDiagnostic
            if (rep == null) {
                Caption("Pemeriksaan lokal tanpa internet. Hasil: masalah, bukti, dan rekomendasi.")
            } else {
                rep.findings.take(3).forEach { f ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        SeverityPill(f.severity)
                        Body(f.title, Modifier.weight(1f), maxLines = 2)
                    }
                }
                if (rep.findings.size > 3) Caption("+${rep.findings.size - 3} temuan lain di Toolkit.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(if (diagBusy) "Memeriksa…" else "Jalankan", {
                    if (!diagBusy) scope.launch {
                        diagBusy = true
                        s.session.lastDiagnostic = withContext(Dispatchers.IO) { s.diagnostics.run() }
                        diagBusy = false
                    }
                }, primary = true, enabled = !diagBusy)
                GlassButton("Detail", { go(Dest.TOOLKIT) })
            }
        }
    }
}
