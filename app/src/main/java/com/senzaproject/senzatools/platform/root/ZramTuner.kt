package com.senzaproject.senzatools.platform.root

import android.content.Context
import com.senzaproject.senzatools.platform.AccessManager
import com.senzaproject.senzatools.utils.Sys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pengatur zRAM (zram0): ukuran 512 MB sampai 8 GB, algoritma kompresi, dan swappiness.
 * Semua perintah root disusun di sini dari nilai yang divalidasi (bukan teks bebas) dan dicatat di log.
 * Nilai bawaan HP disimpan sekali sebelum perubahan pertama supaya bisa dikembalikan.
 */
class ZramTuner(ctx: Context, private val access: AccessManager) {

    data class Info(
        val present: Boolean,
        val diskMb: Long,
        val algo: String?,
        val algos: List<String>,
        val ramMb: Long,
        val swapTotalMb: Long,
        val swapUsedMb: Long,
        val origMb: Long,
        val comprMb: Long,
        val swappiness: Int?
    )

    data class Res(val ok: Boolean, val message: String)

    companion object {
        const val MIN_MB = 512L
        const val MAX_MB = 8192L
        private const val S = "/sys/block/zram0"
        private const val DIR = "/data/adb/senza_zram"
        private val ALGO = Regex("^[a-z0-9_-]{1,16}\$")
    }

    private val prefs = ctx.getSharedPreferences("senza_zram", Context.MODE_PRIVATE)

    /** Terapkan otomatis tiap boot (lewat BootReceiver). */
    var bootOn: Boolean
        get() = prefs.getBoolean("boot_on", false)
        private set(v) { prefs.edit().putBoolean("boot_on", v).apply() }

    // ------------------------------------------------------------------ Baca

    private fun plainRead(): Map<String, String> = mapOf(
        "disk" to (Sys.readText("$S/disksize")?.trim() ?: ""),
        "algo" to (Sys.readText("$S/comp_algorithm")?.trim() ?: ""),
        "mm" to (Sys.readText("$S/mm_stat")?.trim() ?: ""),
        "sw" to (Sys.readText("/proc/sys/vm/swappiness")?.trim() ?: "")
    )

