package com.senzaproject.senzatools.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.platform.flashtool.FlashStage
import com.senzaproject.senzatools.platform.flashtool.ImeiPackage
import com.senzaproject.senzatools.platform.flashtool.ScatterFile
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.ui.theme.LocalGlass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup & Restore IMEI. Muncul setelah partition table (GPT) terbaca. Semua tombol memanggil
 * ImeiEngine yang benar-benar membaca/menulis partisi device lewat sesi XFlash.
 */
@Composable
fun ImeiSection(s: ServiceLocator) {
    val g = LocalGlass.current
    val st by s.flashTool.state.collectAsState()
    val im by s.flashTool.imei.state.collectAsState()
    val engine = s.flashTool.imei

    val files by s.flashTool.files.collectAsState()

    // Kartu ini SELALU tampil. Dulu disembunyikan sampai GPT terbaca, jadi kalau sesi berhenti di tengah
    // (mis. Preloader belum dipilih) fiturnya seolah tidak ada.
    val ready = st.stage == FlashStage.GPT_READY || st.stage == FlashStage.WRITE_DONE

    val candidates = remember(st.gptEntries) { ImeiPackage.findCandidates(st.gptEntries) }
    var selected by remember(candidates) { mutableStateOf(candidates.map { it.name.lowercase() }.toSet()) }
    var understood by remember(im.plan) { mutableStateOf(false) }

    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) engine.backup(uri, selected)
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) engine.prepareRestore(uri)
    }

    GlassCard {
        Heading("Backup & Restore IMEI")
        Body(
            "Membaca/menulis partisi penyimpan IMEI (NVRAM) langsung dari device lewat sesi yang sudah hidup. " +
                "Backup = salinan utuh partisi + hash SHA-256, lalu file dibaca ulang untuk verifikasi. " +
                "Restore = verifikasi file, simpan snapshot kondisi sekarang, tulis, lalu baca ulang dan bandingkan hash.",
            dim = true
        )
        if (!ready) {
            val (stepNo, stepLabel) = when (st.stage) {
                FlashStage.IDLE, FlashStage.WAITING_DEVICE, FlashStage.PERMISSION_NEEDED, FlashStage.HANDSHAKING -> 1 to "Device belum terdeteksi lewat BROM"
                FlashStage.IDENTIFIED, FlashStage.DA_PARSING, FlashStage.DA_MATCHED, FlashStage.DA_SENDING -> 2 to "Chip terdeteksi, DA belum dijalankan"
                FlashStage.DA_READY, FlashStage.XFLASH_SYNCING -> 3 to "DA hidup, kanal XFlash belum terbuka"
                FlashStage.XFLASH_READY, FlashStage.EMI_SENDING -> 4 to "Kanal XFlash terbuka, kalibrasi DRAM (EMI) belum selesai"
                FlashStage.EMI_ACCEPTED, FlashStage.DA2_SENDING -> 5 to "EMI diterima, DA2 belum berjalan"
                FlashStage.DA2_READY, FlashStage.GPT_READING -> 6 to "DA2 jalan, partition table belum terbaca"
                FlashStage.ERROR -> 0 to "Sesi gagal di tahap: ${st.errorStage ?: "?"}"
                else -> 0 to "Sesi belum siap"
            }
            Body("Belum bisa dipakai. Status sesi: $stepLabel" + if (stepNo > 0) " (tahap $stepNo dari 6)." else ".", color = g.warn)
            if (st.stage == FlashStage.ERROR) st.errorMessage?.let { Caption(it, color = g.bad) }
            if (files.preloaderName == null) {
                Body(
                    "Backup/Restore IMEI butuh sesi sampai tahap GPT, dan itu butuh file Preloader (kalibrasi DRAM). " +
                        "Pilih Preloader di kartu Sesi bila belum ada preloader bawaan untuk chip ini.",
                    dim = true
                )
            }
            files.note?.let { Caption(it, color = g.warn) }
            Caption("Cara: masuk mode BROM, pilih File DA (+ Preloader), lalu tekan tombol di bawah. Tombol Backup/Restore aktif otomatis begitu partisi terbaca.")
            GlassButton(
                when {
                    files.waiting -> "Menunggu device…"
                    files.running -> "Menyiapkan sesi…"
                    else -> "Siapkan Sesi IMEI"
                },
                { s.flashTool.start() },
                Modifier.fillMaxWidth(), primary = true,
                enabled = files.daName != null && !files.running
            )
            if (files.daName == null) Caption("Pilih File DA dulu di kartu Sesi.", color = g.warn)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Backup IMEI", {}, Modifier.weight(1f), enabled = false)
                GlassButton("Restore IMEI", {}, Modifier.weight(1f), enabled = false)
            }
        }
        if (ready) {
            if (candidates.isEmpty()) {
                Body("Tidak ada partisi IMEI (nvram/nvdata/nvcfg/protect1/protect2/proinfo) di GPT device ini.", color = g.bad)
            } else {
                Caption("Partisi IMEI terdeteksi di GPT:")
                candidates.forEach { p ->
                    val key = p.name.lowercase()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = key in selected,
                            onCheckedChange = { c -> selected = if (c) selected + key else selected - key },
                            enabled = !im.busy,
                            colors = CheckboxDefaults.colors(checkedColor = g.accent, uncheckedColor = g.textDim)
                        )
                        Column(Modifier.weight(1f)) {
                            Body(p.name)
                            Caption(ScatterFile.formatSize(p.sizeBytes))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(
                        "Backup IMEI",
                        {
                            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            backupPicker.launch("senza_imei_backup_$stamp.zip")
                        },
                        Modifier.weight(1f), primary = true,
                        enabled = !im.busy && selected.isNotEmpty()
                    )
                    GlassButton(
                        "Restore IMEI",
                        { restorePicker.launch(arrayOf("*/*")) },
                        Modifier.weight(1f), enabled = !im.busy
                    )
                }
                if (im.snapshotName != null) {
                    GlassButton("Pulihkan Snapshot Terakhir", { engine.prepareRestoreFromSnapshot() }, enabled = !im.busy)
                    Caption("Snapshot: ${im.snapshotName}")
                }
            }
        }
        if (im.busy) {
            Spinner(im.step ?: "Bekerja…")
            BarMeter(im.progress * 100f)
            if (im.op == "restore" && !im.cancellable) {
                Body("Sedang menulis ke penyimpanan device — JANGAN cabut kabel USB dan jangan tutup aplikasi.", color = g.warn)
            }
            if (im.cancellable) GlassButton("Batalkan", { engine.cancel() }, danger = true)
        }
    }

    im.report?.let { r ->
        GlassCard(strong = true) {
            Heading(r.title)
            r.lines.forEach { Body(it, color = if (r.ok) g.good else g.bad) }
            GlassButton("Tutup", { engine.dismissReport() })
        }
    }

    im.plan?.let { p ->
        GlassCard(strong = true) {
            Heading("Konfirmasi Restore IMEI")
            KeyValue("Sumber", p.sourceLabel)
            if (p.createdMs > 0) KeyValue("Dibuat", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(p.createdMs)))
            KeyValue("HW code backup", "0x${p.hwCode.toString(16)}")
            p.items.forEach { Caption("• ${it.name} — ${ScatterFile.formatSize(it.sizeBytes)} — sha256 ${it.sha256.take(12)}…") }
            p.warnings.forEach { Body("⚠ $it", color = g.warn) }
            if (p.blockers.isNotEmpty()) {
                p.blockers.forEach { Body("DIBLOKIR: $it", color = g.bad) }
                GlassButton("Tutup", { engine.dismissPlan() })
            } else {
                Body(
                    "File lolos verifikasi hash dan cocok dengan layout partisi device. Restore MENIMPA partisi di atas. " +
                        "Sebelum menulis, kondisi sekarang disimpan sebagai snapshot. Pulihkan HANYA backup yang berasal " +
                        "dari perangkat ini sendiri — memakai backup perangkat lain akan menggandakan IMEI-nya.",
                    color = g.warn
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = understood,
                        onCheckedChange = { understood = it },
                        colors = CheckboxDefaults.colors(checkedColor = g.accent, uncheckedColor = g.textDim)
                    )
                    Body("Saya paham dan backup ini dari perangkat ini", modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Batal", { engine.dismissPlan() }, Modifier.weight(1f))
                    GlassButton(
                        "Ya, Pulihkan Sekarang", { engine.confirmRestore() }, Modifier.weight(1.4f),
                        primary = true, enabled = understood && !im.busy
                    )
                }
            }
        }
    }
}
