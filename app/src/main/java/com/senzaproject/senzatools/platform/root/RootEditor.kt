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
 *       -> Terapkan (backup, tulis, verifikasi, lalu restart bila diminta).
 *
 * Metode terapkan (tidak lagi bergantung pada Magisk):
 *  - DIRECT : tulis langsung ke file asli (remount rw bila perlu). Hanya untuk partisi yang bisa ditulis (ext4/f2fs).
 *  - BIND   : file hasil edit disimpan di /data/adb/senza_edit, lalu di-bind-mount ke path aslinya di namespace init.
 *             Bekerja di partisi APA PUN (erofs, squashfs, hanya-baca, top-level seperti /tr_product) dan dengan
 *             root manager APA PUN. Persisten lewat /data/adb/post-fs-data.d + service.d (bila ada) dan penerima
 *             BOOT_COMPLETED milik aplikasi ([BootReceiver]) sebagai cadangan.
 *  - OVERLAY: module systemless Magisk/KernelSU/APatch (/data/adb/modules/senza_edit); hanya path yang dikenali
 *             manager (/system, /vendor, /product, /system_ext, /odm).
 *  - AUTO   : coba DIRECT bila partisi bisa ditulis, lalu BIND, lalu OVERLAY; berhenti di yang pertama berhasil.
 *
 * File *.prop (mis. build.prop) dibaca init SEBELUM mount boot mana pun, jadi bind-mount saja tidak cukup untuk
 * properti. Karena itu nilai yang berubah juga ditulis ke daftar properti dan dipasang dengan resetprop, baik
 * sekarang (langsung terlihat di getprop) maupun tiap boot.
 */
class RootEditor(private val ctx: Context, private val access: AccessManager, private val log: OperationLog) {

    data class Pending(val path: String, val content: String)
    enum class ApplyMode { AUTO, DIRECT, BIND, OVERLAY }
    data class ApplyResult(
        val applied: List<String>,
        val failed: List<Pair<String, String>>,
        val rebooting: Boolean,
        val overlaid: List<String> = emptyList(),
        /** Path yang SUDAH terlihat berubah di partisi asli saat ini (diverifikasi dengan cmp). */
        val live: List<String> = emptyList(),
        /** Terisi bila perintah restart sudah dikirim tetapi perangkat belum restart. */
        val rebootError: String? = null,
        /** Metode yang dipakai per file ("langsung", "bind-mount", "module overlay"). */
        val methods: Map<String, String> = emptyMap(),
        /** Jumlah properti yang dipasang lewat resetprop/setprop. */
        val propsSet: Int = 0,
        val notes: List<String> = emptyList()
    )

    data class PartInfo(
        val path: String,
        val fs: String,
        val readOnly: Boolean,
        val mountPoint: String,
        val overlayOk: Boolean,
        /** Path lain yang menunjuk ke partisi yang sama (symlink), mis. /system/tr_product -> /tr_product. */
        val aliases: List<String> = emptyList(),
        /** Metode terapkan yang cocok untuk partisi ini. */
        val suggest: String = ""
    )

    private data class MountInfo(val dev: String, val mp: String, val fs: String, val opts: List<String>)

    private class RunState(
        val mounts: List<MountInfo>,
        val tmpDir: File,
        val backupDir: File,
        val base: String,
        val hasResetprop: Boolean,
        val overlaid: MutableList<String>,
        val live: MutableList<String>,
        val methods: MutableMap<String, String>,
        val notes: MutableList<String>
    ) {
        var infraDone = false
        var propsSet = 0
    }

