package com.senzaproject.senzaai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.senzaproject.senzaai.analyzer.apk.ApkReport
import com.senzaproject.senzaai.analyzer.rom.RomReport
import com.senzaproject.senzaai.analyzer.system.AvcDenial
import com.senzaproject.senzaai.analyzer.system.CompatFinding
import com.senzaproject.senzaai.analyzer.system.PropAnalyzer
import com.senzaproject.senzaai.analyzer.system.PropDiff
import com.senzaproject.senzaai.analyzer.system.SelinuxAnalyzer
import com.senzaproject.senzaai.di.ServiceLocator
import com.senzaproject.senzaai.domain.model.Severity
import com.senzaproject.senzaai.platform.root.SafeAction
import com.senzaproject.senzaai.ui.AnalyzerViewModel
import com.senzaproject.senzaai.ui.components.*
import com.senzaproject.senzaai.ui.theme.LocalGlass
import com.senzaproject.senzaai.utils.Sys
import com.senzaproject.senzaai.utils.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TABS = listOf("Properti", "Bandingkan", "APK", "ROM", "Kompat.", "Init.rc", "XML", "SELinux", "Partisi")

@Composable
private fun rememberFilePicker(onPick: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPick(uri) }
    return { launcher.launch(arrayOf("*/*")) }
}

@Composable
private fun rememberTreePicker(onPick: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) onPick(uri) }
    return { launcher.launch(null) }
}

@Composable
fun AnalyzerScreen(s: ServiceLocator, vm: AnalyzerViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    ScreenScaffold("Analyzer", "Analisis file yang kamu pilih. Tidak ada file yang diunggah ke mana pun.") {
        ChipRow(TABS, tab) { tab = it }
        when (tab) {
            0 -> PropTab(vm)
            1 -> CompareTab(vm)
            2 -> ApkTab(vm)
            3 -> RomTab(vm)
            4 -> CompatTab(vm)
            5 -> RcTab(vm)
            6 -> XmlTab(vm)
            7 -> SelinuxTab(s, vm)
            else -> PartitionTab(s)
        }
    }
}

// ============================================================================ util UI

@Composable
private fun FindingRow(f: CompatFinding) {
    GlassCard(strong = false, padding = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text0(f.title, Modifier.weight(1f))
            CertaintyPill(f.certainty)
            SeverityPill(f.severity)
        }
        Caption(f.detail)
    }
}

@Composable
private fun Text0(text: String, modifier: Modifier = Modifier) = Body(text, modifier)

@Composable
private fun FindingsCard(title: String, findings: List<CompatFinding>) {
    val order = findings.sortedBy { when (it.severity) { Severity.ERROR -> 0; Severity.WARN -> 1; Severity.INFO -> 2 } }
    val bad = findings.count { it.severity == Severity.ERROR }
    val warn = findings.count { it.severity == Severity.WARN }
    ExpandableCard(title, true, trailing = "$bad masalah · $warn perhatian") {
        order.forEach { FindingRow(it) }
    }
}

@Composable
private fun PickRow(vararg buttons: Pair<String, () -> Unit>, busy: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { (label, action) -> GlassButton(label, action, Modifier.weight(1f), primary = true, enabled = !busy) }
    }
}

// ============================================================================ Properti

