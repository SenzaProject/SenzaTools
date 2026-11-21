package com.senzaproject.senzatools.platform.flashtool

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.senzaproject.senzatools.data.OperationLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Format paket backup IMEI + utilitas verifikasinya (tanpa USB, murni data).
 *
 * Paket = ZIP berisi satu image mentah per partisi ("nvram.img", ...) + "manifest.json" yang
 * mencatat HW code chip, geometri partisi, dan SHA-256 tiap image. IMEI di chip MediaTek tersimpan
 * di dalam partisi NVRAM/NVDATA (dan salinan di nvcfg/protect1/protect2), jadi yang dibackup
 * adalah partisi utuh apa adanya — tidak ada parsing atau pengubahan isi.
 */
object ImeiPackage {
    const val FORMAT = "senza-imei-backup"
    const val VERSION = 1
    const val MANIFEST_NAME = "manifest.json"
    const val MAX_PARTITION_BYTES = 128L * 1024 * 1024

    /** Nama partisi (GPT) yang lazim menyimpan data IMEI/NVRAM di MediaTek. Dicocokkan tanpa peduli huruf besar/kecil. */
    val CANDIDATE_NAMES = listOf("nvram", "nvdata", "nvcfg", "protect1", "protect2", "proinfo")
    private val EXT4_NAMES = setOf("nvdata", "nvcfg", "protect1", "protect2")

    fun findCandidates(entries: List<GptParser.Entry>): List<GptParser.Entry> =
        CANDIDATE_NAMES.mapNotNull { n -> entries.firstOrNull { it.name.equals(n, ignoreCase = true) } }

    fun isExt4Name(name: String) = name.lowercase() in EXT4_NAMES

    data class Item(
        val name: String, val firstLba: Long, val sizeBytes: Long, val sha256: String,
        val blank: Boolean, val ext4: Boolean?
    )

    data class Manifest(val hwCode: Int, val createdMs: Long, val items: List<Item>)

    data class VerifyResult(val manifest: Manifest?, val problems: List<String>)

    /** Hash + deteksi "partisi kosong" + cek magic ext4 sambil data lewat. */
    class HashingSink {
        private val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
            private set
        private var allZero = true
        private var allFF = true
        private val head = ByteArray(2048)
        private var headLen = 0

        fun update(buf: ByteArray, n: Int) {
            md.update(buf, 0, n)
            if (headLen < head.size) {
                val c = minOf(n, head.size - headLen)
                System.arraycopy(buf, 0, head, headLen, c)
                headLen += c
            }
            if (allZero || allFF) {
                for (i in 0 until n) {
                    val b = buf[i]
                    if (b != 0.toByte()) allZero = false
                    if (b != 0xFF.toByte()) allFF = false
                    if (!allZero && !allFF) break
                }
            }
            total += n
        }

        fun sha256Hex(): String = hex(md.digest())
        fun isBlank() = total > 0 && (allZero || allFF)
        /** true/false kalau cukup data untuk dicek, null kalau terlalu kecil. */
        fun looksExt4(): Boolean? =
            if (headLen >= 1082) head[1080] == 0x53.toByte() && head[1081] == 0xEF.toByte() else null
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun sha256Hex(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))

    fun manifestToJson(m: Manifest): String {
        val arr = JSONArray()
        m.items.forEach { i ->
            arr.put(JSONObject().apply {
                put("name", i.name); put("file", "${i.name}.img")
                put("firstLba", i.firstLba); put("sizeBytes", i.sizeBytes); put("sha256", i.sha256)
                put("blank", i.blank)
                if (i.ext4 != null) put("ext4", i.ext4)
            })
        }
        return JSONObject().apply {
            put("format", FORMAT); put("version", VERSION)
            put("app", "Senza Tools")
            put("hwCode", m.hwCode); put("createdMs", m.createdMs)
            put("partitions", arr)
        }.toString(2)
    }

