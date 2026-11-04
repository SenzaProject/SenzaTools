package com.senzaproject.senzatools.platform.root

import com.senzaproject.senzatools.analyzer.system.AvcDenial
import com.senzaproject.senzatools.analyzer.system.SelinuxAnalyzer

/** Satu-satunya parser mode SELinux di aplikasi. Terima "1"/"0" (sysfs) atau "Enforcing"/"Permissive"/"Disabled" (getenforce). */
object SelinuxMode {
    fun parse(text: String?): String? {
        if (text == null) return null
        for (raw in text.lineSequence()) {
            val v = raw.trim().lowercase()
            val m = when {
                v == "1" || v.startsWith("enforcing") -> "Enforcing"
                v == "0" || v.startsWith("permissive") -> "Permissive"
                v.startsWith("disabled") -> "Disabled"
                else -> null
            }
            if (m != null) return m
        }
        return null
    }
}

/**
 * Mode SELinux beserta asal bacanya.
 * [mode] dipilih dengan urutan tetap: root > sysfs > getenforce aplikasi. Selama sumbernya sama, hasilnya sama;
 * bila sumber-sumber saling bertentangan, [conflict] true dan [readings] memuat bacaan tiap sumber.
 */
data class ModeStatus(val mode: String?, val source: String?, val readings: Map<String, String?>, val conflict: Boolean) {
    fun describe(): String = when {
        mode == null -> "Tidak terbaca dari sumber mana pun (${readings.keys.joinToString()})."
        conflict -> "Sumber berbeda: " + readings.entries.joinToString { "${it.key}=${it.value ?: "?"}" } + ". Dipakai: $source."
        else -> "Sumber: $source."
    }
}

data class AvcSourceNote(val name: String, val ok: Boolean, val lines: Int, val note: String)

data class AvcScan(
    val denials: List<AvcDenial>,
    val sources: List<AvcSourceNote>,
    val totalEvents: Int,
    val fingerprint: String,
    /** True hanya bila SEMUA sumber terbaca lengkap. Bila false, jangan menyimpulkan "tidak ada denial". */
    val complete: Boolean
)

/** Logika SELinux yang murni (tanpa Android): baca mode, ubah mode, dan pindai denial. */
class SelinuxEngine(private val sh: SystemShell, private val sleep: (Long) -> Unit = { Thread.sleep(it) }) {

    data class Res(val ok: Boolean, val message: String)

    companion object {
        private const val SYSFS = "/sys/fs/selinux/enforce"
        const val MAX_AVC_LINES = 100_000
    }

    private fun <T> retry(times: Int, block: () -> T?): T? {
        repeat(times) { block()?.let { return it } }
        return null
    }

    /** Baca semua sumber yang tersedia; tidak ada yang dibuang diam-diam. */
    fun status(): ModeStatus {
        val readings = linkedMapOf<String, String?>()
        readings["sysfs"] = SelinuxMode.parse(sh.readFile(SYSFS))
        readings["getenforce"] = retry(2) {
            val r = sh.app("getenforce", 3_000)
            if (r.ok) SelinuxMode.parse(r.lines.joinToString("\n")) else null
        }
        if (sh.rootVerified) {
            readings["root"] = retry(2) {
                val r = sh.root("getenforce", "baca mode SELinux", 5_000, 10)
                if (r.ok) SelinuxMode.parse(r.lines.joinToString("\n")) else null
            }
        }
        val order = listOf("root", "sysfs", "getenforce")
        val source = order.firstOrNull { readings[it] != null }
        val mode = source?.let { readings[it] }
        val conflict = readings.values.filterNotNull().distinct().size > 1
        return ModeStatus(mode, source, readings, conflict)
    }

    fun setPermissive(permissive: Boolean): Res {
        if (!sh.rootVerified) return Res(false, "Butuh akses root yang sudah diverifikasi.")
        val want = if (permissive) "Permissive" else "Enforcing"
        val r = sh.root("setenforce ${if (permissive) 0 else 1}", "SELinux -> $want", 15_000, 10)
        var st = status()
        var tries = 1
        while (st.mode != want && tries < 3) { sleep(200); st = status(); tries++ }
        return if (st.mode == want) Res(true, "SELinux sekarang $want." + if (permissive) " Kembali Enforcing setelah restart." else "")
        else Res(false, "Mode belum berubah (sekarang ${st.mode ?: "tidak terbaca"}). Kernel mungkin memaksa Enforcing. ${r.stderr.take(120)}".trim())
    }

    private fun isAvc(line: String) = line.contains("avc:")

    private fun capture(name: String, commands: List<String>): Pair<String, AvcSourceNote> {
        var last: com.senzaproject.senzatools.utils.StreamResult? = null
        for (cmd in commands) {
            val r = sh.root(cmd, "pindai denial ($name)", 60_000, MAX_AVC_LINES, ::isAvc)
            last = r
            if (r.ok) {
                val trunc = r.total > r.lines.size
                val note = if (trunc) "Melebihi batas ${r.lines.size} baris; ${r.total - r.lines.size} kejadian terlama tidak dihitung." else "lengkap"
                return r.lines.joinToString("\n") to AvcSourceNote(name, !trunc, r.lines.size, note)
            }
        }
        val why = when {
            last == null -> "tidak dijalankan"
            last.timedOut -> "waktu habis"
            !last.complete -> "keluaran terputus"
            else -> "kode ${last.exitCode} ${last.stderr.take(100)}".trim()
        }
        return "" to AvcSourceNote(name, false, 0, "gagal dibaca: $why")
    }

    /**
     * Memindai dmesg DAN logcat (selalu keduanya, tidak ada cadangan yang berpindah-pindah), seluruh isi buffer
     * (bukan jendela N baris terakhir), lalu menggabungkan tanpa hitung ganda lewat id audit.
     */
    fun scanDenials(): AvcScan {
        if (!sh.rootVerified) return AvcScan(emptyList(), emptyList(), 0, "", false)
        val (dm, dmNote) = capture("dmesg", listOf("dmesg"))
        val (lc, lcNote) = capture("logcat", listOf("logcat -b all -d -v threadtime", "logcat -d -v threadtime"))
        val list = SelinuxAnalyzer.parseSources(listOf(dm, lc))
        return AvcScan(list, listOf(dmNote, lcNote), list.sumOf { it.count }, SelinuxAnalyzer.fingerprint(list), dmNote.ok && lcNote.ok)
    }

    /** Teks mentah baris avc dari satu sumber, untuk layar Analyzer. Null bila gagal. */
    fun captureAvcText(source: String): Pair<String?, AvcSourceNote> {
        if (!sh.rootVerified) return null to AvcSourceNote(source, false, 0, "butuh root")
        val (text, note) = if (source == "dmesg") capture("dmesg", listOf("dmesg"))
        else capture("logcat", listOf("logcat -b all -d -v threadtime", "logcat -d -v threadtime"))
        return (if (note.note.startsWith("gagal")) null else text) to note
    }
}