@Composable
private fun PropTab(vm: AnalyzerViewModel) {
    val pick = rememberFilePicker { vm.loadProp(it) }
    GlassCard {
        Heading("Properti sistem")
        Body("Analisis build.prop, system.prop, vendor/product/system_ext/odm prop: duplikat, format tidak valid, konsistensi, dan penjelasan fungsi.", dim = true)
        PickRow("Pilih file .prop" to pick, "Properti perangkat ini" to { vm.loadDeviceProp() }, busy = vm.propBusy)
    }
    if (vm.propBusy) Spinner("Menganalisis…")
    vm.propError?.let { ErrorCard(it) }
    val np = vm.prop ?: return
    val r = np.result
    GlassCard(strong = true) {
        Heading(np.name)
        KeyValue("Total properti", r.props.size.toString())
        KeyValue("Duplikat", r.duplicates.size.toString())
        KeyValue("Baris tidak valid", r.invalid.size.toString())
        KeyValue("Temuan", "${r.issues.count { it.severity == Severity.ERROR }} masalah · ${r.issues.count { it.severity == Severity.WARN }} perhatian")
    }
    if (r.issues.isNotEmpty()) {
        ExpandableCard("Temuan", true, trailing = "${r.issues.size}") {
            LimitedList(r.issues, 25) { i ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeverityPill(i.severity)
                    Column(Modifier.weight(1f)) {
                        Body(i.message)
                        if (i.lineNo != null) Caption("Baris ${i.lineNo}")
                    }
                }
            }
        }
    }
    ExpandableCard("Rekomendasi berbasis bukti", true) {
        PropAnalyzer.recommendations(r).forEach { Body("• $it") }
    }
    var q by remember(np) { mutableStateOf("") }
    ExpandableCard("Daftar properti (ketuk untuk penjelasan)", false, trailing = "${r.props.size}") {
        GlassTextField(q, { q = it }, "Cari properti atau nilai")
        val list = remember(np, q) {
            r.props.entries.filter { q.isBlank() || it.key.contains(q, true) || it.value.contains(q, true) }.toList()
        }
        LimitedList(list, 40) { e -> PropRow(e.key, e.value) }
    }
}

@Composable
private fun PropRow(key: String, value: String) {
    var open by remember { mutableStateOf(false) }
    val g = LocalGlass.current
    val why = remember(key) { PropAnalyzer.explain(key) }
    Column(
        Modifier.fillMaxWidth().then(Modifier.clickableNoRipple { open = !open }),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Mono(key, size = 12)
        Body(value.ifEmpty { "(kosong)" }, dim = true)
        if (open) {
            Caption(why ?: "Tidak ada penjelasan bawaan untuk properti ini. Fungsinya tidak dapat dipastikan dari nama saja.", color = if (why != null) g.accent2 else g.textDim)
        }
    }
}

// ============================================================================ Bandingkan

@Composable
private fun CompareTab(vm: AnalyzerViewModel) {
    val pickA = rememberFilePicker { vm.loadCompare(true, it) }
    val pickB = rememberFilePicker { vm.loadCompare(false, it) }
    GlassCard {
        Heading("Bandingkan build.prop")
        Body("Pilih dua file konfigurasi (atau properti perangkat ini) untuk melihat properti yang ditambah, dihapus, diubah, duplikat, dan konflik.", dim = true)
        Caption("A: ${vm.cmpA?.name ?: "belum dipilih"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Pilih A", pickA, Modifier.weight(1f), primary = true)
            GlassButton("A = perangkat ini", { vm.useDeviceForCompare(true) }, Modifier.weight(1f))
        }
        Caption("B: ${vm.cmpB?.name ?: "belum dipilih"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Pilih B", pickB, Modifier.weight(1f), primary = true)
            GlassButton("B = perangkat ini", { vm.useDeviceForCompare(false) }, Modifier.weight(1f))
        }
    }
    vm.cmpError?.let { ErrorCard(it) }
    vm.cmpDiff?.let { DiffView(it) }
}

