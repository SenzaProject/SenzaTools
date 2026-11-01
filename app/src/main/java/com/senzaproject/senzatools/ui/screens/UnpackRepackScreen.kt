package com.senzaproject.senzatools.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.platform.root.CommandGuard
import com.senzaproject.senzatools.platform.root.UnpackEngine
import com.senzaproject.senzatools.ui.components.*
import com.senzaproject.senzatools.ui.theme.LocalGlass
import kotlinx.coroutines.launch

private val SUB_TABS = listOf("Proyek", "Unpack", "Repack", "Konversi", "Partisi", "Skrip")

/** Partisi yang tidak boleh ditulis lewat fitur Flash (terlalu berisiko untuk dilakukan dari sistem yang sedang jalan). */
private val FLASH_BLOCKED = Regex("^(userdata|super|metadata|persist|system.*|vendor.*|product.*|odm.*|system_ext.*|mi_ext.*)$")

/**
 * Unpack & Repack: membongkar dan merakit ulang image ROM langsung di HP (butuh root).
 * Fitur: proyek, unzip ROM, unpack (payload.bin/br/dat/img/super), repack (ext4/erofs/f2fs, super.img),
 * konversi (simg, dat/br, zstd, gabung sparse), ekstrak/flash partisi, dan eksekutor skrip.
 */
@Composable
fun UnpackRepackTab(s: ServiceLocator) {
    val eng = s.unpack
    val scope = rememberCoroutineScope()
    val rootOk = s.access.isRootVerified

    var sub by rememberSaveable { mutableStateOf(0) }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var projects by remember { mutableStateOf<List<String>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }

    fun append(line: String) {
        val t = log + line + "\n"
        log = if (t.length > 60_000) t.takeLast(50_000) else t
    }

    /** Menjalankan satu tugas dan menampilkan keluarannya di kartu Keluaran. */
    fun launchTask(label: String, script: String, needProject: Boolean = true) {
        if (busy) return
        if (needProject && project == null) {
            log = "! Pilih atau buat proyek dulu di tab Proyek.\n"
            return
        }
        busy = true
        log = "== $label ==\n"
        scope.launch {
            val code = eng.run(if (needProject) project else null, script, label) { append(it) }
            append(if (code == 0) "== Selesai ==" else "== Berhenti (kode $code) ==")
            busy = false
            tick++
        }
    }

    LaunchedEffect(rootOk, tick) {
        if (rootOk) {
            projects = eng.listProjects()
            if (project != null && project !in projects) project = null
        }
    }

    GlassCard(strong = true) {
        Heading("Unpack & Repack")
        Body("Bongkar dan rakit ulang image ROM langsung di HP: payload.bin, super.img, ext4/erofs/f2fs, .dat/.br, dan .zst.", dim = true)
        Caption("Folder kerja: ${UnpackEngine.BASE_DIR}")
        Caption(if (project != null) "Proyek aktif: $project" else "Belum ada proyek aktif")
    }

    if (!rootOk) {
        GlassCard {
            Heading("Butuh akses root")
            Body("Fitur ini menjalankan tool biner lewat root. Minta izin root dulu.", dim = true)
            GlassButton("Minta izin root", { scope.launch { s.access.verifyRoot() } }, primary = true, enabled = !s.access.rootChecking)
        }
        return
    }

    ChipRow(SUB_TABS, sub) { sub = it }

    when (sub) {
        0 -> ProjectSection(eng, projects, project, busy, { project = it }, ::launchTask) { tick++ }
        1 -> UnpackSection(eng, project, busy, tick, ::launchTask) { tick++ }
        2 -> RepackSection(eng, project, busy, tick, ::launchTask) { tick++ }
        3 -> ConvertSection(eng, project, busy, tick, ::launchTask) { tick++ }
        4 -> PartitionSection(eng, project, busy, tick, ::launchTask)
        else -> ScriptSection(eng, project, busy, ::launchTask)
    }

    OutputCard(log, busy, onStop = { eng.cancel() }, onClear = { log = "" })
}

// ============================================================================ Proyek