    companion object {
        const val MAX_BYTES = 200_000L
        private const val REBOOT_WAIT_MS = 15_000L
        private val EXT = setOf(
            "prop", "xml", "rc", "conf", "cfg", "txt", "json", "ini", "sh", "properties", "list", "cil",
            "policy", "yaml", "yml", "mk", "csv", "vintf"
        )
        private val EXACT_NAMES = setOf("build.prop", "default.prop", "local.prop", "prop.default", "cpu_info")
        private const val MODULE_DIR = "/data/adb/modules/senza_edit"
        private const val LEGACY_BIND_DIR = "senza_bind"
        private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        private val SAFE_TOKEN = Regex("^[A-Za-z0-9_./@:+,=-]+$")
        private val PROP_KEY = Regex("^[A-Za-z0-9_.@:-]+$")

        /** Menetapkan $NS = awalan nsenter ke namespace mount init (kosong bila tidak tersedia). */
        private const val NS_INIT =
            "NS=''; if command -v nsenter >/dev/null 2>&1 && nsenter --mount=/proc/1/ns/mnt -- true >/dev/null 2>&1; " +
                "then NS='nsenter --mount=/proc/1/ns/mnt --'; fi; "

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

        /** Nama berkas unik per path (dipakai untuk store/orig/props). */
        private fun keyName(path: String): String =
            path.trim('/').replace('/', '_').replace(Regex("[^A-Za-z0-9._-]"), "_") + "." + Integer.toHexString(path.hashCode())

        /** Lokasi file hasil edit di module (mode OVERLAY). */
        private fun moduleStore(path: String): String? = overlayRel(path)?.let { "$MODULE_DIR/$it" }

        /** Lokasi file hasil edit versi lama (module/senza_bind) — hanya untuk membersihkan instalasi lama. */
        private fun legacyStore(path: String): String = "$MODULE_DIR/$LEGACY_BIND_DIR/${keyName(path)}"

        fun isPropFile(path: String): Boolean {
            val n = path.substringAfterLast('/').lowercase()
            return n.endsWith(".prop") || n == "prop.default"
        }

        fun isEditable(path: String): Boolean {
            val p = try {
                PathGuard.validate(path)
            } catch (_: IllegalArgumentException) {
                return false
            }
            val n = p.substringAfterLast('/').lowercase()
            if (n in EXACT_NAMES || n.startsWith("fstab.") || n.startsWith("init.")) return true
            return n.substringAfterLast('.', "") in EXT
        }

        /** key=value dari isi file prop (komentar dan baris tak valid diabaikan). */
        internal fun parseProps(text: String): Map<String, String> {
            val out = linkedMapOf<String, String>()
            text.lineSequence().forEach { raw ->
                val l = raw.trim()
                if (l.isEmpty() || l.startsWith("#") || !l.contains('=')) return@forEach
                val k = l.substringBefore('=').trim()
                val v = l.substringAfter('=').trim()
                if (PROP_KEY.matches(k)) out[k] = v
            }
            return out
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

    // ------------------------------------------------------------------ Penyimpanan di /data

    @Volatile
    private var baseCache: String? = null

    /** Folder penyimpanan hasil edit: /data/adb/senza_edit (atau /data/local/senza_edit bila /data/adb tidak ada). */
    private suspend fun base(): String {
        baseCache?.let { return it }
        val r = access.runRootEdit(
            "if [ -d /data/adb ]; then echo /data/adb/senza_edit; else echo /data/local/senza_edit; fi",
            "pilih folder penyimpanan"
        )
        val b = r.output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("/data/") } ?: "/data/adb/senza_edit"
        baseCache = b
        return b
    }

    /** Pasang skrip penerap + pengait boot (post-fs-data.d/service.d). Sekali per proses terapkan. */
    private suspend fun ensureInfra(st: RunState): String? {
        if (st.infraDone) return null
        val script = File(st.tmpDir, "senza_apply.sh")
        try {
            ctx.assets.open("senza_apply.sh").use { i -> script.outputStream().use { o -> i.copyTo(o) } }
        } catch (_: Throwable) {
            return "Skrip penerap tidak ditemukan di dalam aplikasi."
        }
        val wrapper = File(st.tmpDir, "wrapper.sh").apply { writeText("#!/system/bin/sh\nsh '${st.base}/senza_apply.sh'\n") }
        val cmd = "mkdir -p '${st.base}/store' '${st.base}/orig' '${st.base}/props.d' && chmod 700 '${st.base}' && " +
            "cat '${script.absolutePath}' > '${st.base}/senza_apply.sh' && chmod 755 '${st.base}/senza_apply.sh' && " +
            "(if [ -d /data/adb ]; then for d in post-fs-data.d service.d; do " +
            "mkdir -p /data/adb/\$d && cat '${wrapper.absolutePath}' > /data/adb/\$d/senza_edit.sh && chmod 755 /data/adb/\$d/senza_edit.sh; " +
            "done; fi; true)"
        val r = access.runRootEdit(cmd, "pasang skrip boot")
        if (!r.ok) return "Gagal menyiapkan ${st.base}: ${r.output.trim().take(120)}"
        st.infraDone = true
        return null
    }

    private suspend fun detectResetprop(): Boolean = access.runRootEdit(
        "for c in resetprop /data/adb/magisk/resetprop /data/adb/ksu/bin/resetprop /data/adb/ap/bin/resetprop; do " +
            "command -v \"\$c\" >/dev/null 2>&1 && exit 0; done; exit 1",
        "cek resetprop"
    ).ok

    private suspend fun isBound(base: String, real: String): Boolean {
        val store = "$base/store/${keyName(real)}"
        return access.runRootEdit("grep -qxF '$store|$real' '$base/binds.list'", "cek bind terpasang").ok
    }

    /**
     * Terapkan semua perubahan tertunda. Restart otomatis hanya jika [reboot] true dan semuanya sukses.
     * [onProgress] dipanggil dari thread latar dengan teks status untuk ditampilkan di UI.
     */
    suspend fun apply(
        mode: ApplyMode = ApplyMode.AUTO,
        reboot: Boolean = true,
        onProgress: (String) -> Unit = {}
    ): ApplyResult = withContext(Dispatchers.IO) {
        val list = _pending.value
        if (list.isEmpty()) return@withContext ApplyResult(emptyList(), emptyList(), false)
        if (!access.isRootVerified) {
            return@withContext ApplyResult(emptyList(), list.map { it.path to "Akses root belum diverifikasi." }, false)
        }
        val mounts = parseMounts(access.runRootEdit("cat /proc/mounts", "baca mount").output)
        val tmpDir = File(ctx.cacheDir, "apply").apply { deleteRecursively(); mkdirs() }
        val backupDir = File(ctx.filesDir, "backups").apply { mkdirs() }
        val st = RunState(
            mounts, tmpDir, backupDir, base(), detectResetprop(),
            mutableListOf(), mutableListOf(), linkedMapOf(), mutableListOf()
        )
        val applied = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        list.forEachIndexed { i, p ->
            onProgress("Menerapkan ${i + 1}/${list.size}: ${p.path}")
            val err = try {
                applyOne(p, i, st, mode)
            } catch (e: Throwable) {
                e.message ?: e.javaClass.simpleName
            }
            if (err == null) applied += p.path else failed += p.path to err
            log.add("ROOT", "terapkan perubahan: ${p.path}", err ?: "berhasil (${st.methods[p.path] ?: "-"})")
        }
        applied.forEach { fileFor(it).delete() }
        synchronized(this@RootEditor) { _pending.value = loadPending() }
        tmpDir.deleteRecursively()

        if (st.propsSet > 0 && !st.hasResetprop) {
            st.notes += "resetprop tidak ditemukan: hanya properti non-ro yang bisa dipasang sekarang; properti ro.* baru berlaku bila file asli terbaca init (mode Langsung) atau setelah resetprop tersedia."
        }

        val doReboot = reboot && failed.isEmpty() && applied.isNotEmpty()
        var rebootError: String? = null
        if (doReboot) {
            onProgress("Perubahan tertulis. Mengirim perintah restart…")
            log.add("ROOT", "restart untuk menerapkan perubahan", "dijalankan")
            val r = access.rebootDevice()
            // Bila perangkat benar-benar restart, proses ini sudah mati sebelum baris-baris berikut dijalankan.
            delay(REBOOT_WAIT_MS)
            rebootError = "Perintah restart sudah dikirim${if (r.ok) "" else " (su keluar dengan kode ${r.exitCode})"}, " +
                "tetapi perangkat belum restart. Tekan Restart sekarang untuk mencoba lagi, atau restart manual lewat tombol power."
            log.add("ROOT", "restart tidak terjadi dalam ${REBOOT_WAIT_MS / 1000} detik", "perlu restart manual")
        }
        ApplyResult(applied, failed, doReboot, st.overlaid, st.live, rebootError, st.methods, st.propsSet, st.notes)
    }

    /** @return null jika sukses, atau pesan kesalahan. */
    private suspend fun applyOne(p: Pending, idx: Int, st: RunState, mode: ApplyMode): String? {
        val path = PathGuard.validate(p.path)
        // Ikuti symlink (mis. /system/tr_product -> /tr_product) agar mount point & bind memakai path sebenarnya.
        val real: String = (try {
            access.runRootEdit("readlink -f -- '$path'", "resolve path").output.trim().takeIf { it.startsWith("/") }?.let { PathGuard.validate(it) }
        } catch (_: IllegalArgumentException) {
            null
        }) ?: path
        if (!access.runRootEdit("[ -f '$real' ]", "cek file ada").ok) return "File tidak ada di perangkat (hanya file yang sudah ada yang bisa diganti)."
        val orig = access.runRootEdit("cat -- '$real'", "baca file asli")
        if (!orig.ok) return "Tidak dapat membaca file asli."

        val bound = isBound(st.base, real)
        val origCopy = "${st.base}/orig/${keyName(real)}"
        // Isi asli sebenarnya: bila file sudah di-bind, isi di path sudah hasil edit lama, jadi pakai salinan asli yang disimpan.
        val baseline: String = if (bound) {
            access.runRootEdit("cat -- '$origCopy'", "baca salinan asli").takeIf { it.ok }?.output ?: orig.output
        } else orig.output

        // Backup isi asli (hanya bila belum di-bind; kalau sudah di-bind, isi asli ada di salinan orig/).
        val backupFile: File? = if (!bound) {
            File(st.backupDir, real.replace(Regex("[^A-Za-z0-9._-]"), "_") + "." + System.currentTimeMillis() + ".bak")
                .also { it.writeText(orig.output) }
        } else null

        val tmp = File(st.tmpDir, "n$idx.tmp").apply { writeText(p.content) }
        val origTmp = File(st.tmpDir, "o$idx.tmp").apply { writeText(baseline) }
        val propDiff: Map<String, String>? = if (isPropFile(real)) {
            val before = parseProps(baseline)
            parseProps(p.content).filter { (k, v) -> before[k] != v }
        } else null

        val mt = st.mounts.filter { it.mp == "/" || real.startsWith(it.mp.trimEnd('/') + "/") }.maxByOrNull { it.mp.length }
        val roMount = mt != null && mt.opts.contains("ro")
        val permanentRo = mt != null && (mt.fs == "erofs" || mt.fs == "squashfs")

        return when (mode) {
            ApplyMode.DIRECT -> {
                if (bound) "File ini sedang di-bind. Hapus dulu di kartu \"Hasil edit aktif\" atau pakai mode Bind-mount/Otomatis."
                else direct(path, real, mt, roMount, permanentRo, tmp, backupFile, propDiff, st)
            }
            ApplyMode.BIND -> bind(real, tmp, origTmp, bound, propDiff, st)
            ApplyMode.OVERLAY -> overlay(real, tmp, origTmp, bound, propDiff, st)
            ApplyMode.AUTO -> {
                var directErr: String? = null
                if (!bound && !permanentRo) {
                    directErr = direct(path, real, mt, roMount, permanentRo, tmp, backupFile, propDiff, st)
                    if (directErr == null) return null
                }
                val bindErr = bind(real, tmp, origTmp, bound, propDiff, st)
                if (bindErr == null) return null
                if (overlayRel(real) != null) {
                    val ovErr = overlay(real, tmp, origTmp, bound, propDiff, st)
                    if (ovErr == null) return null
                    return "Semua metode gagal. Langsung: ${directErr ?: "dilewati"}. Bind-mount: $bindErr. Module overlay: $ovErr"
                }
                "Semua metode gagal. Langsung: ${directErr ?: "dilewati (partisi ${mt?.fs ?: "?"} hanya-baca)"}. Bind-mount: $bindErr"
            }
        }
    }

    // ------------------------------------------------------------------ Metode: langsung

    private suspend fun direct(
        path: String, real: String, mt: MountInfo?, roMount: Boolean, permanentRo: Boolean,
        tmp: File, backup: File?, propDiff: Map<String, String>?, st: RunState
    ): String? {
        if (roMount && permanentRo) {
            return "Partisi ${mt!!.mp} bertipe ${mt.fs} (hanya-baca permanen, tidak bisa ditulis atau di-remount rw)."
        }
        var remounted = false
        if (roMount) {
            val m = mt!!
            if (!SAFE_TOKEN.matches(m.mp) || !SAFE_TOKEN.matches(m.dev)) return "Nama mount/perangkat ${m.mp} memuat karakter tak dikenal; tidak di-remount."
            // blockdev --setrw melepas proteksi hanya-baca tingkat perangkat blok (mis. dm-*), sebelum remount.
            val setrw = if (m.dev.startsWith("/dev/block/")) "blockdev --setrw '${m.dev}' >/dev/null 2>&1; " else ""
            var r = access.runRootEdit(NS_INIT + setrw + "\$NS mount -o rw,remount '${m.mp}'", "remount rw")
            if (!r.ok) r = access.runRootEdit(NS_INIT + setrw + "\$NS mount -o rw,remount '${m.dev}' '${m.mp}'", "remount rw (dev)")
            if (!r.ok) return "Gagal remount rw ${m.mp}: ${r.output.trim().take(120)}"
            remounted = true
        }
        val w = access.runRootEdit("cat '${tmp.absolutePath}' > '$path' && sync && cmp -s '${tmp.absolutePath}' '$path'", "tulis file")
        var err: String? = null
        if (!w.ok) {
            if (backup != null) access.runRootEdit("cat '${backup.absolutePath}' > '$path'", "pulihkan backup")
            err = "Gagal menulis/verifikasi (file dikembalikan dari backup): ${w.output.trim().take(120)}"
        } else {
            st.live += real
            st.methods[real] = "langsung"
            if (propDiff != null && propDiff.isNotEmpty()) livePropsOnly(propDiff, st)
        }
        if (remounted) access.runRootEdit(NS_INIT + "\$NS mount -o ro,remount '${mt!!.mp}'", "remount ro")
        return err
    }

    /** Pasang properti sekarang saja (tanpa daftar boot): dipakai mode Langsung karena file aslinya sudah berubah. */
    private suspend fun livePropsOnly(props: Map<String, String>, st: RunState) {
        if (ensureInfra(st) != null) return
        val f = File(st.tmpDir, "live_props.list").apply { writeText(props.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n") }
        val r = access.runRootEdit("sh '${st.base}/senza_apply.sh' --props '${f.absolutePath}'", "pasang properti sekarang")
        if (r.ok) st.propsSet += props.size
    }

    // ------------------------------------------------------------------ Metode: bind-mount

    private suspend fun bind(real: String, tmp: File, origTmp: File, bound: Boolean, propDiff: Map<String, String>?, st: RunState): String? {
        ensureInfra(st)?.let { return it }
        val key = keyName(real)
        val store = "${st.base}/store/$key"
        val origCopy = "${st.base}/orig/$key"
        val list = "${st.base}/binds.list"
        val reg = StringBuilder()
        if (!bound) reg.append("cat '${origTmp.absolutePath}' > '$origCopy' && ")
        reg.append("cat '${tmp.absolutePath}' > '$store' && chmod 644 '$store' && ")
        reg.append("(chcon --reference='$real' '$store' >/dev/null 2>&1; true) && sync && cmp -s '${tmp.absolutePath}' '$store' && ")
        reg.append("touch '$list' && (grep -vxF '$store|$real' '$list' > '$list.new'; echo '$store|$real' >> '$list.new'; cat '$list.new' > '$list'; rm -f '$list.new')")
        val w = access.runRootEdit(reg.toString(), "simpan hasil edit untuk bind")
        if (!w.ok) return "Gagal menyimpan file hasil edit: ${w.output.trim().take(120)}"
        writePropList(key, propDiff, st)

        // Pasang sekarang lewat skrip yang sama dengan yang dijalankan saat boot.
        access.runRootEdit("sh '${st.base}/senza_apply.sh'", "pasang bind-mount")
        var ok = access.runRootEdit("cmp -s '${tmp.absolutePath}' '$real'", "verifikasi bind").ok
        if (!ok) {
            // Coba sekali lagi dengan perintah eksplisit agar pesan kesalahan mount terlihat.
            val r = access.runRootEdit(
                NS_INIT + "i=0; while [ \$i -lt 8 ] && grep -qF ' $real ' /proc/self/mounts; do \$NS umount '$real' 2>/dev/null || break; i=\$((i+1)); done; " +
                    "\$NS mount --bind '$store' '$real' 2>&1 && cmp -s '${tmp.absolutePath}' '$real'",
                "bind-mount eksplisit"
            )
            ok = r.ok
            if (!ok) {
                if (!bound) removeRecord(st.base, real)
                return "mount --bind gagal: ${r.output.trim().take(140).ifEmpty { "isi path asli tidak berubah (SELinux atau namespace mount terisolasi?)" }}"
            }
        }
        st.live += real
        st.overlaid += real
        st.methods[real] = "bind-mount"
        return null
    }

    private suspend fun writePropList(key: String, props: Map<String, String>?, st: RunState) {
        if (props == null) return
        val f = "${st.base}/props.d/$key.list"
        if (props.isEmpty()) {
            access.runRootEdit("rm -f '$f'", "hapus daftar properti")
            return
        }
        val tmp = File(st.tmpDir, "props_$key.list").apply { writeText(props.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n") }
        val r = access.runRootEdit("cat '${tmp.absolutePath}' > '$f' && chmod 644 '$f'", "simpan daftar properti")
        if (r.ok) st.propsSet += props.size
    }

    /** Buang catatan bind satu file dari penyimpanan (tanpa melepas mount). */
    private suspend fun removeRecord(base: String, real: String) {
        val key = keyName(real)
        val store = "$base/store/$key"
        val list = "$base/binds.list"
        access.runRootEdit(
            "[ -f '$list' ] && (grep -vxF '$store|$real' '$list' > '$list.new'; cat '$list.new' > '$list'; rm -f '$list.new'); " +
                "rm -f '$store' '$base/orig/$key' '$base/props.d/$key.list'; true",
            "bersihkan catatan bind"
        )
    }

    // ------------------------------------------------------------------ Metode: module overlay (Magisk/KernelSU/APatch)

    private suspend fun overlay(real: String, tmp: File, origTmp: File, bound: Boolean, propDiff: Map<String, String>?, st: RunState): String? {
        val store = moduleStore(real)
            ?: return "Module overlay hanya mendukung /system (termasuk /system/tr_*), /vendor, /product, /system_ext, /odm. Pakai Bind-mount untuk $real."
        if (!access.runRootEdit("test -d /data/adb/modules", "cek folder module").ok) {
            return "Folder /data/adb/modules tidak ada: module overlay butuh Magisk/KernelSU/APatch."
        }
        ensureInfra(st)?.let { return it }
        val key = keyName(real)
        val propTmp = File(st.tmpDir, "module.prop").apply {
            writeText("id=senza_edit\nname=Senza Tools Edit Overlay\nversion=1.0\nversionCode=1\nauthor=SenzaProject\ndescription=File hasil edit dari Senza Tools (overlay systemless)\n")
        }
        val sb = StringBuilder()
        sb.append("mkdir -p '${store.substringBeforeLast('/')}' && ")
        sb.append("([ -f '$MODULE_DIR/module.prop' ] || cat '${propTmp.absolutePath}' > '$MODULE_DIR/module.prop') && ")
        sb.append("rm -f '$MODULE_DIR/disable' '$MODULE_DIR/remove' && ")
        if (!bound) sb.append("cat '${origTmp.absolutePath}' > '${st.base}/orig/$key' && ")
        sb.append("cat '${tmp.absolutePath}' > '$store' && chmod 644 '$store' && ")
        sb.append("(chcon --reference='$real' '$store' >/dev/null 2>&1; true) && sync && cmp -s '${tmp.absolutePath}' '$store'")
        val r = access.runRootEdit(sb.toString(), "tulis overlay module")
        if (!r.ok) return "Gagal membuat overlay: ${r.output.trim().take(120)}"
        writePropList(key, propDiff, st)
        st.overlaid += real
        st.methods[real] = "module overlay"

        // Aktifkan sekarang tanpa menunggu restart: bind-mount file module ke path aslinya.
        val b = access.runRootEdit(
            NS_INIT + "i=0; while [ \$i -lt 8 ] && grep -qF ' $real ' /proc/self/mounts; do \$NS umount '$real' 2>/dev/null || break; i=\$((i+1)); done; " +
                "\$NS mount --bind '$store' '$real' && cmp -s '${tmp.absolutePath}' '$real'",
            "bind-mount langsung"
        )
        if (b.ok) st.live += real
        if (propDiff != null && propDiff.isNotEmpty()) access.runRootEdit("sh '${st.base}/senza_apply.sh'", "pasang properti")
        return null
    }

    // ------------------------------------------------------------------ Daftar & penghapusan

    /** Daftar file yang saat ini diganti oleh Senza Tools (bind-mount, module overlay, dan instalasi lama). */
    suspend fun overlays(): List<String> {
        val b = base()
        val r = access.runRootEdit(
            "cat '$b/binds.list' 2>/dev/null; echo '##MODULE'; find '$MODULE_DIR/system' -type f 2>/dev/null; echo '##LEGACY'; cat '$MODULE_DIR/$LEGACY_BIND_DIR/binds.list' 2>/dev/null",
            "daftar hasil edit aktif"
        )
        if (!r.ok && r.output.isBlank()) return emptyList()
        val lines = r.output.lines().map { it.trim() }
        val m = lines.indexOf("##MODULE").let { if (it < 0) lines.size else it }
        val l = lines.indexOf("##LEGACY").let { if (it < 0) lines.size else it }
        val binds = lines.take(m).mapNotNull { it.substringAfter('|', "").takeIf { d -> d.startsWith("/") } }
        val mod = lines.subList(minOf(m + 1, lines.size), l).filter { it.startsWith("$MODULE_DIR/") }
            .mapNotNull { relToPath(it.removePrefix("$MODULE_DIR/")) }
        val legacy = lines.drop(l + 1).mapNotNull { it.substringAfter('|', "").takeIf { d -> d.startsWith("/") } }
        return (binds + mod + legacy).distinct().sorted()
    }

    /** Hapus hasil edit satu file: lepas bind-mount sekarang dan hapus dari semua penyimpanan (file asli kembali langsung). */
    suspend fun removeOverlay(path: String): Boolean {
        val p = PathGuard.validate(path)
        val b = base()
        val key = keyName(p)
        val store = "$b/store/$key"
        val list = "$b/binds.list"
        val unbind = "i=0; while [ \$i -lt 8 ] && grep -qF ' $p ' /proc/self/mounts; do \$NS umount '$p' 2>/dev/null || break; i=\$((i+1)); done"
        val dropLine = "[ -f '$list' ] && (grep -vxF '$store|$p' '$list' > '$list.new'; cat '$list.new' > '$list'; rm -f '$list.new')"
        val legacyList = "$MODULE_DIR/$LEGACY_BIND_DIR/binds.list"
        val legacyDrop = "[ -f '$legacyList' ] && (grep -vxF '${legacyStore(p)}|$p' '$legacyList' > '$legacyList.new'; cat '$legacyList.new' > '$legacyList'; rm -f '$legacyList.new')"
        val moduleFile = moduleStore(p)?.let { "rm -f '$it'; " } ?: ""
        val cmd = NS_INIT + unbind + "; rm -f '$store' '$b/orig/$key' '$b/props.d/$key.list' '${legacyStore(p)}'; $moduleFile$dropLine; $legacyDrop; true"
        return access.runRootEdit(cmd, "hapus hasil edit").ok
    }

    // ------------------------------------------------------------------ Partisi

    /**
     * Pindai partisi: daftar umum + Transsion (tr_*) + Oplus (my_*) + semua mount top-level/dalam-system berjenis
     * erofs/ext4/f2fs/squashfs yang ditemukan di /proc/mounts + direktori mana pun yang memuat build.prop.
     * Path yang hanya symlink ke partisi lain digabung (satu partisi = satu baris).
     */
    suspend fun listPartitions(asRoot: Boolean): List<PartInfo> {
        val base = listOf(
            "system", "product", "system_ext", "vendor", "odm", "vendor_dlkm", "odm_dlkm", "system_dlkm",
            "cust", "preload", "persist", "oem", "optics", "prism", "hw_product", "mi_ext", "product_services", "vendor_ext", "odm_ext"
        )
        val tr = listOf("tr_product", "tr_region", "tr_manifest", "tr_system", "tr_overlayfs", "tr_preload", "tr_company", "tr_carrier")
        val my = listOf("my_product", "my_engineering", "my_company", "my_carrier", "my_region", "my_heytap", "my_stock", "my_preload", "my_bigball", "my_manifest")
        val mountsText = access.run(SafeAction.Mounts, asRoot).output
        val mounts = parseMounts(mountsText)
        val skipTop = setOf("/data", "/cache", "/metadata", "/mnt", "/storage", "/apex", "/config", "/sys", "/proc", "/dev", "/acct", "/debug_ramdisk", "/linkerconfig", "/second_stage_resources", "/sdcard")
        val fsOk = setOf("erofs", "ext4", "f2fs", "squashfs", "ext2", "ext3")
        val topRe = Regex("^/[A-Za-z0-9_.-]+$")
        val sysRe = Regex("^/system/[A-Za-z0-9_.-]+$")
        val dynMounts = mounts.filter { fsOk.contains(it.fs) && !skipTop.contains(it.mp) && (topRe.matches(it.mp) || sysRe.matches(it.mp)) }.map { it.mp }

        // Direktori yang tidak punya mount sendiri tetapi berisi build.prop (partisi tergabung / folder vendor-spesifik).
        val scanCmd = "for d in /*/ /system/*/; do d=\${d%/}; case \"\$d\" in /proc|/sys|/dev|/data|/storage|/mnt|/apex|/sdcard|/config|/acct|/cache|/metadata) continue;; esac; " +
            "if [ -f \"\$d/build.prop\" ] || [ -f \"\$d/etc/build.prop\" ]; then echo \"\$d\"; fi; done"
        val scanned = access.run(SafeAction.ReadScript(scanCmd, "Cari direktori berisi build.prop"), asRoot).output.lines()
            .map { it.trim() }.filter { Regex("^/[A-Za-z0-9_./-]+$").matches(it) && !it.contains("..") }

        val candidates = (base.map { "/$it" } + (tr + my).map { "/$it" } + (tr + my).map { "/system/$it" } +
            base.drop(1).map { "/system/$it" } + dynMounts + scanned).distinct()
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
            val ro = mt?.opts?.contains("ro") == true
            val permRo = mt?.fs == "erofs" || mt?.fs == "squashfs"
            PartInfo(
                path = real,
                fs = mt?.fs ?: "?",
                readOnly = ro,
                mountPoint = mt?.mp ?: "?",
                overlayOk = overlayRel("$real/x") != null,
                aliases = paths.filter { it != real },
                suggest = when {
                    !ro -> "Langsung (rw) atau Bind-mount"
                    permRo -> "Bind-mount (${mt?.fs} hanya-baca permanen)"
                    else -> "Langsung (remount rw) atau Bind-mount"
                }
            )
        }
    }
}
