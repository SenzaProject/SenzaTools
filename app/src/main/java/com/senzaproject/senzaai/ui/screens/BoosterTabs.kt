package com.senzaproject.senzaai.ui.screens

import android.content.Intent
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
import com.senzaproject.senzaai.di.ServiceLocator
import com.senzaproject.senzaai.platform.root.Booster
import com.senzaproject.senzaai.ui.components.*
import com.senzaproject.senzaai.ui.theme.LocalGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
private fun rootOkNow(s: ServiceLocator): Boolean {
    val st by s.access.state.collectAsState()
    return st.let { s.access.isRootVerified }
}

@Composable
private fun NeedRoot(ok: Boolean, text: String = "Fitur ini butuh akses root. Izinkan di root manager, lalu tekan Minta izin root di Tools → Akses.") {
    if (!ok) Caption(text, color = LocalGlass.current.warn)
}

private fun keepOf(s: String) = s.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

// ============================================================================== Mode Fokus Game

@Composable
fun GameFocusTab(s: ServiceLocator) {
    val b = s.booster
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootOk = rootOkNow(s)

    var apps by remember { mutableStateOf<List<Booster.AppItem>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var game by remember { mutableStateOf(b.gamePkg) }
    var keep by remember { mutableStateOf(b.keepList) }
    var hard by remember { mutableStateOf(b.hardMode) }
    var on by remember { mutableStateOf(b.gameModeOn) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { b.listApps() } }

    GlassCard(strong = true) {
        Heading("Mode Fokus Game")
        Body("Semua aplikasi pihak ketiga lain dibekukan supaya RAM dan CPU hanya dipakai game. Launcher, keyboard, dan aplikasi pengecualian tetap jalan. Klik Matikan untuk memulihkan semuanya.", dim = true)
        NeedRoot(rootOk)
        if (on) StatusPill("AKTIF — game: ${game.ifBlank { "-" }}", LocalGlass.current.good)
    }

    GlassCard {
        Heading("1. Pilih game")
        GlassTextField(filter, { filter = it }, "Cari aplikasi (nama atau paket)")
        val list = apps.filter { filter.isBlank() || it.label.contains(filter, true) || it.pkg.contains(filter, true) }.take(8)
        list.forEach { a ->
            GlassCard(strong = a.pkg == game, padding = 10.dp, onClick = { game = a.pkg }) {
                Body(a.label)
                Caption(a.pkg)
            }
        }
        if (game.isNotBlank()) Caption("Dipilih: $game")
    }

    GlassCard {
        Heading("2. Cara membekukan")
        ChipRow(listOf("Bekukan proses (bisa dilanjut)", "Hentikan + blokir latar"), if (hard) 1 else 0) { hard = it == 1 }
        Caption(
            if (hard) "Aplikasi di-force-stop lalu dilarang jalan di latar belakang (appops). Lebih kuat, tapi aplikasi mulai dari awal saat dipulihkan."
            else "Proses dibekukan (SIGSTOP) dan dilanjutkan (SIGCONT) saat mode dimatikan. Status aplikasi tetap terjaga."
        )
        GlassTextField(keep, { keep = it }, "Pengecualian: paket dipisah koma (tetap jalan)", singleLine = false, mono = true)
    }

    GlassCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (busy) "Memproses…" else "Aktifkan & buka game", {
                scope.launch {
                    busy = true; msg = null
                    val r = b.enterGameMode(game, keepOf(keep), hard)
                    msg = r.message
                    on = b.gameModeOn
                    if (r.ok) {
                        ctx.packageManager.getLaunchIntentForPackage(game)?.let {
                            ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    busy = false
                }
            }, Modifier.weight(1f), primary = true, enabled = rootOk && !busy && game.isNotBlank())
            GlassButton("Matikan & pulihkan", {
                scope.launch {
                    busy = true
                    msg = b.exitGameMode().message
                    on = b.gameModeOn
                    busy = false
                }
            }, Modifier.weight(1f), enabled = rootOk && !busy)
        }
        msg?.let { Body(it) }
        Caption("Tips: jika HP direstart saat mode aktif, bekuan hilang sendiri. Jika memakai mode blokir latar, tekan Matikan & pulihkan agar notifikasi aplikasi normal lagi.")
    }
}

// ============================================================================== DPI changer

