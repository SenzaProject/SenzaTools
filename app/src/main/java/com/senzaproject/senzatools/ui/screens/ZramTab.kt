package com.senzaproject.senzatools.ui.screens

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
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.platform.root.ZramTuner
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.ui.theme.LocalGlass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SIZES = listOf("1 GB" to 1024L, "2 GB" to 2048L, "3 GB" to 3072L, "4 GB" to 4096L, "6 GB" to 6144L, "8 GB" to 8192L)
private val SWAPS = listOf(60, 80, 100)

@Composable
fun ZramTab(s: ServiceLocator) {
    val z = s.zram
    val g = LocalGlass.current
    val scope = rememberCoroutineScope()
    val rootOk = s.access.state.collectAsState().let { s.access.isRootVerified }

    var info by remember { mutableStateOf<ZramTuner.Info?>(null) }
    var sizeIdx by remember { mutableStateOf(SIZES.lastIndex) }   // bawaan: 8 GB
    var algoIdx by remember { mutableStateOf(0) }                 // 0 = pakai yang sedang aktif
    var swIdx by remember { mutableStateOf(SWAPS.lastIndex) }
    var boot by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(rootOk) { while (true) { info = z.read(); delay(3000) } }

    val inf = info
    val sizeMb = SIZES[sizeIdx].second
    val algos = inf?.algos.orEmpty()
    val chosenAlgo = if (algoIdx == 0) null else algos.getOrNull(algoIdx - 1)

    GlassCard(strong = true) {
        Heading("zRAM")
        when {
            inf == null -> Spinner("Membaca zRAM…")
            !inf.present -> Body("zRAM (zram0) tidak terbaca. Kernel ini mungkin tidak memakai zRAM, atau butuh root untuk membacanya.", dim = true)
            else -> {
                KeyValue("Status", if (inf.swapTotalMb > 0) "Aktif" else "Mati")
                KeyValue("Ukuran zRAM", "${inf.diskMb} MB")
                KeyValue("Algoritma", inf.algo ?: "Tidak diketahui")
                KeyValue("Swap terpakai", "${inf.swapUsedMb} / ${inf.swapTotalMb} MB")
                if (inf.swapTotalMb > 0) BarMeter(inf.swapUsedMb * 100f / inf.swapTotalMb)
                if (inf.origMb > 0 && inf.comprMb > 0) KeyValue("Kompresi", "${inf.origMb} MB -> ${inf.comprMb} MB (${"%.1f".format(inf.origMb.toFloat() / inf.comprMb)}x)")
                KeyValue("Swappiness", inf.swappiness?.toString() ?: "Tidak terbaca")
                KeyValue("RAM fisik", "${inf.ramMb} MB")
            }
        }
    }

    GlassCard {
        Heading("Atur zRAM")
        Caption("Ukuran")
        ChipRow(SIZES.map { it.first }, sizeIdx) { sizeIdx = it }
        if (algos.size > 1) {
            Caption("Algoritma kompresi")
            ChipRow(listOf("Bawaan") + algos, algoIdx) { algoIdx = it }
        }
        Caption("Swappiness (makin tinggi makin sering memakai zRAM)")
        ChipRow(SWAPS.map { it.toString() }, swIdx) { swIdx = it }
        Chip(if (boot) "Terapkan saat boot: AKTIF" else "Terapkan saat boot: NONAKTIF", boot) { boot = !boot }
        if (!rootOk) Caption("Fitur ini butuh akses root. Izinkan di root manager, lalu tekan Minta izin root di Tools → Akses.", color = g.warn)
        GlassButton(if (busy) "Menerapkan…" else "Terapkan zRAM ${SIZES[sizeIdx].first}", { confirm = true },
            Modifier.fillMaxWidth(), primary = true, enabled = rootOk && !busy && inf?.present == true)
        GlassButton("Kembalikan ke bawaan", { confirmReset = true }, Modifier.fillMaxWidth(), enabled = rootOk && !busy)
        msg?.let { Body(it) }
        Caption("Ukuran zRAM adalah kapasitas virtual. Memori fisik yang terpakai hanya sebagian kecil dari data yang disimpan (kompresi kira-kira 2x sampai 3x). Bila HP bermasalah saat boot, hapus folder /data/adb/senza_zram lewat recovery atau ADB.")
    }

    if (confirm) ConfirmDialog(
        "Terapkan zRAM ${SIZES[sizeIdx].first}?",
        "zRAM dimatikan sebentar lalu dibuat ulang, jadi aplikasi latar bisa dimuat ulang." +
            (if (inf != null && inf.ramMb > 0 && sizeMb > inf.ramMb * 2) "\n\nPerhatian: ukuran ini lebih dari 2x RAM fisik (${inf.ramMb} MB), manfaatnya kecil dan bisa membuat HP lebih lambat." else "") +
            (if (boot) "\n\nAkan diterapkan otomatis tiap boot." else "\n\nHanya berlaku sampai restart."),
        confirmText = "Terapkan",
        onConfirm = {
            confirm = false
            scope.launch {
                busy = true; msg = null
                msg = z.apply(sizeMb, chosenAlgo, SWAPS[swIdx], boot).message
                info = z.read()
                busy = false
            }
        },
        onDismiss = { confirm = false }
    )

    if (confirmReset) ConfirmDialog(
        "Kembalikan zRAM ke bawaan?",
        "Penerapan otomatis saat boot dihapus dan zRAM dikembalikan ke nilai awal HP.",
        confirmText = "Kembalikan",
        onConfirm = {
            confirmReset = false
            scope.launch {
                busy = true; msg = null
                msg = z.restoreDefault().message
                info = z.read()
                busy = false
            }
        },
        onDismiss = { confirmReset = false }
    )
}