@Composable
private fun ProjectSection(
    eng: UnpackEngine,
    projects: List<String>,
    active: String?,
    busy: Boolean,
    onSelect: (String?) -> Unit,
    run: (String, String, Boolean) -> Unit,
    refresh: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var nameErr by remember { mutableStateOf<String?>(null) }
    var delSel by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var zipSel by remember { mutableStateOf<String?>(null) }
    var deleteZip by remember { mutableStateOf(false) }
    val zips by rememberFileList(eng, active, "zip", 0)

    GlassCard {
        Heading("Proyek baru")
        GlassTextField(newName, { newName = it; nameErr = null }, "Nama proyek (huruf, angka, _ - .)")
        nameErr?.let { Caption(it) }
        GlassButton("Buat proyek", {
            val clean = UnpackEngine.cleanProjectName(newName)
            if (clean == null) nameErr = "Nama tidak valid. Pakai huruf/angka/_/-/. tanpa spasi (maks 48)."
            else {
                run("Proyek baru", eng.newProjectScript(clean), false)
                newName = ""
            }
        }, primary = true, enabled = !busy && newName.isNotBlank())
    }

    GlassCard {
        Heading("Pilih proyek")
        if (projects.isEmpty()) Body("Belum ada proyek. Buat dulu di atas.", dim = true)
        projects.forEach { p ->
            CheckRow(p, p == active) { onSelect(if (p == active) null else p) }
        }
        GlassButton("Muat ulang daftar", refresh, enabled = !busy)
    }

    GlassCard {
        Heading("Hapus proyek")
        if (projects.isEmpty()) Body("Tidak ada proyek.", dim = true)
        projects.forEach { p ->
            CheckRow(p, p in delSel) { delSel = if (p in delSel) delSel - p else delSel + p }
        }
        GlassButton("Hapus terpilih", { confirmDelete = true }, danger = true, enabled = !busy && delSel.isNotEmpty())
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Hapus proyek?",
            "Seluruh isi proyek berikut akan dihapus permanen: ${delSel.joinToString(", ")}",
            confirmText = "Hapus", danger = true,
            onConfirm = {
                confirmDelete = false
                if (active in delSel) onSelect(null)
                run("Hapus proyek", eng.deleteProjectScript(delSel.toList()), false)
                delSel = emptySet()
            },
            onDismiss = { confirmDelete = false }
        )
    }

    GlassCard {
        Heading("Unzip ROM")
        Body("Taruh file .zip ROM di ${UnpackEngine.BASE_DIR}, lalu pilih di sini.", dim = true)
        if (zips.isEmpty()) Body("Tidak ada file .zip di folder kerja.", dim = true)
        zips.forEach { (v, l) -> CheckRow(l, v == zipSel) { zipSel = if (v == zipSel) null else v } }
        SwitchRow("Hapus file sumber setelah selesai", null, deleteZip) { deleteZip = it }
        GlassButton("Unzip ROM", {
            zipSel?.let { run("Unzip ROM", eng.unzipRomScript(it, deleteZip), true) }
        }, primary = true, enabled = !busy && zipSel != null && active != null)
    }
}

// ============================================================================ Unpack