@Composable
fun DpiTab(s: ServiceLocator) {
    val b = s.booster
    val scope = rememberCoroutineScope()
    val rootOk = rootOkNow(s)
    var dpi by remember { mutableStateOf<Booster.Dpi?>(null) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { dpi = b.readDpi(); input = dpi?.current?.toString().orEmpty() }

    fun applyDpi(v: Int) {
        scope.launch {
            busy = true; msg = null
            val prev = dpi?.current
            val phys = dpi?.physical
            val r = b.setDpi(v)
            msg = r.message
            if (r.ok) {
                pending = true
                left = 15
                while (left > 0 && pending) { delay(1000); left-- }
                if (pending) {
                    if (prev == null || prev == phys) b.resetDpi() else b.setDpi(prev)
                    msg = "Tidak dikonfirmasi, DPI dikembalikan ke semula."
                }
                pending = false
            }
            dpi = b.readDpi()
            busy = false
        }
    }

    GlassCard(strong = true) {
        Heading("DPI Changer")
        Body("Mengubah kepadatan layar (wm density). DPI lebih kecil = tampilan lebih luas, lebih besar = lebih besar dan mudah dibaca. Perubahan tetap setelah reboot sampai direset.", dim = true)
        NeedRoot(rootOk)
        KeyValue("DPI bawaan", dpi?.physical?.toString() ?: "?")
        KeyValue("DPI sekarang", dpi?.current?.toString() ?: "?")
    }

    GlassCard {
        Heading("Pilih DPI")
        val phys = dpi?.physical
        if (phys != null) {
            val presets = listOf(0.75f, 0.85f, 1f, 1.15f).map { (phys * it / 10f).roundToInt() * 10 }.distinct()
            ChipRow(presets.map { it.toString() }, presets.indexOf(input.toIntOrNull()).takeIf { it >= 0 } ?: -1) { input = presets[it].toString() }
        }
        GlassTextField(input, { input = it.filter { c -> c.isDigit() }.take(4) }, "DPI (160–800)", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Terapkan", { input.toIntOrNull()?.let { applyDpi(it) } }, Modifier.weight(1f), primary = true, enabled = rootOk && !busy && input.toIntOrNull() != null)
            GlassButton("Reset bawaan", {
                scope.launch { busy = true; msg = b.resetDpi().message; dpi = b.readDpi(); input = dpi?.current?.toString().orEmpty(); busy = false }
            }, Modifier.weight(1f), enabled = rootOk && !busy)
        }
        if (pending) {
            StatusPill("Kembali otomatis dalam $left detik", LocalGlass.current.warn)
            GlassButton("Pertahankan DPI ini", { pending = false }, Modifier.fillMaxWidth(), primary = true)
        }
        msg?.let { Body(it) }
        Caption("Setelah menerapkan, kamu punya 15 detik untuk menekan Pertahankan. Kalau layar jadi tidak terbaca, DPI kembali sendiri. Jangan pakai DPI ekstrem (di bawah ~70% bawaan).")
    }
}

// ============================================================================== RAM cleaner

@Composable
fun RamTab(s: ServiceLocator) {
    val b = s.booster
    val scope = rememberCoroutineScope()
    val rootOk = rootOkNow(s)
    var mem by remember { mutableStateOf<Booster.Mem?>(null) }
    var aggressive by remember { mutableStateOf(false) }
    var keep by remember { mutableStateOf(b.keepList) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) { mem = withContext(Dispatchers.IO) { b.readMem() }; delay(2000) }
    }

    GlassCard(strong = true) {
        Heading("RAM Cleaner")
        mem?.let {
            val used = it.totalMb - it.availMb
            KeyValue("Total", "${it.totalMb} MB")
            KeyValue("Terpakai", "$used MB")
            KeyValue("Tersedia", "${it.availMb} MB")
            BarMeter(used * 100f / it.totalMb)
        } ?: Body("Data RAM tidak terbaca.", dim = true)
    }

    GlassCard {
        Heading("Bersihkan")
        Body(if (rootOk) "Dengan root: hentikan proses cache, kosongkan page cache, dan padatkan memori." else "Tanpa root: hanya menghentikan proses latar yang diizinkan sistem. Dengan root hasilnya lebih besar.", dim = true)
        Chip(if (aggressive) "Mode agresif: AKTIF" else "Mode agresif (force-stop semua app pihak ketiga)", aggressive) { aggressive = !aggressive }
        if (aggressive) GlassTextField(keep, { keep = it }, "Pengecualian: paket dipisah koma", singleLine = false, mono = true)
        GlassButton(if (busy) "Membersihkan…" else "Bersihkan RAM sekarang", {
            scope.launch {
                busy = true; msg = null
                msg = b.cleanRam(aggressive && rootOk, keepOf(keep)).message
                busy = false
            }
        }, Modifier.fillMaxWidth(), primary = true, enabled = !busy)
        msg?.let { Body(it) }
        Caption("Android memang memakai RAM semaksimal mungkin; RAM kosong yang naik sebentar lalu turun lagi itu normal. Aplikasi yang dihentikan akan memuat ulang saat dibuka.")
    }
}

// ============================================================================== Sinyal Wi-Fi & seluler

