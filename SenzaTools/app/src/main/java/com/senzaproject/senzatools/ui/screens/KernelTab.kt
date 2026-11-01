package com.senzaproject.senzatools.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.i18n.tr
import com.senzaproject.senzatools.kernel.*
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.ui.theme.LocalGlass
import kotlinx.coroutines.launch
import java.util.Locale

/** Operasi yang menunggu konfirmasi pengguna. */
private data class PendingOp(
    val title: String,
    val message: String,
    val danger: Boolean,
    val changes: List<Change>,
    val validate: Boolean = true
)

/**
 * Kernel Manager Senza (CPU, GPU, RAM, Thermal, I/O, Baterai, Lainnya). Logika node diporting dari SmartPack-Kernel-Manager.
 * Semua penulisan lewat [KernelEngine]: whitelist, validasi, baca-ulang, rollback, cadangan.
 */
@Composable
fun KernelTab(s: ServiceLocator) {
    val k = s.kernel
    val scope = rememberCoroutineScope()
    val rootOk = s.access.isRootVerified

    var scan by remember { mutableStateOf<KernelScan?>(null) }
    var zones by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var boot by remember { mutableStateOf<BootStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<PendingOp?>(null) }
    var group by rememberSaveable { mutableStateOf(0) }
    var snapName by remember { mutableStateOf("") }
    var snapVersion by remember { mutableStateOf(0) }
    val pending = remember { mutableStateMapOf<String, String>() }

    suspend fun refresh() {
        busy = true
        val sc = k.scan()
        scan = sc
        zones = k.thermalZones()
        boot = if (k.rootOk) k.bootStatus() else null
        // Buang perubahan tertunda yang sudah sama dengan nilai sekarang.
        pending.keys.toList().forEach { id -> if (sc.state(id)?.value == pending[id]) pending.remove(id) }
        busy = false
    }

    fun run(op: PendingOp) {
        scope.launch {
            busy = true; msg = null
            val sc = scan ?: k.scan()
            val rep = k.apply(sc, op.changes, op.validate)
            msg = when {
                rep.preflightError != null -> "Dibatalkan, tidak ada yang diubah: ${rep.preflightError}"
                else -> buildString {
                    append("${rep.okCount} nilai diterapkan dan diverifikasi.")
                    rep.failed.forEach { f ->
                        val name = KernelCatalog.byId[f.nodeId]?.title ?: f.nodeId
                        append("\n• $name: ${f.reason}")
                        if (f.rolledBack) append(" (nilai lama dipulihkan)")
                    }
                }
            }
            pending.keys.removeAll(op.changes.map { it.nodeId }.toSet())
            refresh()
            busy = false
        }
    }

    LaunchedEffect(rootOk) {
        refresh()
        k.confirmStable()
    }

    val sc = scan
    val groups = KGroup.values().toList()

    // ---------------------------------------------------------------- Kompatibilitas
    GlassCard {
        Heading("Kompatibilitas perangkat")
        if (sc == null) Spinner("Memindai node kernel")
        else {
            val d = sc.device
            val sm = sc.summary
            KeyValue("SoC", "${d.soc.label}${if (d.platform.isNotEmpty()) " (${d.platform})" else ""}")
            KeyValue("Kernel", d.kernel?.raw ?: "Tidak diketahui")
            KeyValue("Android", "SDK ${d.sdk}")
            KeyValue("SELinux", d.selinux)
            d.ramMb?.let { KeyValue("RAM", "$it MB") }
            KeyValue("Root", if (rootOk) "Terverifikasi" else "Belum")
            GlassDivider()
            Body("${sm.ready} dari ${sm.total} pengaturan bisa dipakai · ${sm.readOnly} baca-saja · ${sm.protectedNodes} dilindungi · ${sm.missing} tidak ada di kernel ini")
            KernelRules.deviceWarnings(d, rootOk).forEach { Caption("• $it") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Pindai ulang", { scope.launch { refresh() } }, Modifier.weight(1f), enabled = !busy)
            if (!rootOk) GlassButton("Minta izin root", { scope.launch { s.access.verifyRoot(); refresh() } }, Modifier.weight(1f), primary = true, enabled = !busy)
        }
    }

    msg?.let { m ->
        GlassCard(strong = true) {
            Body(m)
            GlassButton("Tutup", { msg = null })
        }
    }

    // ---------------------------------------------------------------- Perubahan tertunda
    if (pending.isNotEmpty() && sc != null) {
        GlassCard(strong = true) {
            Heading("${pending.size} perubahan belum diterapkan")
            pending.forEach { (id, v) ->
                val st = sc.state(id) ?: return@forEach
                Caption("• ${tr(st.node.title)}: ${st.value ?: "?"} → $v")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Batalkan", { pending.clear() }, Modifier.weight(1f), enabled = !busy)
                GlassButton("Terapkan", {
                    val changes = pending.map { Change(it.key, it.value) }
                    val nodes = changes.mapNotNull { sc.state(it.nodeId)?.node }
                    val high = nodes.any { it.risk == Risk.HIGH }
                    val summary = changes.joinToString("\n") { c ->
                        val st = sc.state(c.nodeId)
                        "• ${st?.node?.title}: ${st?.value} → ${c.value}"
                    }
                    confirm = PendingOp(
                        if (high) "Perubahan berisiko" else "Terapkan perubahan?",
                        summary + if (high) "\n\nAda pengaturan BERISIKO TINGGI (mis. proteksi panas). Salah atur bisa membuat perangkat terlalu panas atau tidak stabil. Nilai asli dicadangkan, tetapi kamu bertanggung jawab atas perubahan ini."
                        else "\n\nNilai dibaca ulang setelah ditulis; bila kernel menolak, nilai lama dipulihkan otomatis. Perubahan hilang saat restart kecuali “Terapkan saat boot” aktif.",
                        high, changes
                    )
                }, Modifier.weight(1f), primary = true, enabled = rootOk && !busy)
            }
        }
    }

    // ---------------------------------------------------------------- Kelompok
    ChipRow(groups.map { it.label }, group) { group = it }
    val g = groups[group]
    val groupStates = sc?.states?.filter { it.node.group == g }.orEmpty()

    if (g == KGroup.THERMAL && zones.isNotEmpty()) {
        GlassCard {
            Heading("Suhu zona termal")
            zones.take(8).forEach { (name, c) -> KeyValue(name, String.format(Locale.US, "%.1f °C", c)) }
        }
    }
    if (g == KGroup.CPU || g == KGroup.GPU) {
        Caption("Frekuensi, governor, dan profil CPU/GPU ada di tab “Performa”. Di sini: boost dan tunable tambahan.")
    }

    groupStates.filter { it.support !is Support.Missing }.forEach { st ->
        NodeCard(st, pending[st.node.id], rootOk && !busy) { v ->
            if (v == st.value) pending.remove(st.node.id) else pending[st.node.id] = v
        }
    }
    val missing = groupStates.filter { it.support is Support.Missing }
    if (sc != null && groupStates.none { it.support !is Support.Missing }) {
        Body("Tidak ada pengaturan ${g.label} yang tersedia di kernel ini.", dim = true)
    }
    if (missing.isNotEmpty()) {
        ExpandableCard("Tidak tersedia di perangkat ini (${missing.size})") {
            missing.forEach { st ->
                Body(st.node.title)
                Caption((st.support as Support.Missing).reason)
            }
        }
    }

    // ---------------------------------------------------------------- Cadangan & pemulihan
    if (sc != null) {
        val stockChanges = k.stockChanges(sc)
        val undoChanges = k.undoChanges(sc)
        val snaps = remember(snapVersion) { k.store.snapshots() }
        GlassCard {
            Heading("Cadangan & pemulihan")
            Body("Nilai asli tiap pengaturan dicatat sebelum pertama kali diubah. Sebelum setiap penerapan, nilai sebelumnya disimpan untuk Urungkan.", dim = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Pulihkan nilai asli (${stockChanges.size})", {
                    confirm = PendingOp("Pulihkan nilai asli?", stockChanges.joinToString("\n") { c -> "• ${KernelCatalog.byId[c.nodeId]?.title}: ${sc.state(c.nodeId)?.value} → ${c.value}" }, false, stockChanges, validate = false)
                }, Modifier.weight(1f), enabled = rootOk && !busy && stockChanges.isNotEmpty())
                GlassButton("Urungkan (${undoChanges.size})", {
                    confirm = PendingOp("Urungkan penerapan terakhir?", undoChanges.joinToString("\n") { c -> "• ${KernelCatalog.byId[c.nodeId]?.title}: ${sc.state(c.nodeId)?.value} → ${c.value}" }, false, undoChanges, validate = false)
                }, Modifier.weight(1f), enabled = rootOk && !busy && undoChanges.isNotEmpty())
            }
            GlassDivider()
            Heading("Snapshot")
            GlassTextField(snapName, { snapName = it.take(40) }, "Nama snapshot")
            GlassButton("Simpan nilai sekarang", {
                val ok = k.store.saveSnapshot(snapName, k.currentValues(sc))
                msg = if (ok) "Snapshot “${snapName.trim()}” disimpan." else "Nama kosong atau batas ${KernelStore.MAX_SNAPSHOTS} snapshot tercapai."
                if (ok) { snapName = ""; snapVersion++ }
            }, Modifier.fillMaxWidth(), enabled = !busy && snapName.isNotBlank())
            snaps.keys.forEach { name ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Body(name, Modifier.weight(1f))
                    GlassButton("Terapkan", {
                        val ch = k.snapshotChanges(sc, name)
                        confirm = PendingOp("Terapkan snapshot “$name”?", if (ch.isEmpty()) "Sudah sama dengan nilai sekarang." else ch.joinToString("\n") { c -> "• ${KernelCatalog.byId[c.nodeId]?.title}: ${sc.state(c.nodeId)?.value} → ${c.value}" }, ch.any { KernelCatalog.byId[it.nodeId]?.risk == Risk.HIGH }, ch, validate = false)
                    }, enabled = rootOk && !busy)
                    GlassButton("Hapus", { k.store.deleteSnapshot(name); snapVersion++ }, enabled = !busy)
                }
            }
        }

        // ---------------------------------------------------------------- Boot
        GlassCard {
            Heading("Terapkan saat boot")
            val b = boot
            Body("Menyimpan nilai SAAT INI dari pengaturan yang pernah kamu ubah, lalu menerapkannya tiap boot lewat skrip root (butuh root manager yang mengizinkan Senza Tools). Pengaturan Berisiko tidak pernah ikut.", dim = true)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Body(if (b?.enabled == true) "Aktif (${b.valueCount} nilai)" else "Nonaktif", Modifier.weight(1f))
                Switch(b?.enabled == true, { on ->
                    scope.launch {
                        busy = true
                        if (on) {
                            val r = k.enableBoot(sc)
                            msg = r.fold({ "Konfigurasi boot disimpan ($it nilai)." }, { it.message })
                        } else {
                            msg = if (k.disableBoot()) "Penerapan saat boot dimatikan dan konfigurasi dihapus." else "Gagal menghapus konfigurasi boot."
                        }
                        boot = k.bootStatus()
                        busy = false
                    }
                }, enabled = rootOk && !busy)
            }
            if (b?.pending == true) Caption("Boot terakhir belum dikonfirmasi stabil (±2 menit). Jika perangkat restart sendiri sebelum itu, penerapan otomatis dimatikan di boot berikutnya.", color = LocalGlass.current.warn)
            if (!b?.lastStatus.isNullOrBlank()) Caption("Status terakhir: ${b?.lastStatus}")
            Caption("Pengaman: bila boot sebelumnya tidak stabil, skrip melewati penerapan dan menonaktifkan dirinya. Perubahan sysfs juga hilang sendiri saat restart.")
        }
    }

    confirm?.let { op ->
        ConfirmDialog(
            title = op.title, message = op.message, confirmText = if (op.danger) "Saya mengerti, terapkan" else "Terapkan",
            danger = op.danger, onConfirm = { confirm = null; run(op) }, onDismiss = { confirm = null }
        )
    }
}