@Composable
private fun DiffView(d: PropDiff) {
    GlassCard(strong = true) {
        Heading("Hasil perbandingan")
        KeyValue("Ditambahkan (ada di B saja)", d.added.size.toString())
        KeyValue("Dihapus (ada di A saja)", d.removed.size.toString())
        KeyValue("Nilai berbeda", d.modified.size.toString())
        KeyValue("Duplikat di A / B", "${d.duplicatesA.size} / ${d.duplicatesB.size}")
        KeyValue("Konflik nilai ganda", d.conflicts.size.toString())
        KeyValue("Format tidak valid A / B", "${d.invalidA.size} / ${d.invalidB.size}")
    }
    if (d.modified.isNotEmpty()) ExpandableCard("Diubah", true, trailing = "${d.modified.size}") {
        LimitedList(d.modified.entries.toList(), 30) { e ->
            Column {
                Mono(e.key)
                Caption("A: ${e.value.first}")
                Caption("B: ${e.value.second}")
            }
        }
    }
    if (d.added.isNotEmpty()) ExpandableCard("Ditambahkan", false, trailing = "${d.added.size}") {
        LimitedList(d.added.entries.toList(), 30) { e -> Mono("+ ${e.key}=${e.value}") }
    }
    if (d.removed.isNotEmpty()) ExpandableCard("Dihapus", false, trailing = "${d.removed.size}") {
        LimitedList(d.removed.entries.toList(), 30) { e -> Mono("- ${e.key}=${e.value}") }
    }
    if (d.conflicts.isNotEmpty()) ExpandableCard("Properti dengan konflik", true, trailing = "${d.conflicts.size}") {
        d.conflicts.take(60).forEach { Mono(it) }
    }
    if (d.invalidA.isNotEmpty() || d.invalidB.isNotEmpty()) ExpandableCard("Baris tidak valid", false) {
        d.invalidA.take(30).forEach { Caption("A baris ${it.first}: ${it.second.take(80)}") }
        d.invalidB.take(30).forEach { Caption("B baris ${it.first}: ${it.second.take(80)}") }
    }
}

// ============================================================================ APK

@Composable
private fun ApkTab(vm: AnalyzerViewModel) {
    val pick = rememberFilePicker { vm.loadApk(it) }
    GlassCard {
        Heading("APK Analyzer")
        Body("Membaca manifest, izin, komponen, library native (termasuk cek 16 KB), DEX, tanda tangan, dan kompatibilitas dengan perangkat ini. Analisis keamanan bersifat indikatif dan bukan jaminan APK aman.", dim = true)
        PickRow("Pilih file APK" to pick, busy = vm.apkBusy)
        Caption("Mendukung APK tunggal. Split APK/AAB tidak dapat dibaca langsung.")
    }
    if (vm.apkBusy) Spinner("Menganalisis APK…")
    vm.apkError?.let { ErrorCard(it) }
    vm.apk?.let { ApkView(it) }
}