@Composable
private fun UnpackSection(
    eng: UnpackEngine,
    project: String?,
    busy: Boolean,
    tick: Int,
    run: (String, String, Boolean) -> Unit,
    refresh: () -> Unit
) {
    if (project == null) return NeedProject()

    // payload.bin
    var payloadParts by remember { mutableStateOf<List<String>>(emptyList()) }
    var payloadSel by remember { mutableStateOf(setOf<String>()) }
    var payloadDel by remember { mutableStateOf(false) }
    var payloadAuto by remember { mutableStateOf(false) }
    // super.img
    var superParts by remember { mutableStateOf<List<String>>(emptyList()) }
    var superSel by remember { mutableStateOf(setOf<String>()) }
    var superDel by remember { mutableStateOf(false) }
    var superAuto by remember { mutableStateOf(false) }
    var hasPayload by remember { mutableStateOf(false) }
    var hasSuper by remember { mutableStateOf(false) }

    LaunchedEffect(project, tick) {
        hasPayload = eng.fileExists(project, "payload.bin")
        hasSuper = eng.fileExists(project, "super.img")
        payloadParts = if (hasPayload) eng.listPayloadPartitions(project) else emptyList()
        superParts = if (hasSuper) eng.listSuperPartitions(project) else emptyList()
        payloadSel = payloadSel.intersect(payloadParts.toSet())
        superSel = superSel.intersect(superParts.toSet())
    }

    GlassCard {
        Heading("Unpack payload.bin")
        if (!hasPayload) Body("payload.bin tidak ditemukan di folder proyek.", dim = true)
        else {
            Body("Pilih partisi yang diekstrak. Kosong berarti semua.", dim = true)
            payloadParts.forEach { p -> CheckRow(p, p in payloadSel) { payloadSel = if (p in payloadSel) payloadSel - p else payloadSel + p } }
            SwitchRow("Hapus file asli setelah selesai", null, payloadDel) { payloadDel = it }
            SwitchRow("Otomatis unpack IMG hasil ekstrak", null, payloadAuto) { payloadAuto = it }
            GlassButton("Unpack payload.bin", {
                run("Unpack payload.bin", eng.unpackPayloadScript(payloadSel.toList(), payloadDel, payloadAuto), true)
            }, primary = true, enabled = !busy)
        }
    }

    SimpleFileUnpack(eng, project, busy, tick, "br", "Unpack br", "Pilih file .br", run)
    SimpleFileUnpack(eng, project, busy, tick, "dat", "Unpack dat", "Pilih file .new.dat", run)

    SimpleFileUnpack(
        eng, project, busy, tick, "img", "Unpack img",
        "Format img (ext4/erofs/f2fs) dikenali otomatis. Untuk super hanya partisi a yang diekstrak.", run
    )

    GlassCard {
        Heading("Unpack super")
        if (!hasSuper) Body("super.img tidak ditemukan di folder proyek.", dim = true)
        else {
            Body("Pilih partisi logis yang diekstrak. Kosong berarti semua.", dim = true)
            superParts.forEach { p -> CheckRow(p, p in superSel) { superSel = if (p in superSel) superSel - p else superSel + p } }
            SwitchRow("Hapus file asli setelah selesai", null, superDel) { superDel = it }
            SwitchRow("Otomatis unpack IMG hasil ekstrak", null, superAuto) { superAuto = it }
            GlassButton("Unpack super", {
                run("Unpack super", eng.unpackSuperScript(superSel.toList(), superDel, superAuto), true)
            }, primary = true, enabled = !busy)
        }
    }
    GlassButton("Muat ulang daftar file", refresh, enabled = !busy)
}

/** Kartu unpack berbasis daftar file (br, dat, img): pilih satu atau lebih lalu jalankan `dna extract`. */
@Composable
private fun SimpleFileUnpack(
    eng: UnpackEngine,
    project: String,
    busy: Boolean,
    tick: Int,
    kind: String,
    title: String,
    hint: String,
    run: (String, String, Boolean) -> Unit
) {
    val files by rememberFileList(eng, project, kind, tick)
    var sel by remember(kind) { mutableStateOf(setOf<String>()) }
    var del by remember(kind) { mutableStateOf(false) }
    GlassCard {
        Heading(title)
        Body(hint, dim = true)
        if (files.isEmpty()) Body("Tidak ada file yang cocok di folder proyek.", dim = true)
        files.forEach { (v, l) -> CheckRow(l, v in sel) { sel = if (v in sel) sel - v else sel + v } }
        SwitchRow("Hapus file asli setelah selesai", null, del) { del = it }
        GlassButton(title, { run(title, eng.unpackExtractScript(sel.toList(), del), true) }, primary = true, enabled = !busy && sel.isNotEmpty())
    }
}

// ============================================================================ Repack