    fun parseManifest(text: String): Manifest? = try {
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT || o.optInt("version", 0) !in 1..VERSION) null
        else {
            val arr = o.getJSONArray("partitions")
            val items = (0 until arr.length()).map { k ->
                val p = arr.getJSONObject(k)
                Item(
                    name = p.getString("name"), firstLba = p.optLong("firstLba", -1L),
                    sizeBytes = p.getLong("sizeBytes"), sha256 = p.getString("sha256").lowercase(),
                    blank = p.optBoolean("blank", false),
                    ext4 = if (p.has("ext4")) p.getBoolean("ext4") else null
                )
            }
            Manifest(o.getInt("hwCode"), o.optLong("createdMs", 0L), items)
        }
    } catch (_: Throwable) { null }

    /**
     * Baca seluruh ZIP secara berurutan, hitung SHA-256 + ukuran tiap image, lalu cocokkan dengan
     * manifest. Tidak menyentuh device. [onBytes] dipanggil untuk progres.
     */
    fun verifyStream(input: InputStream, onBytes: (Long) -> Unit = {}): VerifyResult {
        val problems = ArrayList<String>()
        var manifestText: String? = null
        val actual = HashMap<String, Pair<Long, String>>()
        val buf = ByteArray(1 shl 16)
        var seen = 0L
        try {
            ZipInputStream(input).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (e.name == MANIFEST_NAME) {
                        val bo = java.io.ByteArrayOutputStream()
                        while (true) {
                            val n = zin.read(buf); if (n < 0) break
                            bo.write(buf, 0, n)
                            if (bo.size() > 1_000_000) { problems.add("manifest.json terlalu besar."); break }
                        }
                        manifestText = bo.toString("UTF-8")
                    } else {
                        val sink = HashingSink()
                        while (true) {
                            val n = zin.read(buf); if (n < 0) break
                            sink.update(buf, n); seen += n; onBytes(seen)
                        }
                        actual[e.name] = sink.total to sink.sha256Hex()
                    }
                }
            }
        } catch (t: Throwable) {
            return VerifyResult(null, listOf("File backup tidak bisa dibaca sebagai ZIP: ${t.message ?: t}"))
        }
        val mt = manifestText ?: return VerifyResult(null, problems + "manifest.json tidak ada di paket ini.")
        val m = parseManifest(mt) ?: return VerifyResult(null, problems + "manifest.json bukan format backup IMEI Senza Tools yang valid.")
        if (m.items.isEmpty()) problems.add("Paket tidak berisi partisi apa pun.")
        m.items.forEach { mi ->
            val a = actual["${mi.name}.img"]
            when {
                a == null -> problems.add("Image ${mi.name}.img tidak ada di paket.")
                a.first != mi.sizeBytes -> problems.add("Ukuran ${mi.name}.img (${a.first} B) tidak sama dengan manifest (${mi.sizeBytes} B).")
                a.second != mi.sha256 -> problems.add("SHA-256 ${mi.name}.img tidak cocok — file rusak atau diubah.")
            }
        }
        return VerifyResult(m, problems)
    }
}

// ---------------------------------------------------------------------------------------------

data class ImeiReport(val ok: Boolean, val title: String, val lines: List<String>)

data class RestoreItem(val name: String, val sizeBytes: Long, val currentLba: Long, val sha256: String)

data class RestorePlan(
    val sourceLabel: String,
    val uri: Uri?,
    val file: File?,
    val createdMs: Long,
    val hwCode: Int,
    val items: List<RestoreItem>,
    val blockers: List<String>,
    val warnings: List<String>
) {
    val ok: Boolean get() = blockers.isEmpty() && items.isNotEmpty()
}

data class ImeiState(
    val busy: Boolean = false,
    val op: String? = null,
    val step: String? = null,
    val progress: Float = 0f,
    val cancellable: Boolean = false,
    val report: ImeiReport? = null,
    val plan: RestorePlan? = null,
    val snapshotName: String? = null
)

private class ImeiException(msg: String) : Exception(msg)

/**
 * Backup & restore IMEI lewat sesi XFlash yang sudah hidup (butuh tahap GPT selesai).
 *
 * Jaminan yang benar-benar dijalankan (bukan sekadar tampilan):
 *  - Backup: tiap partisi dibaca dari device, di-hash, ditulis ke ZIP, lalu file ZIP dibaca ulang
 *    dan diverifikasi terhadap manifest sebelum dilaporkan berhasil.
 *  - Restore: file diverifikasi dulu (SHA-256), geometri dicocokkan dengan GPT device (ukuran harus
 *    persis sama, HW code harus sama), snapshot kondisi sekarang disimpan, baru ditulis; setiap
 *    partisi dibaca ulang dan hash-nya harus sama dengan backup, kalau tidak dilaporkan GAGAL.
 */
