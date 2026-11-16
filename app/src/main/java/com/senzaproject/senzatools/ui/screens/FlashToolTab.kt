package com.senzaproject.senzatools.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.platform.flashtool.FlashStage
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

    val daPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) s.flashTool.pickDaFile(uri)
    }
    val preloaderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) s.flashTool.pickPreloaderAndSendEmi(uri)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) s.flashTool.searchFolder(uri)
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
                    FlashStage.ERROR -> "Error"
                },
                when (st.stage) {
                    FlashStage.IDENTIFIED, FlashStage.DA_MATCHED -> g.good
                    FlashStage.DA_READY, FlashStage.XFLASH_READY, FlashStage.EMI_ACCEPTED, FlashStage.DA2_READY -> g.good
                    FlashStage.ERROR -> g.bad
                    FlashStage.IDLE -> g.textDim
                    else -> g.warn
                }
            )
        }
        Body(
            "Khusus device Transsion OS (Infinix/Tecno/Itel) chipset MediaTek. Tahap 2: kirim file DA " +
                "(Download Agent) yang sudah kamu siapkan sendiri, device menjalankannya di RAM, dan kita " +
                "tunggu konfirmasi DA hidup. SEND_DA hanya menulis ke RAM volatil — belum menyentuh " +
                "penyimpanan permanen device, jadi aman diulang kalau gagal.",
            dim = true
        )
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
            FlashStage.EMI_SENDING, FlashStage.EMI_ACCEPTED, FlashStage.DA2_SENDING, FlashStage.DA2_READY -> {
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

    val tahap2Visible = st.stage == FlashStage.IDENTIFIED || st.stage == FlashStage.DA_PARSING ||
        st.stage == FlashStage.DA_MATCHED || st.stage == FlashStage.DA_SENDING ||
        st.stage == FlashStage.DA_READY || st.stage == FlashStage.XFLASH_SYNCING || st.stage == FlashStage.XFLASH_READY ||
        st.stage == FlashStage.EMI_SENDING || st.stage == FlashStage.EMI_ACCEPTED ||
        st.stage == FlashStage.DA2_SENDING || st.stage == FlashStage.DA2_READY

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
            Body("Pilih file DA (Download Agent) secara manual, atau cari dari folder firmware kamu sendiri.", dim = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    "Pilih file DA",
                    { daPicker.launch(arrayOf("*/*")) },
                    enabled = st.stage != FlashStage.DA_PARSING && st.stage != FlashStage.DA_SENDING
                )
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
        st.stage == FlashStage.EMI_SENDING || st.stage == FlashStage.EMI_ACCEPTED
    ) {
        GlassCard {
            Heading("Tahap 3 — Buka kanal command XFlash")
            Body(
                "Minta versi DA lewat kanal command XFlash sebagai bukti komunikasi dua arah jalan.",
                dim = true
            )
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

    if (st.stage == FlashStage.XFLASH_READY || st.stage == FlashStage.EMI_SENDING || st.stage == FlashStage.EMI_ACCEPTED) {
        GlassCard {
            Heading("Tahap 4 — Kalibrasi DRAM (kirim EMI)")
            Body(
                "Preloader.bin ASLI untuk device ini (bukan file DA) dipakai untuk ambil data EMI lalu " +
                    "dikirim ke DA sebagai kalibrasi DRAM. Masih konfigurasi RAM — belum menyentuh eMMC/UFS.",
                dim = true
            )
            val catalogPreloader = st.catalogMatches.firstOrNull { it.preloaderAsset != null }
            if (catalogPreloader != null && st.stage == FlashStage.XFLASH_READY) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Caption("Preloader bawaan: ${catalogPreloader.label}")
                    GlassButton("Pakai Otomatis", { s.flashTool.useCatalogPreloader(catalogPreloader) }, primary = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    if (st.stage == FlashStage.EMI_SENDING) "Mengirim EMI…" else "Pilih preloader.bin manual",
                    { preloaderPicker.launch(arrayOf("*/*")) },
                    enabled = st.stage != FlashStage.EMI_SENDING
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
            if (st.stage == FlashStage.EMI_ACCEPTED) {
                Body(st.info ?: "EMI diterima.", color = g.good)
            }
        }
    }

    if (st.stage == FlashStage.EMI_ACCEPTED || st.stage == FlashStage.DA2_SENDING || st.stage == FlashStage.DA2_READY) {
        GlassCard {
            Heading("Tahap 5 — Upload DA2 & jalankan")
            Body(
                "Paling tidak pasti sejauh ini: upload DA2 lalu BOOT_TO. Kalau device reconnect USB di titik ini " +
                    "(perilaku yang diketahui terjadi di beberapa chip), langkah ini akan error dengan jelas — " +
                    "solusinya pindai ulang dari Tahap 1, bukan ulangi tombol ini berkali-kali.",
                dim = true
            )
            GlassButton(
                if (st.stage == FlashStage.DA2_SENDING) "Mengupload DA2…" else "Upload DA2 & BOOT_TO",
                { s.flashTool.uploadDa2() },
                primary = true,
                enabled = st.stage != FlashStage.DA2_SENDING
            )
            if (st.stage == FlashStage.DA2_READY) {
                Body(st.info ?: "DA2 siap.", color = g.good)
            }
        }
    }

    GlassCard {
        Heading("Status fitur ke depan")
        Body(
            "• Baca info storage (eMMC/UFS) & scatter/partition table — belum dibuat\n" +
                "• Flash partisi tunggal (boot, vbmeta, vendor_boot, dll) + verifikasi — belum dibuat\n" +
                "• Flash ROM penuh dari apk + wipe + auto-restart + fallback recovery: belum dibuat\n" +
                "Semua akan ditambahkan bertahap setelah tahap ini terbukti jalan di device aslimu.",
            dim = true
        )
    }
}
