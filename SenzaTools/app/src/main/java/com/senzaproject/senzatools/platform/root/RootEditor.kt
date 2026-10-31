package com.senzaproject.senzatools.platform.root

import android.content.Context
import android.util.Base64
import com.senzaproject.senzatools.data.OperationLog
import com.senzaproject.senzatools.platform.AccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Editor file teks bertingkat root (build.prop, XML, init.rc, dll.).
 *
 * Alur: Edit -> Simpan (disimpan di penyimpanan privat aplikasi, belum menyentuh sistem)
 *       -> Terapkan (backup, tulis, verifikasi, lalu restart).
 * Restart otomatis hanya dilakukan jika SEMUA perubahan berhasil ditulis dan diverifikasi.
 *
 * Mode terapkan: DIRECT (tulis langsung, remount rw), OVERLAY (file ditaruh di module systemless
 * /data/adb/modules/senza_edit), AUTO (langsung; bila partisi erofs/squashfs/hanya-baca, otomatis overlay).
 *
 * Partisi erofs/squashfs tidak bisa ditulis sama sekali, jadi "terapkan" berarti:
 *  1. file hasil edit disimpan di module (persisten; diterapkan Magisk saat boot untuk /system, /vendor, /product,
 *     /system_ext, /odm; untuk partisi lain seperti /tr_product lewat post-fs-data.sh milik module ini), lalu
 *  2. file itu langsung di-bind-mount ke path aslinya supaya perubahan terlihat SEKARANG (cat di Termux), dan
 *  3. isi path asli dibandingkan dengan hasil edit (cmp) sebelum dilaporkan "aktif".
 * Dulu hanya langkah 1 yang dilakukan, jadi di aplikasi sudah terlihat berubah tetapi partisi aslinya belum.
 */
class RootEditor(private val ctx: Context, private val access: AccessManager, private val log: OperationLog) {

    data class Pending(val path: String, val content: String)
    enum class ApplyMode { AUTO, DIRECT, OVERLAY }
    data class ApplyResult(
        val applied: List<String>,
        val failed: List<Pair<String, String>>,
        val rebooting: Boolean,
        val overlaid: List<String> = emptyList(),
        /** Path yang SUDAH terlihat berubah di partisi asli saat ini (diverifikasi dengan cmp). */
        val live: List<String> = emptyList(),
        /** Terisi bila perintah restart sudah dikirim tetapi perangkat belum restart. */
        val rebootError: String? = null
    )

    data class PartInfo(
        val path: String,
        val fs: String,
        val readOnly: Boolean,
        val mountPoint: String,
        val overlayOk: Boolean,
        /** Path lain yang menunjuk ke partisi yang sama (symlink), mis. /system/tr_product -> /tr_product. */
        val aliases: List<String> = emptyList()
    )

    private data class MountInfo(val dev: String, val mp: String, val fs: String, val opts: List<String>)

