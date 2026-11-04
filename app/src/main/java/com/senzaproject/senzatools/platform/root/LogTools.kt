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
 *
 * Pengambilan dan penyaringan ada di [LogEngine] / [LogParse] (murni, diuji di JVM).
 */
class LogTools(private val access: AccessManager) {

    data class Res(val ok: Boolean, val message: String)

    private val engine = LogEngine(AccessShell(access))

    companion object {
        val COUNTS get() = LogParse.COUNTS
        private const val BASE = "/sdcard/Download/SenzaTools/logs"

        fun filter(lines: List<String>, keyword: String): List<String> = LogParse.filter(lines, keyword)
    }

    /** Ambil snapshot log. Hasil sama bila isi buffer sama; bandingkan lewat [LogDump.fingerprint]. */
    suspend fun read(src: LogSource, level: LogLevel, count: Int): LogDump = withContext(Dispatchers.IO) {
        engine.read(src, level, count)
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
