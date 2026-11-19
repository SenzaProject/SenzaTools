package com.senzaproject.senzatools.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.platform.flashtool.ScatterFile
import com.senzaproject.senzatools.platform.romflash.RomFlashEngine
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.ui.theme.LocalGlass
import kotlinx.coroutines.launch

/**
 * Flash ROM (Custom ROM) lewat root — jalur TERPISAH dari Flash Tool (BROM/DA via USB).
 * Aturan keras: flash HANYA ke slot tidak aktif (lihat RomFlashEngine untuk alasannya).
 * Wipe data + reboot adalah langkah terpisah, irreversible, perlu ketik "WIPE" untuk konfirmasi.
 */
@Composable
fun RomFlashTab(s: ServiceLocator) {
    val g = LocalGlass.current
    val scope = rememberCoroutineScope()
    val st by s.romFlash.state.collectAsState()
    var pickTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var wipeConfirmText by rememberSaveable { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetName = pickTarget
        val target = st.partitions.firstOrNull { it.baseName == targetName }
        if (uri != null && target != null) scope.launch { s.romFlash.pickImage(target, uri) }
    }

    GlassCard(strong = true) {
        Heading("Flash ROM — via Root (bukan BROM)")
        Body(
            "Flash partisi HANYA ke slot yang sedang TIDAK aktif — slot yang sedang berjalan sekarang " +
                "tidak pernah disentuh sama sekali. Ini jaring pengaman utamanya: kalau ROM baru gagal " +
                "boot, slot lama tetap utuh dan bisa dipakai lagi. \"Auto-fallback ke recovery\" TIDAK " +
                "diimplementasikan sebagai kode aplikasi — itu bergantung pada mekanisme bootloader A/B " +
                "bawaan Android, bukan sesuatu yang bisa dijamin dari sisi apk.",
            dim = true
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            KeyValue("Slot aktif sekarang", st.currentSlot?.let { "_$it" } ?: "-")
            KeyValue("Slot target flash", st.targetSlot?.let { "_$it" } ?: "-")
        }
        GlassButton(
            if (st.stage == RomFlashEngine.Stage.DETECTING) "Mendeteksi…" else "Deteksi Slot & Partisi",
            { scope.launch { s.romFlash.detectSlot() } },
            primary = true,
            enabled = st.stage != RomFlashEngine.Stage.DETECTING && st.stage != RomFlashEngine.Stage.FLASHING && st.stage != RomFlashEngine.Stage.WIPING
        )
        if (st.stage == RomFlashEngine.Stage.ERROR) {
            Body(st.errorMessage ?: "Error tidak diketahui", color = g.bad)
        }
    }

    if (st.partitions.isNotEmpty()) {
        GlassCard {
            Heading("Partisi slot target (_${st.targetSlot})")
            st.partitions.forEach { p ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Caption("${p.baseName}  (${ScatterFile.formatSize(p.deviceSizeBytes)})")
                    GlassButton("Pilih gambar", {
                        pickTarget = p.baseName
                        imagePicker.launch(arrayOf("*/*"))
                    })
                }
            }
        }
    }

    val pending = st.pendingConfirm
    if (pending != null) {
        GlassCard(strong = true) {
            Heading("Konfirmasi tambah ke antrean")
            KeyValue("Partisi", pending.target.baseName)
            KeyValue("Ukuran partisi", ScatterFile.formatSize(pending.target.deviceSizeBytes))
            KeyValue("Ukuran file", ScatterFile.formatSize(pending.imageSizeBytes))
            if (!pending.fits) {
                Body("DIBLOKIR: file lebih besar dari partisi tujuan.", color = g.bad)
                GlassButton("Batal", { s.romFlash.cancelPending() })
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Batal", { s.romFlash.cancelPending() })
                    GlassButton("Tambah ke Antrean", { s.romFlash.confirmQueue() }, primary = true)
                }
            }
        }
    }

    if (st.queuedFlashes.isNotEmpty()) {
        GlassCard {
            Heading("Antrean flash (${st.queuedFlashes.size})")
            st.queuedFlashes.forEach { q ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Caption("${q.target.baseName} <- ${ScatterFile.formatSize(q.imageSizeBytes)}")
                    GlassButton("Hapus", { s.romFlash.removeFromQueue(q.target) })
                }
            }
            GlassButton(
                if (st.stage == RomFlashEngine.Stage.FLASHING) "Sedang flash…" else "Mulai Flash ke Slot Tidak Aktif",
                { scope.launch { s.romFlash.flashQueueToInactiveSlot() } },
                primary = true,
                enabled = st.stage != RomFlashEngine.Stage.FLASHING
            )
            if (st.stage == RomFlashEngine.Stage.FLASHING) {
                Body("Sedang menulis… jangan tutup aplikasi atau cabut apa pun.", color = g.warn)
            }
        }
    }

    if (st.stage == RomFlashEngine.Stage.FLASH_DONE || st.stage == RomFlashEngine.Stage.WIPING) {
        GlassCard(strong = true) {
            Heading("Flash selesai — wipe data & reboot")
            Body(
                "Slot aktif sudah dipindah ke _${st.targetSlot}. Langkah ini WIPE SELURUH DATA PENGGUNA " +
                    "(factory reset) lewat recovery, lalu device reboot otomatis ke ROM baru. TIDAK BISA " +
                    "DIBATALKAN setelah ditekan. Ketik \"WIPE\" untuk mengaktifkan tombol.",
                color = g.bad
            )
            OutlinedTextField(
                value = wipeConfirmText,
                onValueChange = { wipeConfirmText = it },
                label = { androidx.compose.material3.Text("Ketik WIPE untuk konfirmasi") },
                modifier = Modifier.fillMaxWidth()
            )
            GlassButton(
                if (st.stage == RomFlashEngine.Stage.WIPING) "Memproses…" else "Wipe Data & Reboot Sekarang",
                { scope.launch { s.romFlash.wipeDataAndReboot() } },
                primary = true,
                enabled = wipeConfirmText.trim() == "WIPE" && st.stage == RomFlashEngine.Stage.FLASH_DONE
            )
        }
    }

    if (st.log.isNotEmpty()) {
        GlassCard {
            Heading("Riwayat")
            st.log.takeLast(20).forEach { Caption(it) }
        }
    }
}