    companion object {
        const val MAX_BYTES = 200_000L
        private const val REBOOT_WAIT_MS = 15_000L
        private val EXT = setOf("prop", "xml", "rc", "conf", "cfg", "txt", "json", "ini", "sh", "properties", "list", "cil")
        private const val MODULE_DIR = "/data/adb/modules/senza_edit"
        private const val BIND_DIR = "senza_bind"
        private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

        private val POST_FS_DATA = """#!/system/bin/sh
# Dibuat oleh Senza Tools: pasang ulang hasil edit file di partisi yang tidak bisa di-overlay Magisk (mis. /tr_product).
MODDIR=${'$'}{0%/*}
L="${'$'}MODDIR/senza_bind/binds.list"
[ -f "${'$'}L" ] || exit 0
while IFS='|' read -r src dst; do
  [ -f "${'$'}src" ] && [ -f "${'$'}dst" ] || continue
  chcon --reference="${'$'}dst" "${'$'}src" 2>/dev/null
  mount --bind "${'$'}src" "${'$'}dst" 2>/dev/null
done < "${'$'}L"
exit 0
"""

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

        /** Lokasi file hasil edit di dalam module: system/... bila bisa di-overlay, selain itu senza_bind/<nama>. */
        private fun storePath(path: String): String {
            val rel = overlayRel(path)
            if (rel != null) return "$MODULE_DIR/$rel"
            val name = path.trim('/').replace('/', '_').replace(Regex("[^A-Za-z0-9._-]"), "_") +
                "." + Integer.toHexString(path.hashCode())
            return "$MODULE_DIR/$BIND_DIR/$name"
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

    /**
     * Terapkan semua perubahan tertunda. Restart otomatis hanya jika semuanya sukses.
     * [onProgress] dipanggil dari thread latar dengan teks status untuk ditampilkan di UI.
     */
    suspend fun apply(mode: ApplyMode = ApplyMode.AUTO, onProgress: (String) -> Unit = {}): ApplyResult = withContext(Dispatchers.IO) {
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
        val live = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        list.forEachIndexed { i, p ->
            onProgress("Menerapkan ${i + 1}/${list.size}: ${p.path}")
            val err = try {
                applyOne(p, i, mounts, tmpDir, backupDir, mode, overlaid, live)
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
        var rebootError: String? = null
        if (reboot) {
            onProgress("Perubahan tertulis. Mengirim perintah restart…")
            log.add("ROOT", "restart untuk menerapkan perubahan", "dijalankan")
            val r = access.rebootDevice()
            // Bila perangkat benar-benar restart, proses ini sudah mati sebelum baris-baris berikut dijalankan.
            delay(REBOOT_WAIT_MS)
            rebootError = "Perintah restart sudah dikirim${if (r.ok) "" else " (su keluar dengan kode ${r.exitCode})"}, " +
                "tetapi perangkat belum restart. Tekan Restart sekarang untuk mencoba lagi, atau restart manual lewat tombol power."
            log.add("ROOT", "restart tidak terjadi dalam ${REBOOT_WAIT_MS / 1000} detik", "perlu restart manual")
        }
        ApplyResult(applied, failed, reboot, overlaid, live, rebootError)
    }

    /** @return null jika sukses, atau pesan kesalahan. */
    private suspend fun applyOne(
        p: Pending, idx: Int, mounts: List<MountInfo>, tmpDir: File, backupDir: File,
        mode: ApplyMode, overlaid: MutableList<String>, live: MutableList<String>
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
            return writeOverlay(real, tmp, tmpDir, overlaid, live)
        }

        var remounted = false
        if (roMount) {
            if (permanentRo) {
                return "Partisi ${mt!!.mp} bertipe ${mt.fs} (hanya-baca permanen, tidak bisa ditulis atau di-remount rw). " +
                    "Pilih mode Otomatis atau Overlay Magisk."
            }
            var r = access.runRootEdit("mount -o rw,remount '${mt!!.mp}'", "remount rw")
            if (!r.ok) r = access.runRootEdit("mount -o rw,remount '${mt.dev}' '${mt.mp}'", "remount rw (dev)")
            if (!r.ok) {
                if (mode == ApplyMode.AUTO) return writeOverlay(real, tmp, tmpDir, overlaid, live)
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
        } else {
            live += real
        }
        if (remounted) access.runRootEdit("mount -o ro,remount '${mt!!.mp}'", "remount ro")
        return err
    }

    /**
     * Simpan file hasil edit di module systemless, lalu bind-mount ke path aslinya supaya langsung terlihat.
     * Persisten: file di system/... dipasang Magisk saat boot; sisanya lewat post-fs-data.sh module ini.
     */
    private suspend fun writeOverlay(
        path: String, tmp: File, tmpDir: File, overlaid: MutableList<String>, live: MutableList<String>
    ): String? {
        if (!access.runRootEdit("test -d /data/adb/modules", "cek folder module").ok) {
            return "Folder /data/adb/modules tidak ada: butuh Magisk/KernelSU/APatch untuk mode overlay."
        }
        val rel = overlayRel(path)
        val store = storePath(path)
        val propTmp = File(tmpDir, "module.prop").apply {
            writeText("id=senza_edit\nname=Senza Tools Edit Overlay\nversion=1.0\nversionCode=1\nauthor=SenzaProject\ndescription=File hasil edit dari Senza Tools (overlay systemless)\n")
        }
        val sb = StringBuilder()
        sb.append("mkdir -p '${store.substringBeforeLast('/')}' && ")
        sb.append("([ -f '$MODULE_DIR/module.prop' ] || cat '${propTmp.absolutePath}' > '$MODULE_DIR/module.prop') && ")
        sb.append("rm -f '$MODULE_DIR/disable' '$MODULE_DIR/remove' && ")
        sb.append("cat '${tmp.absolutePath}' > '$store' && chmod 644 '$store' && ")
        sb.append("(chcon --reference='$path' '$store' 2>/dev/null; true) && sync && cmp -s '${tmp.absolutePath}' '$store'")
        val r = access.runRootEdit(sb.toString(), "tulis overlay")
        if (!r.ok) return "Gagal membuat overlay: ${r.output.trim().take(120)}"

        if (rel == null) {
            // Partisi yang tidak dikenali Magisk (mis. /tr_product): daftarkan untuk dipasang ulang tiap boot.
            val script = File(tmpDir, "post-fs-data.sh").apply { writeText(POST_FS_DATA) }
            val list = "$MODULE_DIR/$BIND_DIR/binds.list"
            val reg = access.runRootEdit(
                "touch '$list' && (grep -vxF '$store|$path' '$list' > '$list.new'; echo '$store|$path' >> '$list.new'; " +
                    "cat '$list.new' > '$list'; rm -f '$list.new') && " +
                    "cat '${script.absolutePath}' > '$MODULE_DIR/post-fs-data.sh' && chmod 755 '$MODULE_DIR/post-fs-data.sh'",
                "daftarkan bind-mount boot"
            )
            if (!reg.ok) return "Gagal mendaftarkan file untuk boot: ${reg.output.trim().take(120)}"
        }
        overlaid += path

        // Aktifkan sekarang: lepas bind lama (bila ada), pasang yang baru, lalu bandingkan isi path asli.
        val bind = access.runRootEdit(
            "i=0; while [ \$i -lt 8 ] && grep -qF ' $path ' /proc/self/mounts; do umount '$path' 2>/dev/null || break; i=\$((i+1)); done; " +
                "mount --bind '$store' '$path' && cmp -s '${tmp.absolutePath}' '$path'",
            "bind-mount langsung"
        )
        if (bind.ok) live += path
        return null
    }

    /** Daftar file yang saat ini di-overlay oleh Senza Tools (butuh root terverifikasi). */
    suspend fun overlays(): List<String> {
        val r = access.runRootEdit(
            "find '$MODULE_DIR/system' -type f 2>/dev/null; echo '##BINDS'; cat '$MODULE_DIR/$BIND_DIR/binds.list' 2>/dev/null",
            "daftar overlay"
        )
        if (!r.ok && r.output.isBlank()) return emptyList()
        val lines = r.output.lines().map { it.trim() }
        val sep = lines.indexOf("##BINDS").let { if (it < 0) lines.size else it }
        val sys = lines.take(sep).filter { it.startsWith("$MODULE_DIR/") }
            .mapNotNull { relToPath(it.removePrefix("$MODULE_DIR/")) }
        val bound = lines.drop(sep + 1).mapNotNull { it.substringAfter('|', "").takeIf { d -> d.startsWith("/") } }
        return (sys + bound).distinct().sorted()
    }

    /** Hapus overlay satu file: lepas bind-mount sekarang dan hapus dari module (file asli kembali langsung). */
    suspend fun removeOverlay(path: String): Boolean {
        val p = PathGuard.validate(path)
        val store = storePath(p)
        val list = "$MODULE_DIR/$BIND_DIR/binds.list"
        val unbind = "i=0; while [ \$i -lt 8 ] && grep -qF ' $p ' /proc/self/mounts; do umount '$p' 2>/dev/null || break; i=\$((i+1)); done"
        val dropLine = "[ -f '$list' ] && (grep -vxF '$store|$p' '$list' > '$list.new'; cat '$list.new' > '$list'; rm -f '$list.new')"
        return access.runRootEdit("rm -f '$store'; $dropLine; $unbind; true", "hapus overlay").ok
    }

    /**
     * Pindai partisi umum + Transsion (tr_*), baik top-level (/tr_product) maupun di dalam system (/system/tr_product).
     * Path yang hanya symlink ke partisi lain digabung (satu partisi = satu baris), jadi /tr_product dan
     * /system/tr_product tidak lagi dihitung sebagai dua partisi.
     */
    suspend fun listPartitions(asRoot: Boolean): List<PartInfo> {
        val base = listOf("system", "product", "system_ext", "vendor", "odm", "vendor_dlkm", "odm_dlkm", "system_dlkm")
        val tr = listOf("tr_product", "tr_region", "tr_manifest", "tr_system", "tr_overlayfs", "tr_preload", "tr_company", "tr_carrier")
        val mountsText = access.run(SafeAction.Mounts, asRoot).output
        val mounts = parseMounts(mountsText)
        val dynamicTr = mounts.map { it.mp }.filter { Regex("^/(system/)?tr_[A-Za-z0-9_]+$").matches(it) }
        val candidates = (base.map { "/$it" } + tr.map { "/$it" } + tr.map { "/system/$it" } +
            base.drop(1).map { "/system/$it" } + dynamicTr).distinct()
        val found = access.existingDirs(candidates, asRoot)
        if (found.isEmpty()) return emptyList()

        // Resolusi symlink: "path|path-sebenarnya" per baris.
        val resolveCmd = "for p in " + found.joinToString(" ") { "'${PathGuard.validate(it)}'" } +
            "; do echo \"\$p|\$(readlink -f \"\$p\" 2>/dev/null)\"; done"
        val resolved = access.run(SafeAction.ReadScript(resolveCmd, "Resolusi path partisi"), asRoot).output.lines()
            .mapNotNull { l ->
                val i = l.indexOf('|')
                if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim().takeIf { it.startsWith("/") }
            }.toMap()

        val groups = linkedMapOf<String, MutableList<String>>()
        found.forEach { p ->
            val real = resolved[p] ?: p
            groups.getOrPut(real) { mutableListOf() }.add(p)
        }
        return groups.map { (real, paths) ->
            val mt = mounts.filter { it.mp == "/" || real == it.mp || real.startsWith(it.mp.trimEnd('/') + "/") }.maxByOrNull { it.mp.length }
            PartInfo(
                path = real,
                fs = mt?.fs ?: "?",
                readOnly = mt?.opts?.contains("ro") == true,
                mountPoint = mt?.mp ?: "?",
                overlayOk = overlayRel("$real/x") != null,
                aliases = paths.filter { it != real }
            )
        }
    }
}