// ----------------------------------------------------------------------------- Kartu node

@Composable
private fun NodeCard(st: NodeState, pendingValue: String?, enabled: Boolean, onChange: (String) -> Unit) {
    val g = LocalGlass.current
    val n = st.node
    val cur = st.value
    val shown = pendingValue ?: cur
    val ready = st.support is Support.Ready
    GlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Heading(n.title, Modifier.weight(1f))
            when {
                st.support is Support.ReadOnly -> StatusPill("Baca-saja", g.textDim)
                st.support is Support.Protected -> StatusPill("Dilindungi", g.textDim)
                n.risk == Risk.HIGH -> StatusPill(n.risk.label, g.bad)
                n.risk == Risk.MEDIUM -> StatusPill(n.risk.label, g.warn)
            }
        }
        Caption(n.desc)
        n.note?.let { Caption(it, color = g.warn) }
        val path = when (val s = st.support) { is Support.Ready -> s.path; is Support.ReadOnly -> s.path; is Support.Protected -> s.path; else -> "" }
        Mono(path, size = 10)

        val canEdit = ready && enabled && cur != null
        when (val t = n.type) {
            is KType.Toggle -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Body(if (shown == t.on) "Aktif" else "Mati", Modifier.weight(1f))
                Switch(shown == t.on, { onChange(if (it) t.on else t.off) }, enabled = canEdit)
            }
            is KType.Num -> {
                val v = shown?.toLongOrNull() ?: t.min
                Body("$v ${t.unit}".trim())
                Slider(
                    value = v.coerceIn(t.min, t.max).toFloat(),
                    onValueChange = { f ->
                        val snapped = t.min + Math.round((f - t.min) / t.step.toFloat()) * t.step
                        onChange(snapped.coerceIn(t.min, t.max).toString())
                    },
                    valueRange = t.min.toFloat()..t.max.toFloat(),
                    enabled = canEdit,
                    colors = SliderDefaults.colors(thumbColor = g.accent, activeTrackColor = g.accent)
                )
                Caption("Rentang ${t.min}–${t.max} ${t.unit}".trim())
            }
            is KType.Choice -> {
                if (st.options.isEmpty()) Body(cur ?: "?")
                else {
                    val idx = st.options.indexOf(shown).coerceAtLeast(0)
                    if (canEdit) ChipRow(st.options, idx) { onChange(st.options[it]) } else Body(cur ?: "?")
                }
            }
            is KType.Csv -> {
                var text by remember(cur) { mutableStateOf(shown ?: "") }
                GlassTextField(text, { x ->
                    text = x.filter { it.isDigit() || it == ',' }
                    onChange(text)
                }, "Nilai (${t.count} angka, pisah koma)", mono = true)
                val err = if (pendingValue != null) KernelRules.validateValue(n, pendingValue) else null
                if (err != null) Caption(err, color = g.bad)
            }
        }
        if (pendingValue != null && pendingValue != cur) Caption("Perubahan: $cur → $pendingValue", color = g.accent)
        else if (n.type !is KType.Num && n.type !is KType.Toggle && cur != null && !ready) Caption("Nilai: $cur")
    }
}