@Composable
private fun ApkView(a: ApkReport) {
    GlassCard(strong = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            a.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(64.dp)) }
            Column(Modifier.weight(1f)) {
                Heading(a.appName)
                Mono(a.packageName)
                Caption("v${a.versionName ?: "?"} (${a.versionCode}) · ${a.sizeBytes.formatBytes()}")
            }
        }
    }
    GlassCard {
        Heading("Informasi")
        KeyValue("File", a.fileName)
        KeyValue("Min SDK", a.minSdk.toString())
        KeyValue("Target SDK", a.targetSdk.toString())
        KeyValue("Compile SDK", a.compileSdk?.toString() ?: "Tidak tersedia")
        KeyValue("ABI native", a.abis.joinToString().ifBlank { "Tidak ada native library" })
        KeyValue("DEX", "${a.dex.size} file")
        KeyValue("Resource", "${a.resourceFiles} file · resources.arsc ${a.arscBytes.formatBytes()}")
        KeyValue("Assets", "${a.assetFiles} file")
        KeyValue("Izin", "${a.permissions.size} (berbahaya: ${a.permissions.count { it.dangerous }})")
        KeyValue("Komponen", "${a.components.size} (exported: ${a.components.count { it.exported }})")
    }
    FindingsCard("Kompatibilitas dengan perangkat ini", a.compat)
    ExpandableCard("Catatan", true, trailing = "${a.notes.size}") {
        a.notes.forEach { n ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeverityPill(n.severity)
                Body(n.text, Modifier.weight(1f))
            }
        }
    }
    ExpandableCard("Tanda tangan", false, trailing = "${a.certs.size}") {
        if (a.certs.isEmpty()) Body("Tidak ada sertifikat yang dapat dibaca.", dim = true)
        a.certs.forEach { c ->
            KeyValue("Subjek", c.subject)
            KeyValue("Penerbit", c.issuer)
            KeyValue("Berlaku", "${c.notBefore} s/d ${c.notAfter}${if (c.expired) " (kedaluwarsa)" else ""}")
            KeyValue("Algoritma", c.algorithm)
            Caption("SHA-256: ${c.sha256}")
            GlassDivider()
        }
        Caption("Skema v1 (JAR): ${if (a.hasV1Signature) "ada" else "tidak ada"}. Ketersediaan v2/v3 tidak diperiksa terpisah.")
    }
    ExpandableCard("Izin (permissions)", false, trailing = "${a.permissions.size}") {
        LimitedList(a.permissions.sortedByDescending { it.dangerous }, 40) { p ->
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Mono(p.name.removePrefix("android.permission."), Modifier.weight(1f), 11)
                    StatusPill(p.level, if (p.dangerous) LocalGlass.current.bad else LocalGlass.current.textDim)
                }
                p.note?.let { Caption(it) }
            }
        }
    }
    ExpandableCard("Komponen", false, trailing = "${a.components.size}") {
        LimitedList(a.components, 40) { c ->
            Column {
                Mono("${c.kind}: ${c.name.substringAfterLast('.')}", size = 11)
                Caption("${c.name}${if (c.exported) " · exported" else ""}${c.permission?.let { " · izin: $it" } ?: ""}")
            }
        }
    }
    ExpandableCard("Library native", false, trailing = "${a.nativeLibs.size}") {
        if (a.nativeLibs.isEmpty()) Body("Tidak ada library native.", dim = true)
        LimitedList(a.nativeLibs, 40) { l ->
            Column {
                Mono(l.path.removePrefix("lib/"), size = 11)
                val e = l.elf
                Caption(
                    "${l.size.formatBytes()} · " + if (e == null) "bukan ELF/tidak terbaca"
                    else "${e.machine} ${if (e.is64) "64-bit" else "32-bit"} · align ${e.minLoadAlign ?: "?"}" +
                        (if (!e.is64) "" else if (e.ok16k == true) " · 16 KB OK" else if (e.ok16k == false) " · TIDAK 16 KB" else "")
                )
            }
        }
    }
    ExpandableCard("DEX", false, trailing = "${a.dex.size}") {
        a.dex.forEach { d ->
            Caption("${d.name}: ${d.size.formatBytes()} · dex ${d.version ?: "?"} · ${d.classes ?: "?"} kelas · ${d.methods ?: "?"} metode")
        }
    }
    ExpandableCard("Struktur APK", false) {
        a.structure.forEach { (n, size, count) -> KeyValue(n, "${size.formatBytes()} · $count file") }
    }
}

// ============================================================================ ROM

@Composable
private fun RomTab(vm: AnalyzerViewModel) {
    val pickZip = rememberFilePicker { vm.scanRom(it, false) }
    val pickTree = rememberTreePicker { vm.scanRom(it, true) }
    GlassCard {
        Heading("ROM File Analyzer")
        Body("Pilih ZIP ROM (dibaca streaming, tidak disalin) atau folder ROM yang sudah diekstrak. Build.prop, init.rc, manifest/VINTF, dan library native akan dianalisis.", dim = true)
        PickRow("Pilih ZIP" to pickZip, "Pilih folder" to pickTree, busy = vm.romBusy)
        if (vm.romBusy) {
            Spinner(vm.romProgress)
            GlassButton("Batalkan", { vm.cancelRom() }, danger = true)
        }
    }
    vm.romError?.let { ErrorCard(it) }
    vm.rom?.let { RomView(it, vm.romCompat) }
}

