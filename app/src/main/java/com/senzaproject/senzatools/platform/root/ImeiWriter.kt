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
 *
 * Perbaikan: port AT dicoba satu per satu dengan perintah "AT" dan dipilih yang benar-benar menjawab "OK"
 * (sebelumnya hanya dipilih port pertama yang ada, walau tidak menjawab). Port dipasang mode raw tanpa echo,
 * balasan ditunggu sampai "OK"/"ERROR" muncul (bukan jeda tetap), dan bila SELinux Enforcing memblokir port,
 * SELinux dipermissive-kan sementara lalu dikembalikan. Laporan kegagalan kini menampilkan jawaban mentah modem
 * (IMEI disamarkan) supaya "modem diam" tidak lagi terlihat sama dengan "modem menolak".
 */
class ImeiWriter(private val filesDir: File, private val sh: SystemShell) {

    companion object {
        private val EGMR = Regex("""\+EGMR:[^\r\n]*?(\d{15})""")
        private val LONG_DIGITS = Regex("""\d{15}""")
        private const val OUT = "/data/local/tmp/senza_at.out"

        fun parseEgmr(section: String): String? = EGMR.find(section)?.groupValues?.get(1)

        /** Samarkan IMEI (15 digit) dalam teks diagnostik: tampil 4 digit awal dan 2 digit akhir. */
        fun mask(s: String): String = LONG_DIGITS.replace(s) { it.value.take(4) + "•••••••••" + it.value.takeLast(2) }

        /** Ringkas jawaban mentah modem untuk laporan; "(kosong)" bila tidak ada jawaban sama sekali. */
        fun snippet(section: String?): String {
            val t = (section ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (t.isEmpty()) return "(kosong — modem tidak menjawab)"
            return mask(t.take(4).joinToString(" | ")).take(140)
        }

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

    // Tanda § diganti menjadi $ supaya skrip shell mudah dibaca di dalam string mentah Kotlin.
    private val shellTemplate = """
CANDS=""
for p in /dev/radio/pttycmd1 /dev/radio/atci1 /dev/radio/pttycmd2 /dev/radio/atci2; do [ -c "§p" ] && CANDS="§CANDS §p"; done
if [ -z "§CANDS" ]; then echo "@@NODEV"; exit 0; fi
OUT=@OUT@
ENF="§(getenforce 2>/dev/null)"
echo "@@ENF §ENF"
RESTORE=""
trap '[ -n "§RESTORE" ] && setenforce 1 2>/dev/null; rm -f "§OUT"' EXIT
at() {
  : > "§OUT"
  cat "§1" > "§OUT" 2>/dev/null &
  CP=§!
  sleep 0.3
  printf '%s\r\n' "§2" > "§1" 2>/dev/null
  i=0
  while [ §i -lt 25 ]; do
    grep -qE 'OK|ERROR' "§OUT" 2>/dev/null && break
    sleep 0.2; i=§((i+1))
  done
  sleep 0.2
  kill §CP 2>/dev/null; wait §CP 2>/dev/null
  tr -d '\r' < "§OUT"
}
DEV=""; N=0
probe() {
  for p in §CANDS; do
    stty -F "§p" raw -echo 2>/dev/null
    N=§((N+1))
    echo "@@PROBE§N §p"
    R="§(at "§p" AT)"
    echo "§R"
    case "§R" in *OK*) DEV="§p"; return;; esac
  done
}
probe
if [ -z "§DEV" ] && [ "§ENF" = "Enforcing" ]; then
  setenforce 0 2>/dev/null && RESTORE=1 && echo "@@PERMISSIVE" && probe
fi
if [ -z "§DEV" ]; then echo "@@NORESP"; exit 0; fi
echo "@@DEV §DEV"
echo "@@B1"; at "§DEV" 'AT+EGMR=0,7'
echo "@@B2"; at "§DEV" 'AT+EGMR=0,10'
@WRITE@
sleep 1
echo "@@A1"; at "§DEV" 'AT+EGMR=0,7'
echo "@@A2"; at "§DEV" 'AT+EGMR=0,10'
""".trimIndent().replace("§", "$")

    private fun script(f1: File?, f2: File?, write: Boolean): String {
        val w = StringBuilder()
        if (write && f1 != null) {
            w.appendLine("echo \"@@W1\"; at \"\$DEV\" \"AT+EGMR=1,7,\\\"\$(cat ${f1.absolutePath})\\\"\"")
            if (f2 != null) w.appendLine("echo \"@@W2\"; at \"\$DEV\" \"AT+EGMR=1,10,\\\"\$(cat ${f2.absolutePath})\\\"\"")
        }
        return shellTemplate.replace("@OUT@", OUT).replace("@WRITE@", w.toString())
    }

    /** Baris diagnostik: port yang dipakai, mode SELinux, dan jawaban modem pada tiap port yang dicoba. */
    private fun probeLines(sec: Map<String, String>): List<String> {
        val l = mutableListOf<String>()
        sec["ENF"]?.trim()?.takeIf { it.isNotEmpty() }?.let { l += "SELinux: $it" + if ("PERMISSIVE" in sec) " (dibuat permissive sementara, lalu dikembalikan)" else "" }
        sec.filterKeys { it.startsWith("PROBE") }.forEach { (_, v) ->
            val dev = v.lines().firstOrNull()?.trim().orEmpty()
            val resp = snippet(v.lines().drop(1).joinToString("\n"))
            l += "Tes AT di $dev → $resp"
        }
        return l
    }

    /** Baca IMEI yang sekarang dilaporkan modem (tidak menulis apa pun). */
    fun read(): ImeiWriteReport {
        if (!sh.rootVerified) return ImeiWriteReport(false, "Butuh akses root", listOf("Izinkan root dulu di Tools → Akses."))
        val r = sh.root(script(null, null, write = false), "Baca IMEI (AT)", 45_000)
        val sec = sections(r.lines)
        if ("NODEV" in sec) return noDev()
        if ("NORESP" in sec) return noResp(sec)
        val a1 = parseEgmr(sec["A1"] ?: "")
        val a2 = parseEgmr(sec["A2"] ?: "")
        val lines = mutableListOf(
            "IMEI 1: ${a1 ?: "tidak terbaca / kosong"}",
            "IMEI 2: ${a2 ?: "tidak terbaca / kosong"}"
        )
        if (a1 == null && a2 == null) {
            lines += "Port AT menjawab, tapi IMEI kosong. Jawaban mentah: ${snippet(sec["A1"])}"
        }
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
            f1.setReadable(true, false); f2?.setReadable(true, false)
            val r = sh.root(script(f1, f2, write = true), "Tulis IMEI (AT)", 90_000)
            val sec = sections(r.lines)
            if ("NODEV" in sec) return noDev()
            if ("NORESP" in sec) return noResp(sec)
            val b1 = parseEgmr(sec["B1"] ?: ""); val b2 = parseEgmr(sec["B2"] ?: "")
            val a1 = parseEgmr(sec["A1"] ?: ""); val a2 = parseEgmr(sec["A2"] ?: "")
            val w1ok = (sec["W1"] ?: "").let { "OK" in it && "ERROR" !in it }
            val w2ok = f2 == null || (sec["W2"] ?: "").let { "OK" in it && "ERROR" !in it }
            val v1 = a1 == imei1
            val v2 = f2 == null || a2 == imei2
            val lines = mutableListOf<String>()
            lines += "Port AT dipakai: ${sec["DEV"]?.trim() ?: "?"}"
            lines += "Sebelum: IMEI 1 = ${b1 ?: "kosong/tidak terbaca"}" + if (f2 != null || b2 != null) ", IMEI 2 = ${b2 ?: "kosong/tidak terbaca"}" else ""
            lines += "Tulis IMEI 1: " + if (w1ok) "diterima modem (OK)" else "DITOLAK — jawaban modem: ${snippet(sec["W1"])}"
            if (f2 != null) lines += "Tulis IMEI 2: " + if (w2ok) "diterima modem (OK)" else "DITOLAK — jawaban modem: ${snippet(sec["W2"])}"
            lines += "Dibaca ulang: IMEI 1 = ${a1 ?: "tidak terbaca"}" + if (f2 != null) ", IMEI 2 = ${a2 ?: "tidak terbaca"}" else ""
            val ok = v1 && v2
            if (ok) {
                lines += "Cocok dengan yang ditulis. Restart HP, lalu cek dengan *#06#."
            } else {
                lines += "Hasil baca ulang TIDAK sama dengan yang ditulis, jadi dianggap GAGAL. Jangan percaya angka di layar sebelum restart dan cek *#06#."
                if (!w1ok) lines += "Catatan: modem menjawab perintah AT, tetapi menolak perintah tulis EGMR. Kirim laporan ini apa adanya supaya penyebabnya bisa dipastikan."
            }
            return ImeiWriteReport(ok, if (ok) "IMEI berhasil ditulis" else "IMEI gagal ditulis", lines)
        } finally {
            f1.delete(); f2?.delete()
        }
    }

    private fun noResp(sec: Map<String, String>) = ImeiWriteReport(
        false, "Modem tidak menjawab perintah AT",
        listOf("Port AT ada, tapi tidak ada yang menjawab \"OK\" pada perintah AT biasa, jadi tidak ada yang ditulis.") + probeLines(sec) +
            listOf("Kemungkinan: port dipakai proses lain, diblokir SELinux/ROM, atau modem tidak membuka AT di build ini.")
    )

    private fun noDev() = ImeiWriteReport(
        false, "Port AT modem tidak ditemukan",
        listOf(
            "ROM ini tidak membuka /dev/radio/pttycmd1, atci1, pttycmd2 maupun atci2 untuk root, jadi tidak ada yang ditulis.",
            "Cara ini tidak bisa dipakai di HP ini. Alternatifnya perlu tool resmi seperti Maui META / SN Writer lewat PC."
        )
    )
}
