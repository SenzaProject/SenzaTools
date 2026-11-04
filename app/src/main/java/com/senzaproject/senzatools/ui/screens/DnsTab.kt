package com.senzaproject.senzatools.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.platform.root.DnsTuner
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.ui.theme.LocalGlass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DnsTab(s: ServiceLocator) {
    val d = s.dns
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootOk = s.access.state.collectAsState().let { s.access.isRootVerified }

    var st by remember { mutableStateOf(d.state()) }
    var sel by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgOk by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { while (true) { st = d.state(); delay(2000) } }

    val provider = DnsTuner.PROVIDERS[sel]

    GlassCard(strong = true) {
        Heading("Private DNS")
        KeyValue("Mode", DnsTuner.modeLabel(st.mode))
        if (st.mode == "hostname") KeyValue("Hostname", st.host ?: "-")
        Caption("Mengenkripsi DNS (DNS-over-TLS) untuk semua aplikasi, sama dengan menu Settings > Private DNS.")
    }

    GlassCard {
        Heading("Pilih DNS")
        DnsTuner.PROVIDERS.forEachIndexed { i, p ->
            val active = st.mode == "hostname" && st.host == p.host
            GlassCard(strong = i == sel, padding = 10.dp, onClick = { sel = i; msg = null }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Heading(p.name, Modifier.weight(1f))
                    if (active) StatusPill("Aktif", g.good) else if (i == sel) StatusPill("Dipilih", g.accent)
                }
                Mono(p.host, size = 12)
                Caption(p.note)
            }
        }
        if (!rootOk) Caption("Menerapkan DNS butuh akses root. Izinkan di root manager, lalu tekan Minta izin root di Tools → Akses. Tanpa root, salin hostname lalu isi manual di Settings > Private DNS.", color = g.warn)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (busy) "Menguji…" else "Tes", {
                scope.launch {
                    busy = true; msg = null
                    val r = d.test(provider.host)
                    msg = r.message; msgOk = r.ok; busy = false
                }
            }, Modifier.weight(1f), enabled = !busy)
            GlassButton("Terapkan", {
                scope.launch {
                    busy = true; msg = null
                    val r = d.apply(provider.host)
                    msg = r.message; msgOk = r.ok; st = d.state(); busy = false
                }
            }, Modifier.weight(1f), primary = true, enabled = rootOk && !busy)
        }
        GlassButton("Salin hostname", { copyText(ctx, "DNS", provider.host); msg = "Hostname ${provider.host} disalin."; msgOk = true }, Modifier.fillMaxWidth())
        msg?.let { Body(it, color = if (msgOk) g.good else g.bad) }
    }

    GlassCard {
        Heading("Kembalikan")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Otomatis", {
                scope.launch {
                    busy = true
                    val r = d.setMode("opportunistic")
                    msg = r.message; msgOk = r.ok; st = d.state(); busy = false
                }
            }, Modifier.weight(1f), enabled = rootOk && !busy)
            GlassButton("Nonaktif", {
                scope.launch {
                    busy = true
                    val r = d.setMode("off")
                    msg = r.message; msgOk = r.ok; st = d.state(); busy = false
                }
            }, Modifier.weight(1f), enabled = rootOk && !busy)
        }
        Caption("Otomatis adalah bawaan Android. Bila internet bermasalah setelah mengganti DNS, tekan Otomatis.")
    }
}