@Composable
private fun RomView(r: RomReport, compat: List<CompatFinding>?) {
    GlassCard(strong = true) {
        Heading(r.sourceName)
        KeyValue("Jenis", r.kind)
        KeyValue("Entri dipindai", r.entryCount.toString())
        KeyValue("Pengemasan", r.packaging.joinToString().ifBlank { "Tidak terdeteksi" })
        KeyValue("Partisi terdeteksi", r.partitions.joinToString().ifBlank { "Tidak terdeteksi" })
        KeyValue("APK di dalam ROM", r.apkCount.toString())
        KeyValue("Library dipindai", "${r.nativeLibs.size} (64-bit tidak 16 KB: ${r.libs64Bad}/${r.libs64Total})")
    }
    if (r.notes.isNotEmpty()) GlassCard {
        Heading("Catatan")
        r.notes.forEach { Body("• $it") }
    }
    if (r.mergedProps.isNotEmpty()) ExpandableCard("Properti kunci", true) {
        listOf(
            "ro.build.version.release", "ro.build.version.sdk", "ro.build.fingerprint", "ro.build.version.security_patch",
            "ro.product.device", "ro.product.model", "ro.board.platform", "ro.vndk.version", "ro.product.cpu.abilist",
            "ro.treble.enabled", "ro.build.ab_update", "ro.boot.dynamic_partitions"
        ).forEach { k -> r.mergedProps[k]?.let { KeyValue(k, it) } }
    }
    if (compat != null) FindingsCard("Kompatibilitas dengan perangkat ini (sebagai ROM sumber)", compat)
    if (r.images.isNotEmpty()) ExpandableCard("Image & payload", false, trailing = "${r.images.size}") {
        LimitedList(r.images, 40) { Mono(it, size = 11) }
    }
    if (r.metadata.isNotEmpty()) ExpandableCard("Metadata OTA", false, trailing = "${r.metadata.size}") {
        r.metadata.entries.take(40).forEach { KeyValue(it.key, it.value) }
    }
    if (r.buildProps.isNotEmpty()) ExpandableCard("Analisis file properti", false, trailing = "${r.buildProps.size}") {
        r.buildProps.forEach { (path, res) ->
            Mono(path, size = 11)
            Caption("${res.props.size} properti · ${res.duplicates.size} duplikat · ${res.invalid.size} tidak valid")
            res.issues.filter { it.severity != Severity.INFO }.take(6).forEach { Caption("• [${it.severity}] ${it.message}") }
            GlassDivider()
        }
    }
    if (r.initRc.isNotEmpty()) ExpandableCard("init.rc", false, trailing = "${r.initRc.size} file") {
        val svc = r.initRc.sumOf { it.second.services.size }
        val trg = r.initRc.sumOf { it.second.triggers.size }
        Caption("$svc service · $trg trigger")
        r.initRc.flatMap { (p, a) -> a.issues.filter { it.first != Severity.INFO }.map { p to it } }.take(40).forEach { (p, i) ->
            Caption("• ${p.substringAfterLast('/')}:${i.second ?: "-"} [${i.first}] ${i.third}")
        }
    }
    if (r.xml.isNotEmpty()) ExpandableCard("Manifest / VINTF XML", false, trailing = "${r.xml.size} file") {
        r.xml.forEach { (p, x) ->
            Mono(p, size = 11)
            Caption(if (x.wellFormed) "Valid · root <${x.rootTag}> · ${x.elementCount} elemen" else "TIDAK VALID: ${x.error}")
        }
    }
    if (r.nativeLibs.isNotEmpty()) ExpandableCard("Library native", false, trailing = "${r.nativeLibs.size}") {
        val bad = r.nativeLibs.filter { it.elf?.is64 == true && it.elf?.ok16k == false }
        if (bad.isNotEmpty()) {
            Body("Library 64-bit tidak selaras 16 KB:", color = LocalGlass.current.warn)
            LimitedList(bad, 25) { Mono(it.path, size = 11) }
        } else Body("Semua library 64-bit yang dipindai selaras 16 KB (atau tidak ada).", dim = true)
        Caption("Batas pemindaian: 400 library. Penyelarasan di dalam kontainer ZIP/APK tidak diperiksa.")
    }
    if (r.topLevel.isNotEmpty()) ExpandableCard("Struktur tingkat atas", false) {
        r.topLevel.forEach { KeyValue(it.first, "${it.second} file") }
    }
}

