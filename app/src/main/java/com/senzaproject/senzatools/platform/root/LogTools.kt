package com.senzaproject.senzatools.platform.root

import com.senzaproject.senzatools.platform.AccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pengambil log untuk debug ROM: logcat (semua / crash), log kernel (dmesg), simpan paket log ke file, dan
 * hapus buffer. Semua perintah disusun dari pilihan tetap (bukan teks bebas); kata kunci filter hanya dipakai
 * di sisi aplikasi, tidak pernah dikirim ke shell. Butuh root agar log seluruh sistem terbaca.
 */
class LogTools(private val access: AccessManager) {

    enum class Source(val label: String) { LOGCAT("Logcat"), CRASH("Crash"), KERNEL("Kernel") }
    enum class Level(val label: String, val filter: String) {
        ALL("Semua", ""), WARN("W+", "'*:W'"), ERROR("E+", "'*:E'")
    }

    data class Dump(val ok: Boolean, val lines: List<String>, val message: String)
    data class Res(val ok: Boolean, val message: String)

    companion object {
        val COUNTS = listOf(200, 500, 1000, 2000)
        private const val BASE = "/sdcard/Download/SenzaTools/logs"

        fun command(src: Source, level: Level, count: Int): String = when (src) {
            Source.LOGCAT -> "logcat -d -v threadtime -t $count ${level.filter}".trim()
            Source.CRASH -> "logcat -b crash -d -v threadtime -t $count"
            Source.KERNEL -> "dmesg 2>&1 | tail -n $count"
        }

        fun filter(lines: List<String>, keyword: String): List<String> =
            if (keyword.isBlank()) lines else lines.filter { it.contains(keyword.trim(), ignoreCase = true) }
    }

    suspend fun read(src: Source, level: Level, count: Int): Dump = withContext(Dispatchers.IO) {
        if (!access.isRootVerified) return@withContext Dump(false, emptyList(), "Butuh akses root yang sudah diverifikasi untuk membaca log sistem.")
        if (count !in COUNTS) return@withContext Dump(false, emptyList(), "Jumlah baris tidak valid.")
        val r = access.runRootEdit(command(src, level, count), "log ${src.label}", 30_000)
        val lines = r.output.lines().filter { it.isNotBlank() }
        if (r.timedOut) Dump(false, lines, "Waktu habis saat mengambil log.")
        else if (lines.isEmpty()) Dump(false, emptyList(), "Log kosong (kode ${r.exitCode}).")
        else Dump(true, lines, "${lines.size} baris")
    }

    /** Simpan logcat, crash, dmesg, dan getprop ke satu folder bertanggal di Download/SenzaTools/logs. */
    suspend fun saveBundle(): Res = withContext(Dispatchers.IO) {
        if (!access.isRootVerified) return@withContext Res(false, "Butuh akses root yang sudah diverifikasi.")
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = "$BASE/$ts"
        val cmd = "mkdir -p $dir && " +
            "logcat -d -v threadtime > $dir/logcat.txt 2>&1; " +
            "logcat -b crash -d -v threadtime > $dir/crash.txt 2>&1; " +
            "dmesg > $dir/dmesg.txt 2>&1; " +
            "getprop > $dir/getprop.txt 2>&1; " +
            "wc -c $dir/*.txt"
        val r = access.runRootEdit(cmd, "simpan paket log", 60_000)
        if (!r.output.contains("logcat.txt")) return@withContext Res(false, "Gagal menyimpan. ${r.output.trim().take(160)}")
        val sizes = r.output.lines().map { it.trim() }.filter { it.endsWith(".txt") && !it.endsWith("total") }
            .joinToString("\n") { l ->
                val parts = l.split(Regex("\\s+"))
                val bytes = parts.firstOrNull()?.toLongOrNull()
                (parts.lastOrNull()?.substringAfterLast('/') ?: l) + (bytes?.let { " (${it / 1024} KB)" } ?: "")
            }
        Res(true, "Tersimpan di $dir\n$sizes")
    }

    /** Kosongkan semua buffer logcat. Berguna sebelum mereproduksi bug supaya log bersih. */
    suspend fun clearBuffers(): Res = withContext(Dispatchers.IO) {
        if (!access.isRootVerified) return@withContext Res(false, "Butuh akses root yang sudah diverifikasi.")
        val r = access.runRootEdit("logcat -b all -c", "bersihkan buffer logcat")
        if (r.ok) Res(true, "Buffer logcat dikosongkan.") else Res(false, "Gagal membersihkan buffer. ${r.output.trim().take(120)}")
    }
}
