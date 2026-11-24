package com.senzaproject.senzatools.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.platform.root.AccessShell
import com.senzaproject.senzatools.platform.root.ImeiValidator
import com.senzaproject.senzatools.platform.root.ImeiWriteReport
import com.senzaproject.senzatools.platform.root.ImeiWriter
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.ui.theme.LocalGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tulis IMEI manual (1 atau 2 IMEI) untuk HP yang file backup IMEI-nya sudah hilang dan hanya punya angka dari box/label.
 * Berjalan di HP tempat app ini terpasang lewat root + perintah AT ke modem — bukan lewat sesi BROM.
 */
@Composable
fun ImeiManualCard(s: ServiceLocator) {
    val g = LocalGlass.current
    val scope = rememberCoroutineScope()
    val rootOk = s.access.state.collectAsState().let { s.access.isRootVerified }
    val writer = remember { ImeiWriter(s.app.filesDir, AccessShell(s.access)) }

    var imei1 by remember { mutableStateOf("") }
    var imei2 by remember { mutableStateOf("") }
    var understood by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ImeiWriteReport?>(null) }

    val e1 = if (imei1.length == 15) ImeiValidator.check(imei1) else null
    val e2 = when {
        imei2.length != 15 -> null
        imei2 == imei1 -> "IMEI 2 tidak boleh sama dengan IMEI 1."
        else -> ImeiValidator.check(imei2)
    }
    val imei2Ok = imei2.isEmpty() || (imei2.length == 15 && e2 == null)
    val canWrite = rootOk && !busy && imei1.length == 15 && e1 == null && imei2Ok && understood

    GlassCard {
        Heading("Tulis IMEI Manual (Root)")
        Body(
            "Untuk HP yang backup IMEI-nya hilang. Isi IMEI persis seperti yang tertera di box atau label HP ini. " +
                "Berjalan di HP tempat app ini terpasang (bukan HP yang tersambung lewat BROM), memakai root dan perintah AT ke modem.",
            dim = true
        )
        Body(
            "Hanya untuk mengembalikan IMEI ASLI HP ini. Mengubah IMEI menjadi nomor lain melanggar hukum di banyak negara.",
            color = g.warn
        )
        if (!rootOk) Caption("Butuh akses root. Izinkan di root manager, lalu tekan Minta izin root di Tools → Akses.", color = g.warn)

        GlassTextField(imei1, { imei1 = ImeiValidator.digitsOnly(it).take(15) }, "IMEI 1 (15 digit)", mono = true, keyboard = KeyboardType.Number)
        Caption(e1 ?: "${imei1.length}/15", color = if (e1 != null) g.bad else null)
        GlassTextField(imei2, { imei2 = ImeiValidator.digitsOnly(it).take(15) }, "IMEI 2 (opsional, 15 digit)", mono = true, keyboard = KeyboardType.Number)
        Caption(e2 ?: "${imei2.length}/15", color = if (e2 != null) g.bad else null)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = understood,
                onCheckedChange = { understood = it },
                colors = CheckboxDefaults.colors(checkedColor = g.accent, uncheckedColor = g.textDim)
            )
            Body("Angka di atas sama dengan IMEI asli HP ini (box/label)", modifier = Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Tulis IMEI", { confirm = true }, Modifier.weight(1.3f), primary = true, enabled = canWrite)
            GlassButton(
                "Baca IMEI",
                {
                    scope.launch {
                        busy = true
                        report = withContext(Dispatchers.IO) { writer.read() }
                        busy = false
                    }
                },
                Modifier.weight(1f), enabled = rootOk && !busy
            )
        }
        if (busy) Spinner("Bicara dengan modem… (bisa sampai ± 1 menit, jangan tutup app)")
        Caption("Setelah menulis: restart HP lalu cek dengan *#06#.")
    }

    report?.let { r ->
        GlassCard(strong = true) {
            Heading(r.title)
            val tone = if (r.ok) g.good else if (r.uncertain) g.warn else g.bad
            r.lines.forEach { Body(it, color = tone) }
            GlassButton("Tutup", { report = null })
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = "Tulis IMEI ke modem?",
            message = "IMEI 1: $imei1" + (if (imei2.isNotEmpty()) "\nIMEI 2: $imei2" else "") +
                "\n\nPastikan sama persis dengan yang tertera di box/label HP ini. Nilai lama dibaca dulu dan ditampilkan di laporan.",
            confirmText = "Ya, Tulis",
            danger = true,
            onConfirm = {
                confirm = false
                scope.launch {
                    busy = true
                    report = withContext(Dispatchers.IO) { writer.write(imei1, imei2.ifEmpty { null }) }
                    busy = false
                }
            },
            onDismiss = { confirm = false }
        )
    }
}
