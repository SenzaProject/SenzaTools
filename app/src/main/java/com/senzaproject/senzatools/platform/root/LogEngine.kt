package com.senzaproject.senzatools.platform.root

import java.security.MessageDigest

enum class LogSource(val label: String) { LOGCAT("Logcat"), CRASH("Crash"), KERNEL("Kernel") }

/** [rank] mengikuti urutan prioritas logcat: V < D < I < W < E < F. */
enum class LogLevel(val label: String, val rank: Int) { ALL("Semua", 0), WARN("W+", 3), ERROR("E+", 4) }

/**
 * Hasil satu pengambilan log (snapshot).
 * [fingerprint] = sidik jari isi [lines] (SHA-256, 12 hex). Isi log yang sama selalu menghasilkan sidik jari yang sama,
 * jadi dua pengambilan bisa dibandingkan tanpa membaca ribuan baris.
 * [total] = jumlah baris yang lolos penyaring di snapshot sebelum dipotong ke N terakhir.
 */
data class LogDump(
    val ok: Boolean,
    val lines: List<String>,
    val message: String,
    val fingerprint: String = "",
    val total: Long = 0,
    val capturedAt: Long = 0
)

/** Aturan murni (tanpa Android) untuk menormalkan, menyaring, dan mengidentifikasi baris log. */
object LogParse {
    val COUNTS = listOf(200, 500, 1000, 2000)

    private val HEADER = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([VDIWEFA])\s""")

    /** Penanda buffer logcat ("--------- beginning of main"). Posisinya bergantung buffer mana yang dibaca lebih dulu, bukan isi log. */
    fun isMarker(line: String): Boolean = line.startsWith("--------- ")

    /** Peringkat level baris format threadtime; null bila bukan baris threadtime. */
    fun levelRank(line: String): Int? {
        val m = HEADER.find(line) ?: return null
        return when (m.groupValues[1]) {
            "V" -> 0; "D" -> 1; "I" -> 2; "W" -> 3; "E" -> 4; else -> 5   // F dan A
        }
    }

    /** Penyaring baris yang dipakai saat membaca keluaran: membuang baris kosong dan penanda buffer, lalu menerapkan level. */
    fun accept(src: LogSource, level: LogLevel): (String) -> Boolean = { raw ->
        val line = raw.trimEnd('\r')
        when {
            line.isBlank() -> false
            isMarker(line) -> false
            src == LogSource.LOGCAT && level != LogLevel.ALL -> (levelRank(line) ?: -1) >= level.rank
            else -> true
        }
    }

    fun command(src: LogSource): String = when (src) {
        LogSource.LOGCAT -> "logcat -d -v threadtime"
        LogSource.CRASH -> "logcat -b crash -d -v threadtime"
        LogSource.KERNEL -> "dmesg"
    }

    /** Filter kata kunci di sisi aplikasi. Tidak pernah dikirim ke shell. */
    fun filter(lines: List<String>, keyword: String): List<String> =
        if (keyword.isBlank()) lines else lines.filter { it.contains(keyword.trim(), ignoreCase = true) }

    fun fingerprint(lines: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        lines.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(10) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    /** Potong dari akhir pada batas baris, agar teks salinan tidak terpotong di tengah baris. */
    fun joinTail(lines: List<String>, maxChars: Int): String {
        var size = 0
        var from = lines.size
        while (from > 0) {
            val add = lines[from - 1].length + 1
            if (size + add > maxChars) break
            size += add; from--
        }
        return lines.subList(from, lines.size).joinToString("\n")
    }
}

/**
 * Mengambil log sebagai snapshot yang konsisten.
 * Prinsip yang membuat hasilnya bisa diulang: isi buffer diambil UTUH dengan `-d` (tanpa jendela `-t N` yang bergeser),
 * penyaringan level dan pemotongan N baris terakhir dilakukan di sini, penanda buffer dibuang, stderr tidak dicampur
 * ke log, dan hasil yang tidak lengkap atau gagal tidak pernah ditampilkan sebagai log.
 */
class LogEngine(private val sh: SystemShell, private val clock: () -> Long = { System.currentTimeMillis() }) {

    fun read(src: LogSource, level: LogLevel, count: Int): LogDump {
        if (!sh.rootVerified) return LogDump(false, emptyList(), "Butuh akses root yang sudah diverifikasi untuk membaca log sistem.")
        if (count !in LogParse.COUNTS) return LogDump(false, emptyList(), "Jumlah baris tidak valid.")
        val r = sh.root(LogParse.command(src), "log ${src.label}", 60_000, count, LogParse.accept(src, level))
        if (r.timedOut) return LogDump(false, emptyList(), "Waktu habis saat mengambil log. Hasil tidak ditampilkan karena tidak lengkap.")
        if (!r.complete) return LogDump(false, emptyList(), "Keluaran log terputus. Hasil tidak ditampilkan karena tidak lengkap. ${r.stderr.take(120)}".trim())
        if (r.exitCode != 0) return LogDump(false, emptyList(), "Perintah gagal (kode ${r.exitCode}). ${r.stderr.take(160)}".trim())
        if (r.lines.isEmpty()) return LogDump(false, emptyList(), "Log kosong pada snapshot ini.")
        return LogDump(true, r.lines, "${r.lines.size} baris", LogParse.fingerprint(r.lines), r.total, clock())
    }
}