private val TARGETS = listOf("ext4", "erofs", "f2fs")
private val RW = listOf("Read-Write (rw)", "Read-Only (ro)")
private val SIZE = listOf("Otomatis", "Ukuran asli")
private val IMGFMT = listOf("Raw", "Sparse")
private val OUTFMT = listOf("IMG", "DAT", "BR")
private val EROFS_MODE = listOf("lz4hc", "lz4", "lzma")
private val SUPER_TYPE = listOf("A" to "a_only", "AB" to "ab", "VAB" to "virtual_ab")

@Composable
private fun RepackSection(
    eng: UnpackEngine,
    project: String?,
    busy: Boolean,
    tick: Int,
    run: (String, String, Boolean) -> Unit,
    refresh: () -> Unit
) {
    if (project == null) return NeedProject()
    val dirs by rememberFileList(eng, project, "dir", tick)
    val imgs by rememberFileList(eng, project, "img", tick)

    var dirSel by remember { mutableStateOf(setOf<String>()) }
    var target by remember { mutableStateOf(0) }
    var rw by remember { mutableStateOf(0) }
    var size by remember { mutableStateOf(0) }
    var fmt by remember { mutableStateOf(0) }
    var outFmt by remember { mutableStateOf(0) }
    var br by remember { mutableStateOf(3) }
    var erofsLevel by remember { mutableStateOf(8) }
    var erofsMode by remember { mutableStateOf(0) }
    var shrink by remember { mutableStateOf(false) }
    var delAvb by remember { mutableStateOf(false) }

    GlassCard {
        Heading("Repack satu partisi")
        Body("Pilih folder hasil unpack yang akan dirakit ulang.", dim = true)
        if (dirs.isEmpty()) Body("Belum ada folder hasil unpack.", dim = true)
        dirs.forEach { (v, l) -> CheckRow(l, v in dirSel) { dirSel = if (v in dirSel) dirSel - v else dirSel + v } }

        Caption("Jenis repack")
        ChipRow(TARGETS, target) { target = it }
        Caption("RW (khusus ext4)")
        ChipRow(RW, rw) { rw = it }
        Caption("Ukuran (khusus ext4)")
        ChipRow(SIZE, size) { size = it }
        Caption("Format img (ext4/erofs)")
        ChipRow(IMGFMT, fmt) { fmt = it }
        Caption("Format file keluaran (ext4/erofs)")
        ChipRow(OUTFMT, outFmt) { outFmt = it }
        LevelRow("Level kompresi br: {}", br, 1, 7) { br = it }
        LevelRow("Level kompresi erofs/f2fs: {}", erofsLevel, 0, 9) { erofsLevel = it }
        Caption("Mode erofs")
        ChipRow(EROFS_MODE, erofsMode) { erofsMode = it }
        SwitchRow("Padatkan ruang img ext4", "Khusus ext4", shrink) { shrink = it }
        SwitchRow("Hapus enkripsi data avb", "Mengubah fstab. Tidak universal dan bisa menyebabkan bootloop.", delAvb) { delAvb = it }

        GlassButton("Repack", {
            val o = UnpackEngine.RepackOptions(
                dirs = dirSel.toList(),
                target = target,
                readWrite = if (rw == 0) 1 else 0,
                autoSize = if (size == 0) 1 else 0,
                sparse = fmt,
                outFormat = outFmt,
                brLevel = br,
                erofsLevel = erofsLevel,
                erofsMode = EROFS_MODE[erofsMode],
                shrinkExt4 = shrink,
                removeAvb = delAvb
            )
            run("Repack partisi", eng.repackScript(o), true)
        }, primary = true, enabled = !busy && dirSel.isNotEmpty())
    }

    var superType by remember { mutableStateOf(0) }
    var superFmt by remember { mutableStateOf(0) }
    var superSize by remember { mutableStateOf("8.5") }
    var superGroup by remember { mutableStateOf("qti_dynamic_partitions") }
    var superImgs by remember { mutableStateOf(setOf<String>()) }

    GlassCard {
        Heading("Repack super.img")
        Caption("Tipe")
        ChipRow(SUPER_TYPE.map { it.second }, superType) { superType = it }
        Caption("Format")
        ChipRow(listOf("Sparse", "Raw"), superFmt) { superFmt = it }
        GlassTextField(superSize, { superSize = it }, "Ukuran total super.img (GB)", keyboard = KeyboardType.Decimal)
        GlassTextField(superGroup, { superGroup = it }, "Nama grup (biarkan bila tidak tahu)")
        Body("Pilih file IMG yang dimasukkan ke super.", dim = true)
        if (imgs.isEmpty()) Body("Tidak ada file .img di folder proyek.", dim = true)
        imgs.forEach { (v, l) -> CheckRow(l, v in superImgs) { superImgs = if (v in superImgs) superImgs - v else superImgs + v } }
        val sizeOk = superSize.toDoubleOrNull()?.let { it > 0 } == true
        val groupOk = Regex("[A-Za-z0-9_]{1,64}").matches(superGroup)
        GlassButton("Repack super.img", {
            run(
                "Repack super.img",
                eng.repackSuperScript(SUPER_TYPE[superType].first, if (superFmt == 0) 1 else 0, superSize, superGroup, superImgs.toList()),
                true
            )
        }, primary = true, enabled = !busy && superImgs.isNotEmpty() && sizeOk && groupOk)
    }
}

