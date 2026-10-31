package com.senzaproject.senzaai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.senzaproject.senzaai.di.ServiceLocator
import com.senzaproject.senzaai.domain.model.InfoRow
import com.senzaproject.senzaai.domain.model.InfoSection
import com.senzaproject.senzaai.ui.components.Body
import com.senzaproject.senzaai.ui.components.Caption
import com.senzaproject.senzaai.ui.components.ChipRow
import com.senzaproject.senzaai.ui.components.GlassButton
import com.senzaproject.senzaai.ui.components.GlassCard
import com.senzaproject.senzaai.ui.components.Heading
import com.senzaproject.senzaai.ui.components.InfoSectionCard
import com.senzaproject.senzaai.ui.components.KeyValue
import com.senzaproject.senzaai.ui.components.ScreenScaffold
import com.senzaproject.senzaai.ui.components.Spinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TABS = listOf("Perangkat", "CPU", "GPU", "Memori", "Baterai", "Layar", "Jaringan")
private val LIVE_TABS = setOf(1, 3, 4)

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
        val list = sections
        if (list == null) Spinner("Membaca…") else list.forEach { InfoSectionCard(it) }
        if (tab == 6) NetworkDiagnostics(s)
        if (tab in LIVE_TABS) Caption("Data diperbarui otomatis setiap 3 detik selama tab ini terbuka.")
        Caption("Nilai \"Tidak tersedia\" berarti perangkat atau SELinux tidak mengizinkan membacanya pada Normal Access; bukan tebakan.")
    }
}

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