// ============================================================================ Kompatibilitas

@Composable
private fun CompatTab(vm: AnalyzerViewModel) {
    val pick = rememberFilePicker { vm.loadCompatSource(it) }
    GlassCard {
        Heading("ROM Compatibility Assistant")
        Body("Pilih build.prop ROM sumber. Senza AI membandingkannya dengan perangkat ini sebagai target: SDK, ABI, VNDK, Treble, partisi, dan platform. Tanpa data di kedua sisi, hasil ditandai \"belum dapat dipastikan\".", dim = true)
        PickRow("Pilih build.prop sumber" to pick)
        Caption("Untuk ROM lengkap (ZIP/folder), gunakan tab ROM: kompatibilitas ditampilkan otomatis.")
    }
    vm.compatError?.let { ErrorCard(it) }
    vm.compat?.let { FindingsCard("Sumber: ${vm.compatName}  →  Target: perangkat ini", it) }
}

// ============================================================================ init.rc

@Composable
private fun RcTab(vm: AnalyzerViewModel) {
    val pick = rememberFilePicker { vm.loadRc(it) }
    GlassCard {
        Heading("Init RC Analyzer")
        Body("Membaca service, trigger, dan import dari file *.rc. Mendeteksi service ganda tanpa override, izin longgar, dan opsi tidak dikenal.", dim = true)
        PickRow("Pilih file .rc" to pick)
    }
    vm.miscError?.let { ErrorCard(it) }
    val rc = vm.rc ?: return
    val a = rc.result
    GlassCard(strong = true) {
        Heading(rc.name)
        KeyValue("Service", a.services.size.toString())
        KeyValue("Trigger (on)", a.triggers.size.toString())
        KeyValue("Import", a.imports.size.toString())
        KeyValue("Temuan", a.issues.size.toString())
    }
    if (a.issues.isNotEmpty()) ExpandableCard("Temuan", true, trailing = "${a.issues.size}") {
        LimitedList(a.issues.sortedBy { it.first.ordinal * -1 }, 30) { (sev, line, msg) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeverityPill(sev)
                Column(Modifier.weight(1f)) {
                    Body(msg)
                    if (line != null) Caption("Baris $line")
                }
            }
        }
    }
    ExpandableCard("Service", false, trailing = "${a.services.size}") {
        LimitedList(a.services, 30) { sv ->
            Column {
                Mono(sv.name)
                Caption("${sv.path} ${sv.args.joinToString(" ")}".trim())
                Caption(
                    listOfNotNull(
                        sv.options["class"]?.firstOrNull()?.let { "class $it" },
                        sv.options["user"]?.firstOrNull()?.let { "user $it" },
                        sv.options["seclabel"]?.firstOrNull()?.let { "seclabel $it" },
                        sv.flags.takeIf { it.isNotEmpty() }?.joinToString()
                    ).joinToString(" · ").ifBlank { "tanpa opsi tambahan" }
                )
            }
        }
    }
    ExpandableCard("Trigger", false, trailing = "${a.triggers.size}") {
        LimitedList(a.triggers, 30) { t -> Column { Mono("on ${t.name}"); Caption("${t.commands.size} perintah · baris ${t.line}") } }
    }
    if (a.imports.isNotEmpty()) ExpandableCard("Import", false, trailing = "${a.imports.size}") {
        a.imports.take(60).forEach { Mono(it, size = 11) }
    }
}

