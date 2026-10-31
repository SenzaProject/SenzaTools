package com.senzaproject.senzaai.platform.root

import android.content.Context
import android.util.Base64
import com.senzaproject.senzaai.data.OperationLog
import com.senzaproject.senzaai.platform.AccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Editor file teks bertingkat root (build.prop, XML, init.rc, dll.).
 *
 * Alur: Edit -> Simpan (disimpan di penyimpanan privat aplikasi, belum menyentuh sistem)
 *       -> Terapkan (backup, remount rw bila perlu, tulis, verifikasi, lalu reboot).
 * Reboot hanya dilakukan jika SEMUA perubahan berhasil ditulis dan diverifikasi.
 *
 * Mode terapkan: DIRECT (tulis langsung, remount rw), OVERLAY (module systemless Magisk/KernelSU/APatch
 * di /data/adb/modules/senza_edit, untuk partisi erofs/squashfs yang hanya-baca), AUTO (langsung, lalu overlay bila gagal/erofs).
 */
class RootEditor(private val ctx: Context, private val access: AccessManager, private val log: OperationLog) {

    data class Pending(val path: String, val content: String)
    enum class ApplyMode { AUTO, DIRECT, OVERLAY }
    data class ApplyResult(
        val applied: List<String>,
        val failed: List<Pair<String, String>>,
        val rebooting: Boolean,
        val overlaid: List<String> = emptyList()
    )
    data class PartInfo(val path: String, val fs: String, val readOnly: Boolean, val mountPoint: String, val overlayOk: Boolean)
    private data class MountInfo(val dev: String, val mp: String, val fs: String, val opts: List<String>)

    companion object {
        const val MAX_BYTES = 200_000L
        private val EXT = setOf("prop", "xml", "rc", "conf", "cfg", "txt", "json", "ini", "sh", "properties", "list", "cil")
        private const val MODULE_DIR = "/data/adb/modules/senza_edit"
        private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

        /** Path relatif di dalam module (system/...) untuk file partisi yang bisa di-overlay, atau null. */
        fun overlayRel(path: String): String? = when {
            path.startsWith("/system/") -> path.removePrefix("/")
            listOf("/vendor/", "/product/", "/system_ext/", "/odm/").any { path.startsWith(it) } -> "system$path"
            else -> null
        }

        private fun relToPath(rel: String): String? {
            val r = rel.removePrefix("system/")
            if (rel == r) return null
            return if (listOf("vendor/", "product/", "system_ext/", "odm/").any { r.startsWith(it) }) "/$r" else "/system/$r"
        }

        fun isEditable(path: String): Boolean {
            val p = try {
                PathGuard.validate(path)
            } catch (_: IllegalArgumentException) {
                return false
            }
            val n = p.substringAfterLast('/').lowercase()
            return n.substringAfterLast('.', "") in EXT
        }
    }

    private val dir = File(ctx.filesDir, "pending_edits")
    private val _pending = MutableStateFlow(loadPending())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()