    private fun rootRead(): Map<String, String> {
        val cmd = "echo disk=\$(cat $S/disksize 2>/dev/null); echo algo=\$(cat $S/comp_algorithm 2>/dev/null); " +
            "echo mm=\$(cat $S/mm_stat 2>/dev/null); echo sw=\$(cat /proc/sys/vm/swappiness 2>/dev/null)"
        val r = Sys.execute(listOf("su", "-c", cmd), 5_000)
        return r.output.lines().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i > 0) l.substring(0, i) to l.substring(i + 1).trim() else null
        }.toMap()
    }

    suspend fun read(): Info = withContext(Dispatchers.IO) {
        var m = plainRead()
        if (m["disk"]?.toLongOrNull() == null && access.isRootVerified) m = rootRead()
        val disk = m["disk"]?.toLongOrNull()
        val rawAlgo = m["algo"].orEmpty()
        val algos = rawAlgo.split(Regex("\\s+")).map { it.trim('[', ']') }.filter { it.isNotBlank() }
        val active = Regex("\\[([^\\]]+)\\]").find(rawAlgo)?.groupValues?.get(1)
        val mm = m["mm"].orEmpty().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }

        val mi = Sys.readText("/proc/meminfo").orEmpty()
        fun kb(k: String) = Regex("^$k:\\s+(\\d+)", RegexOption.MULTILINE).find(mi)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val swapTotal = kb("SwapTotal")
        val swapFree = kb("SwapFree")

        Info(
            present = disk != null,
            diskMb = (disk ?: 0L) / 1048576L,
            algo = active,
            algos = algos,
            ramMb = kb("MemTotal") / 1024L,
            swapTotalMb = swapTotal / 1024L,
            swapUsedMb = (swapTotal - swapFree).coerceAtLeast(0L) / 1024L,
            origMb = (mm.getOrNull(0) ?: 0L) / 1048576L,
            comprMb = (mm.getOrNull(1) ?: 0L) / 1048576L,
            swappiness = m["sw"]?.toIntOrNull()
        )
    }

    // ------------------------------------------------------------------ Terapkan

    /** Skrip pengaturan zRAM. [restoreOnFail]: bila gagal di tengah jalan, coba nyalakan swap lagi. */
    private fun script(sizeMb: Long, algo: String?, sw: Int, restoreOnFail: Boolean): String = buildString {
        appendLine("Z=/dev/block/zram0; [ -b \"\$Z\" ] || Z=/dev/zram0")
        appendLine("S=$S")
        appendLine("[ -e \"\$S/disksize\" ] || exit 10")
        appendLine(if (restoreOnFail) "fail() { swapon \"\$Z\" 2>/dev/null; exit \$1; }" else "fail() { exit \$1; }")
        appendLine("swapoff \"\$Z\" 2>/dev/null")
        appendLine("echo 1 > \"\$S/reset\" || fail 11")
        if (algo != null) appendLine("echo $algo > \"\$S/comp_algorithm\" || fail 15")
        appendLine("echo ${sizeMb * 1048576L} > \"\$S/disksize\" || fail 12")
        appendLine("mkswap \"\$Z\" >/dev/null 2>&1 || fail 13")
        appendLine("swapon \"\$Z\" || fail 14")
        appendLine("echo $sw > /proc/sys/vm/swappiness 2>/dev/null")
        append("exit 0")
    }

    private fun explain(code: Int): String = when (code) {
        10 -> "Perangkat zram0 tidak ditemukan di kernel ini."
        11 -> "zRAM masih dipakai dan tidak bisa direset (swapoff gagal, kemungkinan RAM terlalu penuh). Tutup beberapa aplikasi lalu coba lagi."
        12 -> "Kernel menolak ukuran zRAM ini."
        13 -> "mkswap gagal."
        14 -> "swapon gagal; swap tidak aktif."
        15 -> "Kernel menolak algoritma kompresi ini."
        else -> "Perintah gagal (kode $code)."
    }

    private suspend fun writeBoot(body: String) {
        val cmd = "mkdir -p $DIR && cat > $DIR/boot.sh <<'SENZA_ZRAM_EOF'\n#!/system/bin/sh\n$body\nSENZA_ZRAM_EOF\nchmod 700 $DIR/boot.sh"
        access.runRootEdit(cmd, "simpan skrip boot zRAM")
    }

    private suspend fun run(sizeMb: Long, algo: String?, sw: Int, boot: Boolean): Res {
        val r = access.runRootEdit(script(sizeMb, algo, sw, true), "zRAM $sizeMb MB", 120_000)
        if (!r.ok) return Res(false, explain(r.exitCode))
        if (boot) writeBoot(script(sizeMb, algo, sw, false)) else access.runRootEdit("rm -rf $DIR", "hapus skrip boot zRAM")
        bootOn = boot
        val after = read()
        return if (after.diskMb == sizeMb && after.swapTotalMb > 0)
            Res(true, "zRAM aktif: ${after.diskMb} MB" + (after.algo?.let { ", $it" } ?: "") + (if (boot) ". Akan diterapkan otomatis tiap boot." else ". Kembali ke bawaan setelah restart."))
        else Res(false, "Perintah selesai tapi nilai belum sesuai (zRAM ${after.diskMb} MB, swap ${after.swapTotalMb} MB).")
    }

    suspend fun apply(sizeMb: Long, algo: String?, sw: Int, boot: Boolean): Res = withContext(Dispatchers.IO) {
        if (!access.isRootVerified) return@withContext Res(false, "Butuh akses root yang sudah diverifikasi.")
        if (sizeMb !in MIN_MB..MAX_MB) return@withContext Res(false, "Ukuran zRAM harus 512 MB sampai 8 GB.")
        if (sw !in 0..200) return@withContext Res(false, "Swappiness harus 0 sampai 200.")
        val cur = read()
        if (!cur.present) return@withContext Res(false, "zRAM (zram0) tidak ditemukan di kernel ini.")
        if (algo != null && (!ALGO.matches(algo) || algo !in cur.algos)) return@withContext Res(false, "Algoritma tidak didukung kernel ini.")
        if (!prefs.contains("orig_mb")) {
            prefs.edit()
                .putLong("orig_mb", cur.diskMb)
                .putString("orig_algo", cur.algo ?: "")
                .putInt("orig_sw", cur.swappiness ?: 60)
                .apply()
        }
        run(sizeMb, algo, sw, boot)
    }

    /** Hapus penerapan otomatis saat boot dan kembalikan ke nilai bawaan HP (bila sempat tercatat). */
    suspend fun restoreDefault(): Res = withContext(Dispatchers.IO) {
        if (!access.isRootVerified) return@withContext Res(false, "Butuh akses root yang sudah diverifikasi.")
        access.runRootEdit("rm -rf $DIR", "hapus skrip boot zRAM")
        bootOn = false
        if (!prefs.contains("orig_mb")) return@withContext Res(true, "Penerapan saat boot dihapus. Restart HP untuk kembali ke zRAM bawaan.")
        val mb = prefs.getLong("orig_mb", 0L)
        val algo = prefs.getString("orig_algo", "")?.takeIf { ALGO.matches(it) }
        val sw = prefs.getInt("orig_sw", 60)
        if (mb <= 0L) {
            val r = access.runRootEdit("Z=/dev/block/zram0; [ -b \"\$Z\" ] || Z=/dev/zram0; swapoff \"\$Z\" 2>/dev/null; echo 1 > ${S}/reset", "matikan zRAM")
            return@withContext if (r.ok) Res(true, "zRAM dimatikan seperti bawaan HP.") else Res(false, "Gagal mematikan zRAM.")
        }
        val cur = read()
        run(mb, algo?.takeIf { it in cur.algos }, sw, false).let {
            if (it.ok) Res(true, "Dikembalikan ke bawaan: ${mb} MB.") else it
        }
    }
}