// ============================================================================ XML

@Composable
private fun XmlTab(vm: AnalyzerViewModel) {
    val pick = rememberFilePicker { vm.loadXml(it) }
    GlassCard {
        Heading("XML & Manifest Analyzer")
        Body("Memeriksa kebenaran format XML, struktur elemen, izin, dan komponen. Untuk AndroidManifest.xml teks (bukan biner di dalam APK; gunakan tab APK untuk itu).", dim = true)
        PickRow("Pilih file .xml" to pick)
    }
    vm.miscError?.let { ErrorCard(it) }
    val x = vm.xml ?: return
    val r = x.result
    GlassCard(strong = true) {
        Heading(x.name)
        KeyValue("Format", if (r.wellFormed) "Valid (well-formed)" else "TIDAK VALID")
        if (!r.wellFormed) Body(r.error ?: "", color = LocalGlass.current.bad)
        KeyValue("Elemen root", r.rootTag ?: "-")
        KeyValue("Jumlah elemen", r.elementCount.toString())
        KeyValue("Kedalaman maks", r.maxDepth.toString())
    }
    if (r.issues.isNotEmpty()) ExpandableCard("Temuan", true) {
        r.issues.forEach { (sev, msg) -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SeverityPill(sev); Body(msg, Modifier.weight(1f)) } }
    }
    if (r.topTags.isNotEmpty()) ExpandableCard("Elemen terbanyak", false) { r.topTags.forEach { KeyValue(it.first, it.second.toString()) } }
    if (r.permissions.isNotEmpty()) ExpandableCard("Izin", false, trailing = "${r.permissions.size}") {
        r.permissions.forEach { Mono(it.removePrefix("android.permission."), size = 11) }
    }
    if (r.components.isNotEmpty()) ExpandableCard("Komponen", false, trailing = "${r.components.size}") {
        LimitedList(r.components, 40) { Mono(it, size = 11) }
    }
}

// ============================================================================ SELinux

@Composable
private fun SelinuxTab(s: ServiceLocator, vm: AnalyzerViewModel) {
    val pick = rememberFilePicker { vm.loadAvc(it) }
    val scope = rememberCoroutineScope()
    val rootOk = s.access.isRootVerified
    var busy by remember { mutableStateOf(false) }
    GlassCard {
        Heading("SELinux Denial Analyzer")
        Body("Membaca baris \"avc: denied\" dari log (dmesg/logcat) dan mengelompokkannya. Saran rule hanya contoh untuk dipelajari; periksa neverallow dan risikonya sebelum dipakai.", dim = true)
        PickRow("Pilih file log" to pick)
        if (rootOk) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("dmesg (root)", {
                    scope.launch {
                        busy = true
                        val r = s.access.run(SafeAction.Dmesg, true)
                        vm.analyzeAvcText("dmesg (root)", r.output)
                        busy = false
                    }
                }, Modifier.weight(1f), enabled = !busy)
                GlassButton("logcat (root)", {
                    scope.launch {
                        busy = true
                        val r = s.access.run(SafeAction.Logcat, true)
                        vm.analyzeAvcText("logcat (root)", r.output)
                        busy = false
                    }
                }, Modifier.weight(1f), enabled = !busy)
            }
        } else Caption("Ambil log langsung dari perangkat membutuhkan akses root terverifikasi (Tools → Akses). Tanpa root, ekspor log lalu pilih filenya.")
    }
    vm.miscError?.let { ErrorCard(it) }
    val st = vm.avc ?: return
    GlassCard(strong = true) {
        Heading(st.source)
        KeyValue("Kelompok denial unik", st.denials.size.toString())
        KeyValue("Total kejadian", st.denials.sumOf { it.count }.toString())
        if (st.denials.isEmpty()) Body("Tidak ditemukan baris avc: denied pada log ini. Ini tidak membuktikan tidak ada masalah SELinux; log mungkin sudah terpotong.", dim = true)
    }
    LimitedList(st.denials, 12, 20) { d -> DenialCard(d) }
}