    private fun loadPending(): List<Pending> = try {
        dir.listFiles()?.mapNotNull { f ->
            try {
                Pending(String(Base64.decode(f.name, B64)), f.readText())
            } catch (_: Throwable) {
                null
            }
        }?.sortedBy { it.path } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun fileFor(path: String) = File(dir, Base64.encodeToString(path.toByteArray(), B64))

    fun pendingFor(path: String): String? = _pending.value.firstOrNull { it.path == path }?.content

    @Synchronized
    fun stage(path: String, content: String) {
        val p = PathGuard.validate(path)
        dir.mkdirs()
        fileFor(p).writeText(content)
        _pending.value = loadPending()
        log.add("NORMAL", "simpan perubahan tertunda: $p", "ok (belum diterapkan)")
    }

    @Synchronized
    fun discard(path: String) {
        fileFor(path).delete()
        _pending.value = loadPending()
        log.add("NORMAL", "buang perubahan tertunda: $path", "ok")
    }

    /** Membaca isi penuh file untuk diedit. Menolak file besar/biner agar tidak merusak file. */
    suspend fun read(path: String, asRoot: Boolean): Result<String> {
        val p = try {
            PathGuard.validate(path)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        if (!isEditable(p)) return Result.failure(IllegalArgumentException("Jenis file ini tidak dapat diedit (hanya teks: prop, xml, rc, conf, json, dll.)."))
        val size = access.run(SafeAction.SizeOf(p), asRoot).output.trim().toLongOrNull()
            ?: return Result.failure(IllegalArgumentException("Ukuran file tidak terbaca (izin ditolak?)."))
        if (size > MAX_BYTES) return Result.failure(IllegalArgumentException("File terlalu besar untuk diedit di sini (maks ${MAX_BYTES / 1000} KB)."))
        val r = access.run(SafeAction.ReadFileFull(p), asRoot)
        if (!r.ok) return Result.failure(IllegalArgumentException("Tidak dapat membaca file (izin ditolak?)."))
        if (r.output.contains('\u0000')) return Result.failure(IllegalArgumentException("File biner tidak dapat diedit."))
        return Result.success(r.output)
    }

    private fun parseMounts(text: String): List<MountInfo> = text.lineSequence().mapNotNull { l ->
        val t = l.trim().split(Regex("\\s+"))
        if (t.size < 4) null else MountInfo(t[0], t[1], t[2], t[3].split(','))
    }.toList()

    /** Terapkan semua perubahan tertunda. Reboot otomatis hanya jika semuanya sukses. */
    suspend fun apply(mode: ApplyMode = ApplyMode.AUTO): ApplyResult = withContext(Dispatchers.IO) {
        val list = _pending.value
        if (list.isEmpty()) return@withContext ApplyResult(emptyList(), emptyList(), false)
        if (!access.isRootVerified) {
            return@withContext ApplyResult(emptyList(), list.map { it.path to "Akses root belum diverifikasi." }, false)
        }
        val mounts = parseMounts(access.runRootEdit("cat /proc/mounts", "baca mount").output)
        val tmpDir = File(ctx.cacheDir, "apply").apply { deleteRecursively(); mkdirs() }
        val backupDir = File(ctx.filesDir, "backups").apply { mkdirs() }
        val applied = mutableListOf<String>()
        val overlaid = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        list.forEachIndexed { i, p ->
            val err = try {
                applyOne(p, i, mounts, tmpDir, backupDir, mode, overlaid)
            } catch (e: Throwable) {
                e.message ?: e.javaClass.simpleName
            }
            if (err == null) applied += p.path else failed += p.path to err
            log.add("ROOT", "terapkan perubahan: ${p.path}", err ?: "berhasil")
        }
        applied.forEach { fileFor(it).delete() }
        synchronized(this@RootEditor) { _pending.value = loadPending() }
        tmpDir.deleteRecursively()

        val reboot = failed.isEmpty() && applied.isNotEmpty()
        if (reboot) {
            log.add("ROOT", "reboot untuk menerapkan perubahan", "dijalankan")
            access.runRootEdit("sync; svc power reboot || reboot", "reboot", 10_000)
        }
        ApplyResult(applied, failed, reboot, overlaid)
    }

    /** @return null jika sukses, atau pesan kesalahan. */
    private suspend fun applyOne(
        p: Pending, idx: Int, mounts: List<MountInfo>, tmpDir: File, backupDir: File,
        mode: ApplyMode, overlaid: MutableList<String>
    ): String? {
        val path = PathGuard.validate(p.path)
        // Ikuti symlink (mis. /system/tr_product -> /tr_product) agar mount point & overlay memakai path sebenarnya.
        val real: String = (try {
            access.runRootEdit("readlink -f -- '$path'", "resolve path").output.trim().takeIf { it.startsWith("/") }?.let { PathGuard.validate(it) }
        } catch (_: IllegalArgumentException) {
            null
        }) ?: path
        val orig = access.runRootEdit("cat -- '$path'", "baca file asli")
        if (!orig.ok) return "Tidak dapat membaca file asli."
        val backup = File(backupDir, path.replace(Regex("[^A-Za-z0-9._-]"), "_") + "." + System.currentTimeMillis() + ".bak")
        backup.writeText(orig.output)

        val tmp = File(tmpDir, "n$idx.tmp").apply { writeText(p.content) }
        val mt = mounts.filter { it.mp == "/" || real.startsWith(it.mp.trimEnd('/') + "/") }.maxByOrNull { it.mp.length }
        val roMount = mt != null && mt.opts.contains("ro")
        val permanentRo = mt != null && (mt.fs == "erofs" || mt.fs == "squashfs")

        if (mode == ApplyMode.OVERLAY || (mode == ApplyMode.AUTO && permanentRo)) {
            return writeOverlay(real, tmp, tmpDir, overlaid)
        }

        var remounted = false
        if (roMount) {
            if (permanentRo) {
                return "Partisi ${mt!!.mp} bertipe ${mt.fs} (hanya-baca permanen). Pilih mode Otomatis atau Overlay Magisk."
            }
            var r = access.runRootEdit("mount -o rw,remount '${mt!!.mp}'", "remount rw")
            if (!r.ok) r = access.runRootEdit("mount -o rw,remount '${mt.dev}' '${mt.mp}'", "remount rw (dev)")
            if (!r.ok) {
                if (mode == ApplyMode.AUTO) return writeOverlay(real, tmp, tmpDir, overlaid)
                return "Gagal remount rw ${mt.mp}: ${r.output.trim().take(120)}"
            }
            remounted = true
        }

        val w = access.runRootEdit(
            "cat '${tmp.absolutePath}' > '$path' && sync && cmp -s '${tmp.absolutePath}' '$path'",
            "tulis file"
        )
        var err: String? = null
        if (!w.ok) {
            access.runRootEdit("cat '${backup.absolutePath}' > '$path'", "pulihkan backup")
            err = "Gagal menulis/verifikasi (file dikembalikan dari backup): ${w.output.trim().take(120)}"
        }
        if (remounted) access.runRootEdit("mount -o ro,remount '${mt!!.mp}'", "remount ro")
        return err
    }

    /** Tulis file ke module systemless; berlaku setelah reboot tanpa menyentuh partisi asli. */
    private suspend fun writeOverlay(path: String, tmp: File, tmpDir: File, overlaid: MutableList<String>): String? {
        val rel = overlayRel(path) ?: return "Overlay Magisk hanya mendukung /system (termasuk /system/tr_*), /vendor, /product, /system_ext, /odm. " +
            "Partisi top-level seperti $path tidak bisa di-overlay; gunakan mode Langsung (bila partisi rw) atau pindahkan partisinya ke /system/tr_x."
        if (!access.runRootEdit("test -d /data/adb/modules", "cek folder module").ok) {
            return "Folder /data/adb/modules tidak ada: butuh Magisk/KernelSU/APatch untuk mode overlay."
        }
        val dest = "$MODULE_DIR/$rel"
        val propTmp = File(tmpDir, "module.prop").apply {
            writeText("id=senza_edit\nname=Senza Tools Edit Overlay\nversion=1.0\nversionCode=1\nauthor=SenzaProject\ndescription=File hasil edit dari Senza Tools (overlay systemless)\n")
        }
        val cmd = "mkdir -p '${dest.substringBeforeLast('/')}' && " +
            "([ -f '$MODULE_DIR/module.prop' ] || cat '${propTmp.absolutePath}' > '$MODULE_DIR/module.prop') && " +
            "rm -f '$MODULE_DIR/disable' '$MODULE_DIR/remove' && " +
            "cat '${tmp.absolutePath}' > '$dest' && chmod 644 '$dest' && " +
            "(chcon --reference='$path' '$dest' 2>/dev/null; true) && sync && cmp -s '${tmp.absolutePath}' '$dest'"
        val r = access.runRootEdit(cmd, "tulis overlay")
        if (!r.ok) return "Gagal membuat overlay: ${r.output.trim().take(120)}"
        overlaid += path
        return null
    }

    /** Daftar file yang saat ini di-overlay oleh Senza Tools (butuh root terverifikasi). */
    suspend fun overlays(): List<String> {
        val r = access.runRootEdit("find '$MODULE_DIR/system' -type f 2>/dev/null", "daftar overlay")
        if (!r.ok) return emptyList()
        return r.output.lines().map { it.trim() }.filter { it.startsWith("$MODULE_DIR/") }
            .mapNotNull { relToPath(it.removePrefix("$MODULE_DIR/")) }.sorted()
    }

    /** Hapus overlay satu file; berlaku setelah reboot. */
    suspend fun removeOverlay(path: String): Boolean {
        val p = PathGuard.validate(path)
        val rel = overlayRel(p) ?: return false
        return access.runRootEdit("rm -f '$MODULE_DIR/$rel'", "hapus overlay").ok
    }

    /** Pindai partisi umum + Transsion (tr_*), baik top-level (/tr_product) maupun di dalam system (/system/tr_product). */
    suspend fun listPartitions(asRoot: Boolean): List<PartInfo> {
        val base = listOf("system", "product", "system_ext", "vendor", "odm", "vendor_dlkm", "odm_dlkm", "system_dlkm")
        val tr = listOf("tr_product", "tr_region", "tr_manifest", "tr_system", "tr_overlayfs", "tr_preload", "tr_company", "tr_carrier")
        val mountsText = access.run(SafeAction.Mounts, asRoot).output
        val mounts = parseMounts(mountsText)
        val dynamicTr = mounts.map { it.mp }.filter { Regex("^/(system/)?tr_[A-Za-z0-9_]+$").matches(it) }
        val candidates = (base.map { "/$it" } + tr.map { "/$it" } + tr.map { "/system/$it" } +
            base.drop(1).map { "/system/$it" } + dynamicTr).distinct()
        val found = access.existingDirs(candidates, asRoot)
        return found.map { path ->
            val mt = mounts.filter { it.mp == "/" || path == it.mp || path.startsWith(it.mp.trimEnd('/') + "/") }.maxByOrNull { it.mp.length }
            PartInfo(
                path = path,
                fs = mt?.fs ?: "?",
                readOnly = mt?.opts?.contains("ro") == true,
                mountPoint = mt?.mp ?: "?",
                overlayOk = overlayRel("$path/x") != null
            )
        }
    }
}
