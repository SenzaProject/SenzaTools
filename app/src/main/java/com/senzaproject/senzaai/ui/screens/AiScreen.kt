package com.senzaproject.senzaai.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.senzaproject.senzaai.di.ServiceLocator
import com.senzaproject.senzaai.ui.ChatViewModel
import com.senzaproject.senzaai.ui.components.*
import com.senzaproject.senzaai.ui.theme.LocalGlass
import com.senzaproject.senzaai.utils.Saf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiScreen(s: ServiceLocator, vm: ChatViewModel) {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    val scope = rememberCoroutineScope()
    var diagBusy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val settings = remember(vm.messages.size, vm.busy) { s.aiSettings.load() }
    val report = s.session.lastDiagnostic

    val pickLog = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { Saf.readText(ctx, uri, 2 * 1024 * 1024) }
                s.session.lastLog = text.takeLast(60_000)
                vm.attach[ChatViewModel.LOG] = true
            } catch (_: Throwable) {
            }
        }
    }

    ScreenScaffold("AI Assistant", "Diagnostik lokal + asisten AI untuk sistem Android") {

        // ---------------------------------------------------------------- diagnostik lokal
        GlassCard(strong = true) {
            Heading("Senza Smart Diagnostics")
            Body("Pemeriksaan lokal (tanpa internet): baterai, RAM, penyimpanan, suhu, SELinux, patch keamanan, dan konfigurasi build. Setiap temuan menyertakan bukti dan tingkat kepastian.", dim = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(if (diagBusy) "Memeriksa…" else "Jalankan diagnostik", {
                    if (!diagBusy) scope.launch {
                        diagBusy = true
                        s.session.lastDiagnostic = withContext(Dispatchers.IO) { s.diagnostics.run() }
                        diagBusy = false
                    }
                }, Modifier.weight(1f), primary = true, enabled = !diagBusy)
                GlassButton("Bagikan laporan", {
                    val txt = report?.let { s.diagnostics.toText(it) } ?: return@GlassButton
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, txt) }
                    ctx.startActivity(Intent.createChooser(send, "Bagikan laporan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, Modifier.weight(1f), enabled = report != null)
            }
        }
        report?.findings?.forEach { f ->
            GlassCard {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Heading(f.title, Modifier.weight(1f))
                    SeverityPill(f.severity)
                }
                KeyValue("Bukti", f.evidence)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Caption("Kepastian")
                    CertaintyPill(f.certainty)
                }
                Body("Rekomendasi: ${f.recommendation}", dim = true)
            }
        }

        // ---------------------------------------------------------------- chat
        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Heading("Tanya Senza AI")
                StatusPill(if (settings.isReady) settings.provider.label else "Belum diatur", if (settings.isReady) g.good else g.warn)
            }
            if (!settings.isReady) {
                Body("Isi API key, base URL (https://), dan model di Settings → AI untuk memakai asisten. Diagnostik lokal di atas tetap berfungsi tanpa AI.", dim = true)
            } else {
                Caption("Model: ${settings.model}")
            }

            Caption("Lampiran (dikirim ke layanan AI hanya bila dicentang):")
            val avail = vm.available()
            ChipRow(avail, -1) { vm.toggle(avail[it]) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                avail.forEach { k -> if (vm.attach[k] == true) StatusPill("✓ $k", g.accent2) }
            }
            GlassButton("Lampirkan file log/teks…", { pickLog.launch(arrayOf("*/*")) })

            Caption("Contoh pertanyaan:")
            ChipRow(
                listOf("Jelaskan ro.vndk.version", "Analisis log terlampir", "Kenapa bootloop?", "Cek kompatibilitas ROM"),
                -1
            ) {
                input = when (it) {
                    0 -> "Jelaskan fungsi properti ro.vndk.version dan akibatnya jika tidak cocok."
                    1 -> "Analisis log terlampir. Pisahkan temuan terbukti, kemungkinan penyebab, dan yang belum dapat dipastikan."
                    2 -> "Perangkat saya bootloop setelah flash ROM. Apa saja yang perlu saya periksa berdasarkan data terlampir?"
                    else -> "Berdasarkan data terlampir, apa risiko ketidakcocokan ROM ini dengan perangkat saya?"
                }
            }
        }

        vm.messages.forEach { m ->
            val mine = m.role == "user"
            GlassCard(strong = mine) {
                Caption(if (mine) "Kamu" else "Senza AI", color = if (mine) g.accent else g.accent2)
                SelectionContainer { Body(m.content) }
            }
        }
        if (vm.busy) Spinner("Senza AI sedang menganalisis…")
        vm.error?.let { ErrorCard(it) }

        GlassCard {
            GlassTextField(input, { input = it }, "Tulis pertanyaan", singleLine = false, minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Kirim", {
                    val t = input
                    input = ""
                    vm.send(t)
                }, Modifier.weight(1f), primary = true, enabled = !vm.busy && input.isNotBlank())
                GlassButton("Bersihkan", { vm.clear() }, Modifier.weight(1f), enabled = vm.messages.isNotEmpty())
            }
            Caption("AI tidak dapat menjalankan perintah di perangkatmu. Jawaban bisa keliru; verifikasi sebelum menerapkan, terutama perintah sistem.")
        }
    }
}