@Composable
private fun DenialCard(d: AvcDenial) {
    GlassCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono("${d.sType} → ${d.tType}", Modifier.weight(1f), 11)
            StatusPill("${d.count}×", LocalGlass.current.warn)
        }
        Body(SelinuxAnalyzer.explain(d))
        Mono(SelinuxAnalyzer.suggestion(d), size = 11)
        SelinuxAnalyzer.caution(d)?.let { Caption("⚠ $it", color = LocalGlass.current.warn) }
    }
}

// ============================================================================ Partisi

@Composable
private fun PartitionTab(s: ServiceLocator) {
    val scope = rememberCoroutineScope()
    var byName by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val info by produceState<Triple<List<Pair<String, String>>, List<String>, List<String>>?>(null) {
        value = withContext(Dispatchers.IO) {
            val p = Sys.refreshProps()
            val keys = listOf(
                "ro.boot.slot_suffix", "ro.build.ab_update", "ro.boot.dynamic_partitions", "ro.virtual_ab.enabled",
                "ro.treble.enabled", "ro.vndk.version", "ro.product.first_api_level", "ro.boot.verifiedbootstate",
                "ro.boot.vbmeta.device_state", "ro.boot.flash.locked", "ro.product.cpu.pagesize.max"
            )
            val props = keys.map { it to (p[it]?.ifBlank { null } ?: "Tidak tersedia") }
            val mounts = (Sys.readText("/proc/mounts") ?: "").lines().filter { l ->
                val mp = l.split(' ').getOrNull(1) ?: ""
                mp in setOf("/", "/system", "/system_ext", "/product", "/vendor", "/odm", "/vendor_dlkm", "/odm_dlkm", "/data", "/metadata", "/cache")
            }.map { it.split(' ').let { t -> "${t.getOrNull(1)}  ${t.getOrNull(2)}  ${t.getOrNull(3)?.substringBefore(',')}" } }
            val sto = s.device.storageSections().getOrNull(1)?.rows?.map { "${it.label}: ${it.value}" } ?: emptyList()
            Triple(props, mounts, sto)
        }
    }
    GlassCard {
        Heading("Partition Analyzer")
        Body("Menampilkan konfigurasi partisi yang dapat dibaca. Ketersediaan bergantung pada perangkat dan tingkat akses.", dim = true)
    }
    val i = info
    if (i == null) {
        Spinner("Membaca…")
        return
    }
    ExpandableCard("Konfigurasi boot & partisi", true) { i.first.forEach { KeyValue(it.first, it.second) } }
    ExpandableCard("Mount point yang terbaca", false, trailing = "${i.second.size}") {
        if (i.second.isEmpty()) Body("/proc/mounts tidak dapat dibaca pada Normal Access.", dim = true)
        i.second.forEach { Mono(it, size = 11) }
    }
    ExpandableCard("Penggunaan partisi (terpakai / total)", false, trailing = "${i.third.size}") {
        i.third.forEach { Mono(it, size = 11) }
    }
    GlassCard {
        Heading("Daftar partisi blok (root)")
        if (!s.access.isRootVerified) Body("Memerlukan akses root terverifikasi. Hanya menampilkan nama partisi, tidak membaca isinya.", dim = true)
        else GlassButton(if (busy) "Membaca…" else "Tampilkan by-name", {
            scope.launch {
                busy = true
                byName = s.access.run(SafeAction.PartitionsByName, true).output
                busy = false
            }
        }, primary = true, enabled = !busy)
        byName?.let { Mono(it.take(6000), size = 10) }
    }
}