// ============================================================================ Konversi

@Composable
private fun ConvertSection(
    eng: UnpackEngine,
    project: String?,
    busy: Boolean,
    tick: Int,
    run: (String, String, Boolean) -> Unit,
    refresh: () -> Unit
) {
    if (project == null) return NeedProject()
    val imgs by rememberFileList(eng, project, "img", tick)
    val zst by rememberFileList(eng, project, "zst_img", tick)
    val parts by rememberFileList(eng, project, "split_sparse", tick)

    var simgSel by remember { mutableStateOf(setOf<String>()) }
    var simgDel by remember { mutableStateOf(false) }
    GlassCard {
        Heading("img ke simg")
        Body("ext4/erofs diubah ke sparse; sparse dikembalikan ke raw. Hasil di folder out.", dim = true)
        if (imgs.isEmpty()) Body("Tidak ada file .img.", dim = true)
        imgs.forEach { (v, l) -> CheckRow(l, v in simgSel) { simgSel = if (v in simgSel) simgSel - v else simgSel + v } }
        SwitchRow("Hapus file sumber", null, simgDel) { simgDel = it }
        GlassButton("Konversi", { run("img ke simg", eng.imgToSimgScript(simgSel.toList(), simgDel), true) },
            primary = true, enabled = !busy && simgSel.isNotEmpty())
    }

    var datSel by remember { mutableStateOf(setOf<String>()) }
    var datDel by remember { mutableStateOf(false) }
    var datBr by remember { mutableStateOf(0) }
    var datLevel by remember { mutableStateOf(3) }
    GlassCard {
        Heading("img ke dat / br")
        Caption("Tipe")
        ChipRow(listOf("DAT", "BR"), datBr) { datBr = it }
        LevelRow("Level kompresi br: {}", datLevel, 1, 7) { datLevel = it }
        if (imgs.isEmpty()) Body("Tidak ada file .img.", dim = true)
        imgs.forEach { (v, l) -> CheckRow(l, v in datSel) { datSel = if (v in datSel) datSel - v else datSel + v } }
        SwitchRow("Hapus file sumber", null, datDel) { datDel = it }
        GlassButton("Konversi", { run("img ke dat/br", eng.imgToDatBrScript(datSel.toList(), datDel, datBr == 1, datLevel), true) },
            primary = true, enabled = !busy && datSel.isNotEmpty())
    }

    var zstSel by remember { mutableStateOf(setOf<String>()) }
    var zstDel by remember { mutableStateOf(false) }
    var zstLevel by remember { mutableStateOf(3) }
    GlassCard {
        Heading("Konversi zst dan img")
        Body(".zst diekstrak ke .img, .img dikompres ke .zst. Hasil di folder out.", dim = true)
        LevelRow("Level kompresi zstd: {}", zstLevel, 0, 19) { zstLevel = it }
        if (zst.isEmpty()) Body("Tidak ada file .zst/.img.", dim = true)
        zst.forEach { (v, l) -> CheckRow(l, v in zstSel) { zstSel = if (v in zstSel) zstSel - v else zstSel + v } }
        SwitchRow("Hapus file sumber", null, zstDel) { zstDel = it }
        GlassButton("Konversi", { run("Konversi zstd", eng.zstScript(zstSel.toList(), zstDel, zstLevel), true) },
            primary = true, enabled = !busy && zstSel.isNotEmpty())
    }

    var mergeSel by remember { mutableStateOf(setOf<String>()) }
    var mergeDel by remember { mutableStateOf(false) }
    GlassCard {
        Heading("Gabung sparse bersegmen")
        Body("Gabungkan IMG bersegmen (nama.img.0, nama.img.1, ...) menjadi satu IMG.", dim = true)
        if (parts.isEmpty()) Body("Tidak ada file bersegmen.", dim = true)
        parts.forEach { (v, l) -> CheckRow(l, v in mergeSel) { mergeSel = if (v in mergeSel) mergeSel - v else mergeSel + v } }
        SwitchRow("Hapus file sumber", null, mergeDel) { mergeDel = it }
        GlassButton("Gabungkan", { run("Gabung sparse", eng.mergeSparseScript(mergeSel.toList(), mergeDel), true) },
            primary = true, enabled = !busy && mergeSel.isNotEmpty())
    }
    GlassButton("Muat ulang daftar file", refresh, enabled = !busy)
}

