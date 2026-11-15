package com.senzaproject.senzatools.ui.screens

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
 * Flash Tool — Tahap 1: deteksi USB device MediaTek BROM/Preloader + identifikasi chip.
 * BELUM mengirim DA, BELUM flashing partisi apa pun (itu Tahap 2 ke atas).
 * Lihat komentar di BromProtocol.kt / FlashToolEngine.kt untuk batasan yang sudah diketahui.
 */
@Composable
fun FlashToolTab(s: ServiceLocator) {
    val g = LocalGlass.current
    val st by s.flashTool.state.collectAsState()

    DisposableEffect(Unit) { onDispose { s.flashTool.dispose() } }

    GlassCard(strong = true) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Heading("Flash Tool — Tahap 1: Deteksi BROM")
            StatusPill(
                when (st.stage) {
                    FlashStage.IDLE -> "Siap"
                    FlashStage.WAITING_DEVICE -> "Memindai…"
                    FlashStage.PERMISSION_NEEDED -> "Menunggu izin USB"
                    FlashStage.HANDSHAKING -> "Handshake…"
                    FlashStage.IDENTIFIED -> "Teridentifikasi"
                    FlashStage.ERROR -> "Error"
                },
                when (st.stage) {
                    FlashStage.IDENTIFIED -> g.good
                    FlashStage.ERROR -> g.bad
                    FlashStage.IDLE -> g.textDim
                    else -> g.warn
                }
            )
        }
        Body(
            "Khusus device Transsion OS (Infinix/Tecno/Itel) chipset MediaTek. Fitur ini baru " +
                "mendeteksi USB dan membaca identitas chip lewat BootROM — belum mengirim DA dan " +
                "belum mem-flash apa pun. Flash partisi tunggal menyusul setelah tahap ini terbukti " +
                "terbaca benar di device asli kamu.",
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
            FlashStage.IDENTIFIED -> {
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

    GlassCard {
        Heading("Status fitur ke depan")
        Body(
            "• Kirim DA (Download Agent) & akses memori/partisi: belum dibuat\n" +
                "• Flash partisi tunggal (boot, vbmeta, vendor_boot, dll): belum dibuat\n" +
                "• Flash ROM penuh dari apk + wipe + auto-restart + fallback recovery: belum dibuat\n" +
                "Semua akan ditambahkan bertahap setelah deteksi di tahap ini terbukti jalan di device aslimu.",
            dim = true
        )
    }
}
