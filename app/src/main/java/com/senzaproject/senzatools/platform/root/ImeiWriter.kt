package com.senzaproject.senzatools.platform.root

import java.io.File

/** Validasi IMEI murni (tanpa Android). Tidak ada pembangkit IMEI di sini — hanya memeriksa angka yang dimasukkan user. */
object ImeiValidator {
    fun digitsOnly(s: String): String = s.filter { it in '0'..'9' }

    /** Cek digit Luhn untuk 15 digit IMEI. */
    fun luhnOk(imei: String): Boolean {
        if (imei.length != 15 || imei.any { it !in '0'..'9' }) return false
        var sum = 0
        for (i in 0 until 15) {
            var d = imei[14 - i] - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    /** Null bila IMEI wajar; selain itu alasan penolakan dalam bahasa Indonesia. */
    fun check(imei: String): String? = when {
        imei.length != 15 -> "IMEI harus 15 digit (sekarang ${imei.length})."
        imei.any { it !in '0'..'9' } -> "IMEI hanya boleh angka."
        imei.all { it == imei[0] } -> "IMEI tidak boleh angka yang sama semua."
        !luhnOk(imei) -> "Digit terakhir (check digit) tidak cocok. Ada angka yang salah ketik — cek lagi dengan box/label HP."
        else -> null
    }
}

data class ImeiWriteReport(val ok: Boolean, val title: String, val lines: List<String>)

/**
 * Tulis / baca IMEI lewat perintah AT ke modem MediaTek (AT+EGMR) dengan akses root.
 *
 * Berjalan di HP tempat aplikasi ini terpasang — BUKAN di HP target yang tersambung lewat BROM.
 * Slot 7 = IMEI 1, slot 10 = IMEI 2. Hasil selalu dibaca ulang dari modem; "berhasil" hanya dilaporkan
 * kalau IMEI yang terbaca sama persis dengan yang ditulis.
 * Batasan jujur: butuh port AT modem yang terbuka di ROM (/dev/radio/pttycmd1 atau atci1). Kalau tidak ada,
 * laporan menyebutnya dengan jelas dan tidak ada yang ditulis.
 */
class ImeiWriter(private val filesDir: File, private val sh: SystemShell) {

    companion object {
        private val EGMR = Regex("""\+EGMR:\s*"?(\d{15})"?""")
        private const val OUT = "/data/local/tmp/senza_at.out"

        fun parseEgmr(section: String): String? = EGMR.find(section)?.groupValues?.get(1)

        /** Pecah keluaran skrip menjadi bagian per penanda "@@NAMA". */
        fun sections(out: List<String>): Map<String, String> {
            val m = LinkedHashMap<String, StringBuilder>()
            var cur: StringBuilder? = null
            for (l in out) {
                if (l.startsWith("@@")) {
                    val name = l.removePrefix("@@").trim().substringBefore(' ')
                    cur = StringBuilder().also { sb -> m[name] = sb }
                    val rest = l.removePrefix("@@").trim().substringAfter(' ', "")
                    if (rest.isNotEmpty()) cur.appendLine(rest)
                } else cur?.appendLine(l)
            }
            return m.mapValues { it.value.toString() }
        }
    }

    private fun script(f1: File?, f2: File?, write: Boolean): String {
        val d = "$"
        val sb = StringBuilder()
        sb.appendLine("DEV=\"\"")
        sb.appendLine("for d in /dev/radio/pttycmd1 /dev/radio/atci1; do [ -c \"${d}d\" ] && DEV=\"${d}d\" && break; done")
        sb.appendLine("if [ -z \"${d}DEV\" ]; then echo \"@@NODEV\"; exit 0; fi")
        sb.appendLine("echo \"@@DEV ${d}DEV\"")
        sb.appendLine("OUT=$OUT")
        sb.appendLine("at() { : > \"${d}OUT\"; timeout 3 cat \"${d}DEV\" > \"${d}OUT\" 2>/dev/null & sleep 0.4; printf '%s\\r' \"${d}1\" > \"${d}DEV\"; wait; tr -d '\\r' < \"${d}OUT\"; }")
        sb.appendLine("echo \"@@B1\"; at 'AT+EGMR=0,7'")
        sb.appendLine("echo \"@@B2\"; at 'AT+EGMR=0,10'")
        if (write && f1 != null) {
            sb.appendLine("echo \"@@W1\"; at \"AT+EGMR=1,7,\\\"${d}(cat ${f1.absolutePath})\\\"\"")
            if (f2 != null) sb.appendLine("echo \"@@W2\"; at \"AT+EGMR=1,10,\\\"${d}(cat ${f2.absolutePath})\\\"\"")
        }
        sb.appendLine("echo \"@@A1\"; at 'AT+EGMR=0,7'")
        sb.appendLine("echo \"@@A2\"; at 'AT+EGMR=0,10'")
        sb.appendLine("rm -f \"${d}OUT\"")
        return sb.toString()
    }

    /** Baca IMEI yang sekarang dilaporkan modem (tidak menulis apa pun). */
    fun read(): ImeiWriteReport {
        if (!sh.rootVerified) return ImeiWriteReport(false, "Butuh akses root", listOf("Izinkan root dulu di Tools → Akses."))
        val r = sh.root(script(null, null, write = false), "Baca IMEI (AT)", 30_000)
        val sec = sections(r.lines)
        if ("NODEV" in sec) return noDev()
        val a1 = parseEgmr(sec["A1"] ?: "")
        val a2 = parseEgmr(sec["A2"] ?: "")
        val lines = listOf(
            "IMEI 1: ${a1 ?: "tidak terbaca / kosong"}",
            "IMEI 2: ${a2 ?: "tidak terbaca / kosong"}"
        )
        return ImeiWriteReport(a1 != null || a2 != null, "IMEI yang dilaporkan modem", lines)
    }

    fun write(imei1: String, imei2: String?): ImeiWriteReport {
        if (!sh.rootVerified) return ImeiWriteReport(false, "Butuh akses root", listOf("Izinkan root dulu di Tools → Akses."))
        ImeiValidator.check(imei1)?.let { return ImeiWriteReport(false, "IMEI 1 ditolak", listOf(it)) }
        if (!imei2.isNullOrEmpty()) {
            ImeiValidator.check(imei2)?.let { return ImeiWriteReport(false, "IMEI 2 ditolak", listOf(it)) }
            if (imei2 == imei1) return ImeiWriteReport(false, "IMEI 2 ditolak", listOf("IMEI 2 tidak boleh sama dengan IMEI 1."))
        }
        // IMEI dititipkan lewat file di folder privat app supaya tidak ikut tertulis di log perintah root.
        val f1 = File(filesDir, ".imei1.tmp")
        val f2 = if (!imei2.isNullOrEmpty()) File(filesDir, ".imei2.tmp") else null
        try {
            f1.writeText(imei1)
            f2?.writeText(imei2!!)
            val r = sh.root(script(f1, f2, write = true), "Tulis IMEI (AT)", 60_000)
            val sec = sections(r.lines)
            if ("NODEV" in sec) return noDev()
            val b1 = parseEgmr(sec["B1"] ?: ""); val b2 = parseEgmr(sec["B2"] ?: "")
            val a1 = parseEgmr(sec["A1"] ?: ""); val a2 = parseEgmr(sec["A2"] ?: "")
            val w1ok = (sec["W1"] ?: "").let { "OK" in it && "ERROR" !in it }
            val w2ok = f2 == null || (sec["W2"] ?: "").let { "OK" in it && "ERROR" !in it }
            val v1 = a1 == imei1
            val v2 = f2 == null || a2 == imei2
            val lines = mutableListOf<String>()
            lines += "Sebelum: IMEI 1 = ${b1 ?: "kosong/tidak terbaca"}" + if (f2 != null || b2 != null) ", IMEI 2 = ${b2 ?: "kosong/tidak terbaca"}" else ""
            lines += "Perintah tulis IMEI 1: " + if (w1ok) "diterima modem (OK)" else "DITOLAK / tidak ada jawaban"
            if (f2 != null) lines += "Perintah tulis IMEI 2: " + if (w2ok) "diterima modem (OK)" else "DITOLAK / tidak ada jawaban"
            lines += "Dibaca ulang: IMEI 1 = ${a1 ?: "tidak terbaca"}" + if (f2 != null) ", IMEI 2 = ${a2 ?: "tidak terbaca"}" else ""
            val ok = v1 && v2
            if (ok) {
                lines += "Cocok dengan yang ditulis. Restart HP, lalu cek dengan *#06#."
            } else {
                lines += "Hasil baca ulang TIDAK sama dengan yang ditulis, jadi dianggap GAGAL. Jangan percaya angka di layar sebelum restart dan cek *#06#."
            }
            return ImeiWriteReport(ok, if (ok) "IMEI berhasil ditulis" else "IMEI gagal ditulis", lines)
        } finally {
            f1.delete(); f2?.delete()
        }
    }

    private fun noDev() = ImeiWriteReport(
        false, "Port AT modem tidak ditemukan",
        listOf(
            "ROM ini tidak membuka /dev/radio/pttycmd1 maupun /dev/radio/atci1 untuk root, jadi tidak ada yang ditulis.",
            "Cara ini tidak bisa dipakai di HP ini. Alternatifnya perlu tool resmi seperti Maui META / SN Writer lewat PC."
        )
    )
}