// ============================================================================ Partisi perangkat

@Composable
private fun PartitionSection(
    eng: UnpackEngine,
    project: String?,
    busy: Boolean,
    tick: Int,
    run: (String, String, Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var blocks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var extractSel by remember { mutableStateOf(setOf<String>()) }
    var flashSel by remember { mutableStateOf<String?>(null) }
    var flashPath by remember { mutableStateOf("${UnpackEngine.BASE_DIR}/image/") }
    var reboot by remember { mutableStateOf(0) } // 0 tidak, 1 sistem, 2 recovery
    var confirmFlash by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            blocks = eng.listBlocks()
            loading = false
        }
    }
    LaunchedEffect(tick) { if (blocks.isEmpty()) load() }

    GlassCard {
        Heading("Ekstrak img dari perangkat")
        Body("Salin partisi yang sedang berjalan ke ${UnpackEngine.BASE_DIR}/image.", dim = true)
        if (loading) Spinner("Memindai partisi")
        if (blocks.isEmpty() && !loading) Body("Partisi tidak ditemukan.", dim = true)
        blocks.forEach { (v, l) -> CheckRow(l, v in extractSel) { extractSel = if (v in extractSel) extractSel - v else extractSel + v } }
        GlassButton("Ekstrak partisi", { run("Ekstrak partisi", eng.extractBlocksScript(extractSel.toList()), false) },
            primary = true, enabled = !busy && extractSel.isNotEmpty())
        GlassButton("Pindai ulang partisi", { load() }, enabled = !busy && !loading)
    }

    GlassCard {
        Heading("Flash img ke perangkat")
        Body("Menulis langsung ke partisi. Salah file atau salah partisi bisa membuat HP bootloop atau mati total. Partisi system, vendor, product, odm, super, userdata, dan metadata diblokir.", dim = true)
        blocks.forEach { (v, l) -> CheckRow(l, v == flashSel) { flashSel = if (v == flashSel) null else v } }
        GlassTextField(flashPath, { flashPath = it }, "Path absolut file .img", mono = true)
        Caption("Setelah selesai")
        ChipRow(listOf("Tidak restart", "Restart sistem", "Restart recovery"), reboot) { reboot = it }
        msg?.let { Caption(it) }
        GlassButton("Flash", {
            val name = flashSel?.substringAfterLast('/') ?: ""
            when {
                FLASH_BLOCKED.matches(name) -> msg = "Partisi $name diblokir untuk flash."
                !flashPath.startsWith("/") || !flashPath.endsWith(".img") -> msg = "Path harus absolut dan berakhiran .img."
                else -> { msg = null; confirmFlash = true }
            }
        }, danger = true, enabled = !busy && flashSel != null && flashPath.isNotBlank())
    }
    if (confirmFlash) {
        val name = flashSel?.substringAfterLast('/') ?: ""
        ConfirmDialog(
            "Flash partisi $name?",
            "File: $flashPath\n\nPartisi akan ditimpa dan tidak bisa dibatalkan. Pastikan file cocok dengan perangkat dan Anda punya cadangan.",
            confirmText = "Flash", danger = true,
            onConfirm = {
                confirmFlash = false
                val rb = when (reboot) { 1 -> ""; 2 -> "recovery"; else -> null }
                flashSel?.let { run("Flash $name", eng.flashBlockScript(it, flashPath, rb), false) }
            },
            onDismiss = { confirmFlash = false }
        )
    }
}