class ImeiEngine(
    private val ctx: Context,
    private val log: OperationLog,
    private val host: FlashToolEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ImeiState(snapshotName = latestSnapshot()?.name))
    val state: StateFlow<ImeiState> = _state.asStateFlow()

    @Volatile private var cancelRequested = false
    private var lastPublish = 0L

    private val snapDir: File get() = File(ctx.filesDir, "imei_snapshots").also { it.mkdirs() }

    private fun latestSnapshot(): File? =
        File(ctx.filesDir, "imei_snapshots").listFiles { f -> f.name.endsWith(".zip") }?.maxByOrNull { it.lastModified() }

    fun cancel() { if (_state.value.cancellable) cancelRequested = true }
    fun dismissReport() { _state.update { it.copy(report = null) } }
    fun dismissPlan() { if (!_state.value.busy) _state.update { it.copy(plan = null) } }

    private fun begin(op: String, step: String, cancellable: Boolean) {
        cancelRequested = false
        _state.update { it.copy(busy = true, op = op, step = step, progress = 0f, cancellable = cancellable, report = null) }
    }

    private fun end(report: ImeiReport?) {
        _state.update {
            it.copy(busy = false, op = null, step = null, progress = 0f, cancellable = false,
                report = report ?: it.report, snapshotName = latestSnapshot()?.name)
        }
    }

    private fun progress(frac: Float, step: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 120) return
        lastPublish = now
        _state.update { it.copy(progress = frac.coerceIn(0f, 1f), step = step) }
    }

    private fun fmtMb(b: Long) = "%.1f MB".format(b / 1048576.0)

    // ------------------------------------------------------------------ BACKUP

    fun backup(outUri: Uri, selectedNames: Set<String>) {
        val io = host.acquireIo()
        if (io == null) {
            _state.update { it.copy(report = ImeiReport(false, "Backup tidak bisa dimulai",
                listOf("Sesi belum siap: selesaikan sampai tahap baca partition table (GPT), atau operasi lain masih berjalan."))) }
            return
        }
        val chosen = ImeiPackage.findCandidates(io.gpt).filter { it.name.lowercase() in selectedNames }
        if (chosen.isEmpty()) {
            host.releaseIo()
            _state.update { it.copy(report = ImeiReport(false, "Backup tidak bisa dimulai", listOf("Tidak ada partisi IMEI yang dipilih/terdeteksi di GPT device."))) }
            return
        }
        begin("backup", "Menyiapkan backup…", cancellable = true)
        scope.launch {
            var report: ImeiReport
            try {
                val out = ctx.contentResolver.openOutputStream(outUri, "w") ?: throw ImeiException("Tidak bisa membuka file tujuan untuk ditulis.")
                val (manifest, warns) = streamBackup(io, chosen, out, 0f, 0.85f, "Membaca")
                progress(0.87f, "Memverifikasi file backup…", force = true)
                val input = ctx.contentResolver.openInputStream(outUri) ?: throw ImeiException("Tidak bisa membuka ulang file backup untuk verifikasi.")
                val v = input.use { ImeiPackage.verifyStream(it) }
                if (v.problems.isNotEmpty() || v.manifest == null) {
                    throw ImeiException("Verifikasi file backup GAGAL: " + v.problems.joinToString("; "))
                }
                val lines = ArrayList<String>()
                manifest.items.forEach { lines.add("✓ ${it.name} — ${fmtMb(it.sizeBytes)} — sha256 ${it.sha256.take(12)}…") }
                lines.addAll(warns.map { "⚠ $it" })
                lines.add("File backup sudah dibaca ulang dan hash-nya cocok dengan manifest. Simpan file ini di tempat aman (jangan hanya di HP ini).")
                report = ImeiReport(true, "Backup IMEI selesai (${manifest.items.size} partisi)", lines)
                log.add("FlashTool", "Backup IMEI", "OK: ${manifest.items.joinToString { it.name }}")
            } catch (t: Throwable) {
                try { DocumentsContract.deleteDocument(ctx.contentResolver, outUri) } catch (_: Throwable) {}
                report = ImeiReport(false, "Backup IMEI gagal", listOf(t.message ?: t.toString(), "File backup yang belum lengkap sudah dibuang. Tidak ada yang ditulis ke device."))
                log.add("FlashTool", "Backup IMEI", "ERROR: ${t.message}")
            } finally {
                host.releaseIo()
            }
            end(report)
        }
    }

    /** Baca partisi dari device → tulis ZIP ke [out] (ditutup di sini). Melempar ImeiException kalau gagal. */
    private fun streamBackup(
        io: IoHandle, chosen: List<GptParser.Entry>, out: OutputStream,
        base: Float, span: Float, verb: String
    ): Pair<ImeiPackage.Manifest, List<String>> {
        val total = chosen.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        var done = 0L
        val items = ArrayList<ImeiPackage.Item>()
        val warnings = ArrayList<String>()
        val zip = ZipOutputStream(BufferedOutputStream(out, 1 shl 16))
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        try {
            for (e in chosen) {
                if (e.sizeBytes > ImeiPackage.MAX_PARTITION_BYTES) {
                    throw ImeiException("Partisi ${e.name} berukuran ${fmtMb(e.sizeBytes)} — di luar batas wajar partisi IMEI, dibatalkan.")
                }
                val sink = ImeiPackage.HashingSink()
                zip.putNextEntry(ZipEntry("${e.name}.img"))
                val res = XFlashProtocol.readDataStream(
                    io.conn, io.intf, XFlashProtocol.STORAGE_EMMC, XFlashProtocol.PARTTYPE_USER,
                    e.firstLba * 512L, e.sizeBytes, shouldCancel = { cancelRequested }
                ) { buf, n ->
                    zip.write(buf, 0, n); sink.update(buf, n); done += n
                    progress(base + span * (done.toFloat() / total), "$verb ${e.name} (${fmtMb(done)} / ${fmtMb(total)})")
                }
                zip.closeEntry()
                if (res is XFlashProtocol.XResult.Error) {
                    throw ImeiException("Gagal membaca partisi ${e.name}: [${res.stage}] ${res.message}")
                }
                if (sink.total != e.sizeBytes) throw ImeiException("Jumlah byte ${e.name} tidak sesuai (${sink.total} dari ${e.sizeBytes}).")
                val ext4 = if (ImeiPackage.isExt4Name(e.name)) sink.looksExt4() else null
                if (sink.isBlank()) warnings.add("Partisi ${e.name} isinya kosong (semua 0x00/0xFF) — kemungkinan data IMEI sudah hilang di device ini.")
                if (ext4 == false) warnings.add("Partisi ${e.name} tidak berisi filesystem ext4 yang dikenali — tetap dibackup apa adanya.")
                items.add(ImeiPackage.Item(e.name, e.firstLba, e.sizeBytes, sink.sha256Hex(), sink.isBlank(), ext4))
            }
            val manifest = ImeiPackage.Manifest(io.hwCode, System.currentTimeMillis(), items)
            zip.putNextEntry(ZipEntry(ImeiPackage.MANIFEST_NAME))
            zip.write(ImeiPackage.manifestToJson(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.finish()
            zip.flush()
            return manifest to warnings
        } finally {
            try { zip.close() } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------ RESTORE: PERSIAPAN

    fun prepareRestore(uri: Uri) = prepare("File dipilih", uri, null)

    fun prepareRestoreFromSnapshot() {
        val f = latestSnapshot()
        if (f == null) {
            _state.update { it.copy(report = ImeiReport(false, "Tidak ada snapshot", listOf("Belum ada snapshot sebelum-restore yang tersimpan."))) }
            return
        }
        prepare("Snapshot: ${f.name}", null, f)
    }

    private fun prepare(label: String, uri: Uri?, file: File?) {
        val io = host.acquireIo()
        if (io == null) {
            _state.update { it.copy(report = ImeiReport(false, "Restore tidak bisa dipersiapkan",
                listOf("Sesi belum siap: selesaikan sampai tahap baca partition table (GPT), atau operasi lain masih berjalan."))) }
            return
        }
        begin("verify", "Memverifikasi file backup…", cancellable = false)
        _state.update { it.copy(plan = null) }
        scope.launch {
            var report: ImeiReport? = null
            try {
                val v = openSource(uri, file).use { ImeiPackage.verifyStream(it) { n -> progress(0.5f, "Memverifikasi… ${fmtMb(n)}") } }
                val m = v.manifest
                val blockers = ArrayList<String>(v.problems)
                val warnings = ArrayList<String>()
                val items = ArrayList<RestoreItem>()
                if (m != null) {
                    if (m.hwCode != io.hwCode) {
                        blockers.add("HW code backup (0x${m.hwCode.toString(16)}) berbeda dengan chip device sekarang (0x${io.hwCode.toString(16)}).")
                    }
                    for (mi in m.items) {
                        val g = io.gpt.firstOrNull { e -> e.name.equals(mi.name, ignoreCase = true) }
                        when {
                            g == null -> blockers.add("Partisi ${mi.name} tidak ada di GPT device ini.")
                            g.sizeBytes != mi.sizeBytes ->
                                blockers.add("Ukuran partisi ${mi.name} di device (${g.sizeBytes} B) tidak sama dengan backup (${mi.sizeBytes} B). Backup ini bukan dari layout partisi yang sama.")
                            mi.sizeBytes > ImeiPackage.MAX_PARTITION_BYTES -> blockers.add("Partisi ${mi.name} terlalu besar untuk dipulihkan lewat fitur ini.")
                            else -> {
                                if (mi.firstLba >= 0 && mi.firstLba != g.firstLba) warnings.add("Posisi ${mi.name} berbeda dari saat backup (layout firmware berubah); ditulis ke posisi GPT sekarang.")
                                if (mi.blank) warnings.add("Backup ${mi.name} berisi data kosong — memulihkannya tidak akan mengembalikan IMEI.")
                                items.add(RestoreItem(mi.name, mi.sizeBytes, g.firstLba, mi.sha256))
                            }
                        }
                    }
                    if (m.items.none { it.name.equals("nvram", true) || it.name.equals("nvdata", true) }) {
                        warnings.add("Paket ini tidak berisi nvram maupun nvdata — kemungkinan tidak cukup untuk memulihkan IMEI.")
                    }
                }
                val plan = RestorePlan(label, uri, file, m?.createdMs ?: 0L, m?.hwCode ?: 0, items, blockers, warnings)
                _state.update { it.copy(plan = plan) }
                log.add("FlashTool", "Verifikasi backup IMEI", if (plan.ok) "OK: ${items.joinToString { it.name }}" else "DIBLOKIR: ${blockers.firstOrNull()}")
            } catch (t: Throwable) {
                report = ImeiReport(false, "Verifikasi backup gagal", listOf(t.message ?: t.toString()))
            } finally {
                host.releaseIo()
            }
            end(report)
        }
    }

    private fun openSource(uri: Uri?, file: File?): InputStream =
        if (file != null) file.inputStream()
        else ctx.contentResolver.openInputStream(uri ?: throw ImeiException("Sumber backup tidak ada."))
            ?: throw ImeiException("Tidak bisa membuka file backup.")

    // ------------------------------------------------------------------ RESTORE: EKSEKUSI

    fun confirmRestore() {
        val plan = _state.value.plan
        if (plan == null || !plan.ok) return
        val io = host.acquireIo()
        if (io == null) {
            _state.update { it.copy(report = ImeiReport(false, "Restore tidak bisa dimulai", listOf("Sesi belum siap atau operasi lain masih berjalan."))) }
            return
        }
        begin("restore", "Menyimpan snapshot kondisi sekarang…", cancellable = true)
        _state.update { it.copy(plan = null) }
        scope.launch {
            val written = ArrayList<String>()
            var snapName: String? = null
            var report: ImeiReport
            try {
                // Fase 1: snapshot kondisi device SEKARANG (read-only, bisa dibatalkan, belum ada yang ditulis).
                val entries = plan.items.map { ri ->
                    io.gpt.firstOrNull { it.name.equals(ri.name, true) && it.firstLba == ri.currentLba }
                        ?: throw ImeiException("GPT berubah sejak verifikasi (partisi ${ri.name}); ulangi dari verifikasi.")
                }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val snap = File(snapDir, "pre_restore_$stamp.zip")
                try {
                    streamBackup(io, entries, snap.outputStream(), 0f, 0.35f, "Snapshot")
                } catch (t: Throwable) {
                    snap.delete()
                    throw ImeiException("Snapshot sebelum restore gagal, restore DIBATALKAN (tidak ada yang ditulis): ${t.message}")
                }
                snapName = snap.name
                pruneSnapshots(keep = 5)

                // Fase 2: tulis + baca ulang + bandingkan hash. Tidak bisa dibatalkan.
                cancelRequested = false
                _state.update { it.copy(cancellable = false) }
                val total = plan.items.sumOf { it.sizeBytes }.coerceAtLeast(1L)
                var doneBytes = 0L
                openSource(plan.uri, plan.file).use { raw ->
                    ZipInputStream(raw).use { zin ->
                        while (true) {
                            val e = zin.nextEntry ?: break
                            val item = plan.items.firstOrNull { "${it.name}.img" == e.name } ?: continue
                            val data = ByteArray(item.sizeBytes.toInt())
                            var off = 0
                            while (off < data.size) {
                                val n = zin.read(data, off, data.size - off)
                                if (n < 0) break
                                off += n
                            }
                            if (off != data.size) throw ImeiException("Image ${item.name} di file backup terpotong. Tidak ada perubahan pada partisi ini.")
                            if (ImeiPackage.sha256Hex(data) != item.sha256) {
                                throw ImeiException("Hash image ${item.name} berubah sejak verifikasi. Tidak ada perubahan pada partisi ini.")
                            }
                            val addr = item.currentLba * 512L
                            progress(0.35f + 0.65f * (doneBytes.toFloat() / total), "Menulis ${item.name}…", force = true)
                            val w = XFlashProtocol.writeData(
                                io.conn, io.intf, XFlashProtocol.STORAGE_EMMC, XFlashProtocol.PARTTYPE_USER, addr, data
                            ) { sent ->
                                progress(0.35f + 0.65f * ((doneBytes + sent / 2).toFloat() / total), "Menulis ${item.name} (${fmtMb(sent.toLong())} / ${fmtMb(item.sizeBytes)})")
                            }
                            if (w is XFlashProtocol.XResult.Error) {
                                throw ImeiException("Tulis ${item.name} GAGAL: [${w.stage}] ${w.message}. Status partisi ini tidak pasti — jangan reboot dulu.")
                            }
                            progress(0.35f + 0.65f * ((doneBytes + item.sizeBytes / 2).toFloat() / total), "Memverifikasi ${item.name}…", force = true)
                            val sink = ImeiPackage.HashingSink()
                            val r = XFlashProtocol.readDataStream(
                                io.conn, io.intf, XFlashProtocol.STORAGE_EMMC, XFlashProtocol.PARTTYPE_USER, addr, item.sizeBytes
                            ) { buf, n -> sink.update(buf, n) }
                            if (r is XFlashProtocol.XResult.Error) {
                                throw ImeiException("Baca-ulang ${item.name} untuk verifikasi GAGAL: [${r.stage}] ${r.message}. Hasil tulis belum terbukti.")
                            }
                            if (sink.sha256Hex() != item.sha256) {
                                throw ImeiException("Verifikasi ${item.name} GAGAL: isi partisi setelah ditulis TIDAK sama dengan backup.")
                            }
                            written.add(item.name)
                            doneBytes += item.sizeBytes
                        }
                    }
                }
                if (written.size != plan.items.size) {
                    throw ImeiException("Tidak semua partisi ditemukan di file backup (tertulis ${written.size} dari ${plan.items.size}).")
                }
                val lines = written.map { "✓ $it — ditulis & terverifikasi (hash baca-ulang = hash backup)" } +
                    listOf("Snapshot kondisi sebelum restore: $snapName",
                        "Langkah berikut: cabut kabel USB, nyalakan device secara normal, lalu cek IMEI dengan *#06#.")
                report = ImeiReport(true, "Restore IMEI selesai (${written.size} partisi)", lines)
                log.add("FlashTool", "Restore IMEI", "OK: ${written.joinToString()}")
            } catch (t: Throwable) {
                val lines = ArrayList<String>()
                lines.add(t.message ?: t.toString())
                if (written.isNotEmpty()) lines.add("Sudah berhasil ditulis & terverifikasi: ${written.joinToString()}")
                if (snapName != null) lines.add("Snapshot kondisi sebelum restore tersimpan ($snapName). Gunakan tombol \"Pulihkan Snapshot Terakhir\" untuk mengembalikan.")
                lines.add("Jangan matikan/reboot device sebelum menyelesaikan atau mengembalikan.")
                report = ImeiReport(false, "Restore IMEI gagal / tidak terverifikasi", lines)
                log.add("FlashTool", "Restore IMEI", "ERROR: ${t.message}")
            } finally {
                host.releaseIo()
            }
            end(report)
        }
    }

    private fun pruneSnapshots(keep: Int) {
        val all = snapDir.listFiles { f -> f.name.endsWith(".zip") }?.sortedByDescending { it.lastModified() } ?: return
        all.drop(keep).forEach { it.delete() }
    }
}