@Composable
fun SignalTab(s: ServiceLocator) {
    val b = s.booster
    val scope = rememberCoroutineScope()
    val rootOk = rootOkNow(s)
    var wifi by remember { mutableStateOf<Booster.WifiSig?>(null) }
    var cell by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }

    suspend fun load() {
        wifi = withContext(Dispatchers.IO) { b.wifi() }
        cell = b.cellDbm()
    }
    LaunchedEffect(rootOk) { while (true) { load(); delay(3000) } }

    fun quality(d: Int, wifiMode: Boolean) = if (wifiMode) when {
        d >= -55 -> "Sangat baik"; d >= -67 -> "Baik"; d >= -75 -> "Cukup"; else -> "Lemah"
    } else when {
        d >= -85 -> "Sangat baik"; d >= -100 -> "Baik"; d >= -110 -> "Cukup"; else -> "Lemah"
    }

    GlassCard(strong = true) {
        Heading("Sinyal Wi-Fi & Seluler")
        Body("Memuat ulang koneksi supaya HP memilih jaringan/menara terbaik, plus pembacaan kekuatan sinyal langsung agar kamu bisa melihat bedanya.", dim = true)
        val w = wifi
        if (w != null) {
            KeyValue("Wi-Fi", "${w.rssi} dBm (${quality(w.rssi, true)})")
            KeyValue("Band", if (w.freqMhz >= 5900) "6 GHz" else if (w.freqMhz >= 4900) "5 GHz" else "2.4 GHz")
            KeyValue("Kecepatan link", "${w.linkMbps} Mbps")
        } else Body("Wi-Fi tidak tersambung.", dim = true)
        val c = cell
        if (c != null) KeyValue("Seluler", "$c dBm (${quality(c, false)})")
        else Body(if (rootOk) "Sinyal seluler tidak terbaca di perangkat ini." else "Sinyal seluler hanya terbaca dengan root.", dim = true)
    }

    GlassCard {
        Heading("Segarkan koneksi")
        NeedRoot(rootOk)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Segarkan Wi-Fi", {
                scope.launch { busy = true; msg = b.refreshWifi().message; delay(4000); load(); busy = false }
            }, Modifier.weight(1f), primary = true, enabled = rootOk && !busy)
            GlassButton("Segarkan seluler", { confirm = true }, Modifier.weight(1f), enabled = rootOk && !busy)
        }
        msg?.let { Body(it) }
        Caption("Jujur saja: tidak ada aplikasi yang bisa menguatkan sinyal secara fisik. Fitur ini hanya menyambung ulang agar HP berpindah ke router/menara dengan sinyal terbaik. Hasil paling terasa kalau sinyal sedang nyangkut di yang lemah. Untuk Wi-Fi, 5 GHz lebih cepat tapi jangkauannya lebih pendek dari 2.4 GHz.")
    }

    if (confirm) ConfirmDialog(
        "Segarkan jaringan seluler?",
        "Mode pesawat akan menyala sekitar 3 detik. Panggilan dan data terputus sebentar.",
        confirmText = "Segarkan",
        onConfirm = {
            confirm = false
            scope.launch { busy = true; msg = b.refreshCellular().message; delay(6000); load(); busy = false }
        },
        onDismiss = { confirm = false }
    )
}

// ============================================================================== Fast charger

@Composable
fun ChargerTab(s: ServiceLocator) {
    val b = s.booster
    var c by remember { mutableStateOf<Booster.Charge?>(null) }
    var peak by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            val r = withContext(Dispatchers.IO) { b.readCharge() }
            c = r
            r.watts?.let { if (it > peak) peak = it }
            delay(1500)
        }
    }

    val ch = c
    GlassCard(strong = true) {
        Heading("Fast Charger")
        if (ch == null) Body("Membaca data pengisian…", dim = true)
        else {
            val g = LocalGlass.current
            val label = b.chargeLabel(ch)
            Title(ch.watts?.let { "%.1f W".format(it) } ?: "— W")
            StatusPill(label, if (label.contains("Fast")) g.good else if (ch.watts != null) g.accent2 else g.textDim)
            Caption("Label dihitung dari arus × tegangan yang benar-benar terukur di HP ini, bukan dari teks bawaan ROM. Jadi tetap akurat di ROM port yang tidak menampilkan fast charging.")
        }
    }

    if (ch != null) {
        GlassCard {
            Heading("Detail")
            KeyValue("Status", ch.status)
            KeyValue("Sumber", ch.plugged)
            KeyValue("Arus", ch.mA?.let { "%.0f mA".format(it) } ?: "tidak terbaca")
            KeyValue("Tegangan", ch.volts?.let { "%.2f V".format(it) } ?: "tidak terbaca")
            KeyValue("Daya puncak (sesi ini)", if (peak > 0) "%.1f W".format(peak) else "-")
            ch.maxWatts?.let { KeyValue("Kemampuan charger terdeteksi", "%.0f W".format(it)) }
            ch.chargeType?.let { KeyValue("Tipe pengisian (kernel)", it) }
            ch.usbType?.let { KeyValue("Tipe USB", it) }
            KeyValue("Baterai", "${ch.level}%" + (ch.tempC?.let { " · %.1f°C".format(it) } ?: ""))
            if (ch.mA == null) Caption("Arus tidak terbaca dari BatteryManager di ROM ini; kernel mungkin menyembunyikannya.")
        }
        if (ch.extra.isNotEmpty()) ExpandableCard("Node sysfs yang terbaca (${ch.extra.size})") {
            ch.extra.forEach { (k, v) -> KeyValue(k, v) }
        }
    }
}