// ============================================================================ Skrip

@Composable
private fun ScriptSection(
    eng: UnpackEngine,
    project: String?,
    busy: Boolean,
    run: (String, String, Boolean) -> Unit
) {
    var cmd by rememberSaveable { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    GlassCard {
        Heading("Eksekutor skrip")
        Body("Jalankan perintah di folder proyek dengan semua tool (dna, lpmake, mkfs.erofs, magiskboot, zstd, busybox) sudah ada di PATH. Perintah berbahaya diblokir.", dim = true)
        GlassTextField(cmd, { cmd = it; err = null }, "Perintah", singleLine = false, mono = true, minLines = 3)
        err?.let { Caption(it) }
        GlassButton("Jalankan", {
            val reason = CommandGuard.check(cmd)
            if (reason != null) err = "Diblokir: $reason" else confirm = true
        }, primary = true, enabled = !busy && cmd.isNotBlank())
    }
    if (confirm) {
        ConfirmDialog(
            "Jalankan perintah ini sebagai root?", cmd, confirmText = "Jalankan",
            onConfirm = { confirm = false; run("Skrip", cmd, project != null) },
            onDismiss = { confirm = false }
        )
    }
}

// ============================================================================ Komponen bersama

@Composable
private fun NeedProject() {
    GlassCard {
        Heading("Belum ada proyek")
        Body("Buka tab Proyek, buat atau pilih proyek dulu.", dim = true)
    }
}

@Composable
private fun rememberFileList(eng: UnpackEngine, project: String?, kind: String, tick: Int): State<List<Pair<String, String>>> {
    val st = remember(kind) { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(project, kind, tick) {
        st.value = if (project == null && kind != "zip") emptyList() else eng.listFiles(project, kind)
    }
    return st
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked, { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = g.accent, uncheckedColor = g.textDim)
        )
        Spacer(Modifier.width(4.dp))
        Body(label, Modifier.weight(1f))
    }
}

@Composable
private fun SwitchRow(label: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Body(label)
            if (desc != null) Caption(desc)
        }
        Switch(checked, onChange)
    }
}

/** [template] memakai {} untuk nilai; contoh "Level: {}". */
@Composable
private fun LevelRow(template: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val g = LocalGlass.current
    Column {
        Caption(template.replace("{}", value.toString()))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(thumbColor = g.accent, activeTrackColor = g.accent)
        )
    }
}

@Composable
private fun OutputCard(log: String, busy: Boolean, onStop: () -> Unit, onClear: () -> Unit) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()
    LaunchedEffect(log) { scroll.scrollTo(scroll.maxValue) }
    GlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Heading("Keluaran")
            if (busy) Spinner("Berjalan")
        }
        if (log.isBlank()) Body("Belum ada keluaran.", dim = true)
        else SelectionContainer {
            Box(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(scroll)) { Mono(log) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Hentikan", onStop, Modifier.weight(1f), danger = true, enabled = busy)
            GlassButton("Salin", { copyText(ctx, "Unpack & Repack", log) }, Modifier.weight(1f), enabled = log.isNotBlank())
            GlassButton("Bersihkan", onClear, Modifier.weight(1f), enabled = log.isNotBlank() && !busy)
        }
    }
}
