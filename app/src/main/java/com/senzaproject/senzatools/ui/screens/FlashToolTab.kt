package com.senzaproject.senzatools.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.platform.flashtool.FlashStage
import com.senzaproject.senzatools.platform.flashtool.ScatterFile
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.ui.theme.LocalGlass

/**
 * Flash Tool — Tahap 1 (deteksi USB + identifikasi chip BROM), Tahap 2 (kirim & jalankan DA1),
 * Tahap 3 (buka kanal command XFlash + minta versi DA). BELUM flashing partisi apa pun.
 * Lihat komentar di BromProtocol.kt / XFlashProtocol.kt / FlashToolEngine.kt untuk batasan yang diketahui.
 */
@Composable
fun FlashToolTab(s: ServiceLocator) {
    val g = LocalGlass.current
    val st by s.flashTool.state.collectAsState()

    val files by s.flashTool.files.collectAsState()

    val daPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) s.flashTool.selectDa(uri)
    }
    val authPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) s.flashTool.selectAuth(uri)
    }
    val preloaderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) s.flashTool.selectPreloader(uri)
    }
    val scatterPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) s.flashTool.selectScatter(uri)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) s.flashTool.searchFolder(uri)
    }
    var writeTargetPartition by rememberSaveable { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val part = writeTargetPartition
        if (uri != null && part != null) s.flashTool.pickImageForPartition(part, uri)
    }

    DisposableEffect(Unit) { onDispose { s.flashTool.dispose() } }

    GlassCard(strong = true) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Heading("Flash Tool — Tahap 1+2: BROM & DA")
            StatusPill(
                when (st.stage) {
                    FlashStage.IDLE -> "Siap"
                    FlashStage.WAITING_DEVICE -> "Memindai…"
                    FlashStage.PERMISSION_NEEDED -> "Menunggu izin USB"
                    FlashStage.HANDSHAKING -> "Handshake…"
                    FlashStage.IDENTIFIED -> "Teridentifikasi"
                    FlashStage.DA_PARSING -> "Membaca file DA…"
                    FlashStage.DA_MATCHED -> "DA cocok"
                    FlashStage.DA_SENDING -> "Mengirim DA…"
                    FlashStage.DA_READY -> "DA siap"
                    FlashStage.XFLASH_SYNCING -> "Buka kanal XFlash…"
                    FlashStage.XFLASH_READY -> "Kanal XFlash siap"
                    FlashStage.EMI_SENDING -> "Mengirim EMI…"
                    FlashStage.EMI_ACCEPTED -> "EMI diterima"
                    FlashStage.DA2_SENDING -> "Upload DA2…"
                    FlashStage.DA2_READY -> "DA2 siap"
                    FlashStage.GPT_READING -> "Membaca GPT…"
                    FlashStage.GPT_READY -> "GPT terbaca"
                    FlashStage.WRITING -> "Menulis partisi…"
                    FlashStage.WRITE_DONE -> "Tulis selesai"
                    FlashStage.ERROR -> "Error"
                },
                when (st.stage) {
                    FlashStage.IDENTIFIED, FlashStage.DA_MATCHED -> g.good
                    FlashStage.DA_READY, FlashStage.XFLASH_READY, FlashStage.EMI_ACCEPTED, FlashStage.DA2_READY, FlashStage.GPT_READY, FlashStage.WRITE_DONE -> g.good
                    FlashStage.WRITING -> g.warn
                    FlashStage.ERROR -> g.bad
                    FlashStage.IDLE -> g.textDim
                    else -> g.warn
                }
            )
        }
    }

    GlassCard {
        Heading("Cara masuk mode BROM")
        Body(
            "1. Matikan total device (bukan restart)\n" +
                "2. Lepas kabel USB dari HP ini (Senza Tools)\n" +
                "3. Tahan kombinasi tombol volume (naik/turun tergantung model) sambil colok kabel USB dari device target ke HP ini\n" +
                "4. Tekan \"Pindai & Deteksi\" di bawah",
            dim = true
        )
    }

    GlassCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(
                if (st.stage == FlashStage.WAITING_DEVICE || st.stage == FlashStage.HANDSHAKING) "Memproses…" else "Pindai & Deteksi",
                { s.flashTool.scanAndConnect() },
                primary = true,
                enabled = st.stage != FlashStage.WAITING_DEVICE && st.stage != FlashStage.HANDSHAKING
            )
            if (st.stage != FlashStage.IDLE) GlassButton("Reset", { s.flashTool.reset() })
        }
        st.deviceName?.let { KeyValue("USB device", it) }
        when (st.stage) {
            FlashStage.IDENTIFIED, FlashStage.DA_MATCHED, FlashStage.DA_SENDING,
            FlashStage.DA_READY, FlashStage.XFLASH_SYNCING, FlashStage.XFLASH_READY,
            FlashStage.EMI_SENDING, FlashStage.EMI_ACCEPTED, FlashStage.DA2_SENDING, FlashStage.DA2_READY,
            FlashStage.GPT_READING, FlashStage.GPT_READY -> {
                Caption("Chip teridentifikasi lewat BROM:")
                Mono(st.info ?: "-")
            }
            FlashStage.ERROR -> {
                Body("Gagal pada tahap: ${st.errorStage}", color = g.bad)
                Caption(st.errorMessage ?: "Tidak diketahui")
            }
            FlashStage.PERMISSION_NEEDED -> {
                Caption("Android meminta konfirmasi akses USB — setujui dialog izin yang muncul.")
                st.info?.let { Caption(it) }
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ Sesi: file + MULAI/BERHENTI
    val sessionActive = files.running || files.waiting || st.stage != FlashStage.IDLE
    GlassCard {
        Heading("Sesi")
        FileSlot("File DA", files.daName, files.daInfo ?: "Wajib — Download Agent untuk chip target",
            onPick = { daPicker.launch(arrayOf("*/*")) }, onClear = null)
        FileSlot("File AUTH", files.authName, "Opsional — untuk BROM dengan DAA",
            onPick = { authPicker.launch(arrayOf("*/*")) }, onClear = { s.flashTool.clearAuth() })
        FileSlot("Preloader", files.preloaderName, "Opsional — sumber data EMI setelah DA hidup",
            onPick = { preloaderPicker.launch(arrayOf("*/*")) }, onClear = { s.flashTool.clearPreloader() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(
                when {
                    files.waiting -> "Menunggu device…"
                    files.running -> "Berjalan…"
                    else -> "MULAI"
                },
                { s.flashTool.start() },
                Modifier.weight(1f),
                primary = true,
                enabled = files.daName != null && !files.running,
                icon = Icons.Rounded.PlayArrow
            )
            GlassButton(
                "BERHENTI",
                { s.flashTool.stop() },
                Modifier.weight(1f),
                danger = true,
                enabled = sessionActive,
                icon = Icons.Rounded.Stop
            )
        }
        if (files.daName == null) Caption("Pilih file DA untuk memulai sesi.", color = g.warn)
        files.note?.let { Caption(it, color = g.warn) }
    }

    // ------------------------------------------------------------------ Scatter: PILIH / START / TINJAU
    GlassCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Heading("Scatter")
            StatusPill("${files.selected.size} dipilih", if (files.selected.isEmpty()) g.textDim else g.good)
        }
        FileSlot("File scatter", files.scatterName,
            files.scatter?.let { "${it.platform.ifBlank { "?" }} • ${it.storage.ifBlank { "?" }} • ${it.partitions.size} partisi" }
                ?: "Bisa dipratinjau tanpa HP tersambung",
            onPick = { scatterPicker.launch(arrayOf("*/*")) }, onClear = { s.flashTool.clearScatter() }, pickLabel = "Pilih Scatter")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(
                if (files.waiting) "Menunggu DA…" else "START / TUNGGU DA",
                { s.flashTool.start() },
                Modifier.weight(1.4f),
                primary = true,
                enabled = !files.running,
                icon = Icons.Rounded.PlayArrow
            )
            GlassButton(
                "TINJAU",
                { s.flashTool.tinjau() },
                Modifier.weight(1f),
                enabled = files.scatter != null,
                icon = Icons.Rounded.Bolt
            )
        }
        files.scatter?.let { sc ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Pilih semua", { s.flashTool.selectAllPartitions(true) }, Modifier.weight(1f))
                GlassButton("Kosongkan", { s.flashTool.selectAllPartitions(false) }, Modifier.weight(1f))
            }
            sc.partitions.filter { it.flashable }.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickableNoRipple { s.flashTool.togglePartition(p.name) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = p.name in files.selected,
                        onCheckedChange = { s.flashTool.togglePartition(p.name) },
                        colors = CheckboxDefaults.colors(checkedColor = g.accent, uncheckedColor = g.textDim)
                    )
                    Column(Modifier.weight(1f)) {
                        Body(p.name)
                        Caption("${p.fileName} • ${ScatterFile.formatSize(p.size)}")
                    }
                }
            }
        }
        files.preview?.let { Mono(it, size = 11) }
    }

    val tahap2Visible = st.stage == FlashStage.IDENTIFIED || st.stage == FlashStage.DA_PARSING ||
        st.stage == FlashStage.DA_MATCHED || st.stage == FlashStage.DA_SENDING ||
        st.stage == FlashStage.DA_READY || st.stage == FlashStage.XFLASH_SYNCING || st.stage == FlashStage.XFLASH_READY ||
        st.stage == FlashStage.EMI_SENDING || st.stage == FlashStage.EMI_ACCEPTED ||
        st.stage == FlashStage.DA2_SENDING || st.stage == FlashStage.DA2_READY ||
        st.stage == FlashStage.GPT_READING || st.stage == FlashStage.GPT_READY

    if (tahap2Visible) {
        if (st.catalogMatches.isNotEmpty() && st.stage == FlashStage.IDENTIFIED) {
            GlassCard {
                Heading("Firmware cocok ditemukan di katalog bawaan")
                Body("Chip device ini (0x${st.hwCode?.toString(16)}) cocok dengan ${st.catalogMatches.size} entri yang sudah dibundel di APK:", dim = true)
                st.catalogMatches.forEach { entry ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Caption(entry.label)
                        GlassButton("Pakai Otomatis", { s.flashTool.useCatalogDa(entry) }, primary = true)
                    }
                    Caption(entry.note)
                }
            }
        }

        GlassCard {
            Heading("Tahap 2 — Kirim & jalankan DA")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Cari di Folder", { folderPicker.launch(null) })
                if (st.stage == FlashStage.DA_MATCHED) {
                    GlassButton(
                        "Kirim & Jalankan DA",
                        { s.flashTool.sendAndRunDa() },
                        primary = true
                    )
                }
            }
            st.folderScanInfo?.let { Caption(it) }
            if (st.folderDaCandidates.isNotEmpty()) {
                Caption("DA cocok ditemukan di folder:")
                st.folderDaCandidates.forEach { c ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Caption(c.name)
                        GlassButton("Pakai Ini", { s.flashTool.useFolderDa(c) })
                    }
                }
            }
            st.daSummary?.let {
                Caption("Hasil pencocokan file DA:")
                Mono(it)
            }
            if (st.stage == FlashStage.DA_READY || st.stage == FlashStage.XFLASH_SYNCING || st.stage == FlashStage.XFLASH_READY) {
                Body("DA sudah hidup dan siap. SEND_DA/JUMP_DA tidak menyentuh penyimpanan permanen.", color = g.good)
            }
        }
    }

    if (st.stage == FlashStage.DA_READY || st.stage == FlashStage.XFLASH_SYNCING || st.stage == FlashStage.XFLASH_READY ||
        st.stage == FlashStage.EMI_SENDING || st.stage == FlashStage.EMI_ACCEPTED ||
        st.stage == FlashStage.DA2_SENDING || st.stage == FlashStage.DA2_READY ||
        st.stage == FlashStage.GPT_READING || st.stage == FlashStage.GPT_READY
    ) {
        GlassCard {
            Heading("Tahap 3 — Buka kanal command XFlash")
            GlassButton(
                if (st.stage == FlashStage.XFLASH_SYNCING) "Membuka kanal…" else "Buka Kanal & Minta Versi DA",
                { s.flashTool.xflashHandshake() },
                primary = true,
                enabled = st.stage != FlashStage.XFLASH_SYNCING && st.stage != FlashStage.EMI_SENDING
            )
            if (st.stage == FlashStage.XFLASH_READY || st.stage == FlashStage.EMI_SENDING || st.stage == FlashStage.EMI_ACCEPTED) {
                Caption("Kanal XFlash terbuka:")
                Mono(st.info ?: "-")
            }
        }
    }

    if (st.stage == FlashStage.XFLASH_READY || st.stage == FlashStage.EMI_SENDING || st.stage == FlashStage.EMI_ACCEPTED ||
        st.stage == FlashStage.DA2_SENDING || st.stage == FlashStage.DA2_READY ||
        st.stage == FlashStage.GPT_READING || st.stage == FlashStage.GPT_READY
    ) {
        GlassCard {
            Heading("Tahap 4 — Kalibrasi DRAM (kirim EMI)")
            val catalogPreloader = st.catalogMatches.firstOrNull { it.preloaderAsset != null }
            if (catalogPreloader != null && st.stage == FlashStage.XFLASH_READY) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Caption("Preloader bawaan: ${catalogPreloader.label}")
                    GlassButton("Pakai Otomatis", { s.flashTool.useCatalogPreloader(catalogPreloader) }, primary = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    if (st.stage == FlashStage.EMI_SENDING) "Mengirim EMI…" else "Kirim EMI dari Preloader",
                    { s.flashTool.sendSelectedPreloader() },
                    enabled = st.stage != FlashStage.EMI_SENDING && files.preloaderName != null,
                    primary = files.preloaderName != null
                )
                GlassButton("Cari di Folder", { folderPicker.launch(null) }, enabled = st.stage != FlashStage.EMI_SENDING)
            }
            if (st.folderPreloaderCandidates.isNotEmpty()) {
                Caption("Kandidat preloader di folder (berdasar nama file, belum divalidasi):")
                st.folderPreloaderCandidates.forEach { c ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Caption(c.name)
                        GlassButton("Coba Ini", { s.flashTool.useFolderPreloader(c) })
                    }
                }
            }
            if (st.stage == FlashStage.EMI_ACCEPTED || st.stage == FlashStage.DA2_SENDING || st.stage == FlashStage.DA2_READY ||
        st.stage == FlashStage.GPT_READING || st.stage == FlashStage.GPT_READY) {
                Body("EMI diterima.", color = g.good)
            }
        }
    }

    if (st.stage == FlashStage.EMI_ACCEPTED || st.stage == FlashStage.DA2_SENDING || st.stage == FlashStage.DA2_READY ||
        st.stage == FlashStage.GPT_READING || st.stage == FlashStage.GPT_READY) {
        GlassCard {
            Heading("Tahap 5 — Upload DA2 & jalankan")
            Body(
                "Paling tidak pasti sejauh ini: upload DA2 lalu BOOT_TO. Kalau device reconnect USB di titik ini " +
                    "(perilaku yang diketahui terjadi di beberapa chip), langkah ini akan error dengan jelas — " +
                    "solusinya pindai ulang dari Tahap 1, bukan ulangi tombol ini berkali-kali. Saat MULAI dipakai, " +
                    "langkah ini berjalan otomatis setelah EMI diterima.",
                dim = true
            )
            GlassButton(
                if (st.stage == FlashStage.DA2_SENDING) "Mengupload DA2…" else "Upload DA2 & BOOT_TO",
                { s.flashTool.uploadDa2() },
                primary = true,
                enabled = st.stage == FlashStage.EMI_ACCEPTED
            )
            if (st.stage == FlashStage.DA2_READY || st.stage == FlashStage.GPT_READING || st.stage == FlashStage.GPT_READY) {
                Body("DA2 ter-upload & BOOT_TO diterima.", color = g.good)
            }
        }
    }

    if (st.stage == FlashStage.DA2_READY || st.stage == FlashStage.GPT_READING || st.stage == FlashStage.GPT_READY) {
        GlassCard {
            Heading("Tahap 6 — Baca partition table (GPT)")
            Body(
                "Baca 34 sektor awal storage (MBR + header GPT + tabel entri) lewat command READ_DATA, lalu " +
                    "parse sebagai GPT standar. Murni baca, tidak menulis apa pun. Asumsi yang belum terkonfirmasi: " +
                    "tipe storage (dipakai eMMC) dan kode \"area utama\" — kalau salah, device akan menolak dengan " +
                    "status error jelas, atau signature GPT tidak ketemu (dilaporkan sebagai error, bukan data palsu).",
                dim = true
            )
            GlassButton(
                if (st.stage == FlashStage.GPT_READING) "Membaca GPT…" else "Baca Partition Table",
                { s.flashTool.readPartitionTable() },
                primary = true,
                enabled = st.stage == FlashStage.DA2_READY
            )
            if (st.stage == FlashStage.GPT_READY) {
                Caption("${st.gptEntries.size} partisi terbaca:")
                st.gptEntries.forEach { p ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Caption(p.name)
                        Caption(ScatterFile.formatSize(p.sizeBytes))
                        GlassButton("Tulis ke sini", {
                            writeTargetPartition = p.name
                            imagePicker.launch(arrayOf("*/*"))
                        })
                    }
                }
            }
        }
    }

    if (st.stage == FlashStage.GPT_READY || st.stage == FlashStage.WRITING || st.stage == FlashStage.WRITE_DONE) {
        GlassCard {
            Heading("Tahap 7 — Tulis partisi tunggal")
            Body(
                "MENULIS KE PENYIMPANAN PERMANEN. Pilih file dari daftar partisi Tahap 6 lewat tombol " +
                    "\"Tulis ke sini\". File yang lebih besar dari ukuran partisi akan DIBLOKIR otomatis — " +
                    "menulis melebihi batas partisi bisa menimpa partisi tetangganya.",
                dim = true
            )
            when (st.stage) {
                FlashStage.WRITING -> Body("Sedang menulis… jangan cabut kabel USB.", color = g.warn)
                FlashStage.WRITE_DONE -> Body(st.info ?: "Tulis selesai.", color = g.good)
                else -> {}
            }
        }
    }

    val pending = st.writePending
    if (pending != null) {
        GlassCard(strong = true) {
            Heading("Konfirmasi tulis partisi")
            KeyValue("Partisi", pending.partitionName)
            KeyValue("Ukuran partisi", ScatterFile.formatSize(pending.entry.sizeBytes))
            KeyValue("Ukuran file", ScatterFile.formatSize(pending.imageSize))
            if (!pending.fitsInPartition) {
                Body(
                    "DIBLOKIR: file ini lebih besar dari partisi tujuan. Tidak bisa dilanjutkan — " +
                        "pastikan ini file yang benar untuk partisi \"${pending.partitionName}\".",
                    color = g.bad
                )
                GlassButton("Batal", { s.flashTool.cancelWrite() })
            } else {
                Body(
                    "Tindakan ini tidak bisa dibatalkan setelah dimulai. Pastikan ini file yang benar " +
                        "untuk partisi \"${pending.partitionName}\" sebelum lanjut.",
                    color = g.warn
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Batal", { s.flashTool.cancelWrite() })
                    GlassButton("Ya, Tulis Sekarang", { s.flashTool.confirmWrite() }, primary = true)
                }
            }
        }
    }
}

/** Satu baris pilihan file: label + nama file terpilih di kiri, tombol pilih (+ bersihkan) di kanan. */
@Composable
private fun FileSlot(
    label: String,
    fileName: String?,
    hint: String,
    onPick: () -> Unit,
    onClear: (() -> Unit)?,
    pickLabel: String = "Pilih $label"
) {
    val g = LocalGlass.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Caption(label)
                androidx.compose.material3.Text(
                    fileName ?: "Belum dipilih",
                    color = if (fileName != null) g.good else g.text,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            GlassButton(pickLabel, onPick, icon = Icons.Rounded.FileOpen)
            if (onClear != null && fileName != null) {
                Spacer(Modifier.width(6.dp))
                GlassButton("Hapus", onClear, icon = Icons.Rounded.Close)
            }
        }
        Caption(hint)
    }
}
