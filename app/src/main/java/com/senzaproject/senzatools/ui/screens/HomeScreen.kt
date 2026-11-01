package com.senzaproject.senzatools.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
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

    ScreenScaffold("Senza Tools", "Intelligent Android. Smarter System Control.") {
        GlassCard(strong = true) {
            Heading("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
            Caption("Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT} · ${Build.DEVICE}")
            Caption("Kernel ${System.getProperty("os.version") ?: "-"}")
            data?.let { Caption("Aktif sejak boot: ${it.uptime.formatDuration()}") }
        }

        GlassCard {
            Heading("Ringkasan kondisi")
            val d = data
            if (d == null) {
                Spinner("Membaca data perangkat…")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RingGauge(d.cpu.usagePercent, "CPU", if (d.cpu.usagePercent == null) "tidak tersedia" else if (d.cpu.usageIsEstimate) "estimasi dari frekuensi" else "${d.cpu.cores.count { it.online }} core aktif")
                    RingGauge(d.mem.percent, "RAM", "${d.mem.usedBytes.formatBytes()} / ${d.mem.totalBytes.formatBytes()}")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RingGauge(d.bat.percent.toFloat(), "Baterai", "${d.bat.status}${d.bat.tempC?.let { " · ${it.f1()}°C" } ?: ""}")
                    RingGauge(d.sto.percent, "Storage", "${d.sto.freeBytes.formatBytes()} bebas")
                }
                val hp = health?.primary
                KeyValue(
                    "Kesehatan baterai",
                    hp?.let { (if (it.estimate) "≈ " else "") + "${it.percent.f1()} %" + (if (it.estimate) " (perkiraan)" else "") }
                        ?: (d.bat.healthPercent?.let { "${it.f1()} %" } ?: if (health == null) "Membaca…" else "Tidak tersedia · buka Device → Baterai")
                )
            }
        }

        GlassCard {
            Heading("Suhu")
            val d = data
            KeyValue("Baterai", d?.bat?.tempC?.let { "${it.f1()} °C" } ?: "Tidak tersedia")
            KeyValue("CPU / SoC", d?.cpu?.tempC?.let { "${it.f1()} °C" } ?: "Tidak tersedia")
            KeyValue("Zona termal tertinggi", d?.devTemp?.let { "${it.f1()} °C" } ?: "Tidak tersedia")
            Caption("Sensor suhu bergantung pada perangkat dan izin SELinux.")
        }

        GlassCard(onClick = { go(Dest.TOOLS) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Heading("Mode akses")
                StatusPill(access.active.label, if (access.active == AccessMode.ROOT) g.warn else g.good)
            }
            Body(
                if (access.active == AccessMode.ROOT) "Root aktif di aplikasi ini. Semua operasi root tercatat di log."
                else "Normal Access. Root diminta sekali saat apk dibuka; bila ditolak, aktifkan lewat Tools → Akses.",
                dim = true
            )
        }

        GlassCard {
            Heading("Diagnostik terbaru")
            val rep = s.session.lastDiagnostic
            if (rep == null) {
                Body("Belum ada diagnostik. Jalankan pemeriksaan lokal (tanpa internet) untuk melihat masalah, bukti, dan rekomendasi.", dim = true)
            } else {
                rep.findings.take(4).forEach { f ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SeverityPill(f.severity)
                        Body(f.title, Modifier.weight(1f))
                    }
                }
                if (rep.findings.size > 4) Caption("+${rep.findings.size - 4} temuan lain — buka tab Toolkit untuk detail.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(if (diagBusy) "Memeriksa…" else "Jalankan diagnostik", {
                    if (!diagBusy) scope.launch {
                        diagBusy = true
                        s.session.lastDiagnostic = withContext(Dispatchers.IO) { s.diagnostics.run() }
                        diagBusy = false
                    }
                }, primary = true, enabled = !diagBusy)
                GlassButton("Buka Toolkit", { go(Dest.TOOLKIT) })
            }
        }

        GlassCard {
            Heading("Pintasan")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Analisis APK", { go(Dest.ANALYZER) }, Modifier.weight(1f))
                GlassButton("Info perangkat", { go(Dest.DEVICE) }, Modifier.weight(1f))
            }
        }
    }
}
