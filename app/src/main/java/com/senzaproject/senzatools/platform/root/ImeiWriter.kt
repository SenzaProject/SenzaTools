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

/**
 * [ok] = IMEI terbaca ulang dari modem dan sama persis dengan yang ditulis.
 * [uncertain] = perintah sudah dikirim tetapi modem tidak memberi bukti (tidak menjawab / angka belum berubah):
 * bukan sukses, dan bukan gagal pasti. UI menampilkannya dengan warna peringatan.
 */
data class ImeiWriteReport(
    val ok: Boolean,
    val title: String,
    val lines: List<String>,
    val uncertain: Boolean = false
)

/**
 * Tulis / baca IMEI lewat perintah AT ke modem MediaTek (AT+EGMR) dengan akses root.
 *
 * Berjalan di HP tempat aplikasi ini terpasang — BUKAN di HP target yang tersambung lewat BROM.
 * Slot 7 = IMEI 1, slot 10 = IMEI 2. "Berhasil" hanya dilaporkan kalau IMEI yang dibaca ulang dari modem sama persis
 * dengan yang ditulis.
 *
 * Cara kerja port AT (diperbaiki):
 * - Semua port kandidat (/dev/radio/pttycmd1, atci1, pttycmd2, atci2) dites dengan perintah "AT", masing-masing dengan
 *   terminator CR+LF lalu CR. Port pertama yang menjawab "OK" dipakai. Dulu hanya port pertama yang ada yang dicoba,
 *   padahal port itu belum tentu yang menjawab.
 * - Port dibuka SEKALI (baca+tulis lewat satu file descriptor), bukan dibuka terpisah untuk membaca dan menulis.
 * - Tidak bergantung pada perintah "timeout" (tidak ada di semua ROM); batas waktu dihitung di skrip sendiri.
 * - Kalau tidak ada port yang menjawab, perintah tulis tetap dikirim ke port pertama (cara "tulis buta" yang umum dipakai
 *   di MTK), tetapi hasilnya dilaporkan sebagai BELUM TERVERIFIKASI, bukan sukses.
 * - Balasan mentah modem dan info teknis (SELinux, pemilik port, penolakan AVC) ikut ditampilkan di laporan.
 */
class ImeiWriter(private val filesDir: File, private val sh: SystemShell) {

    companion object {
        private val EGMR = Regex("""\+EGMR:\s*"?(\d{15})"?""")

        // Potongan skrip shell. Tanda ^ diganti menjadi $ saat skrip dirakit (supaya Kotlin tidak menganggapnya template string).
        private const val SH_PORTS = """
ERR=/data/local/tmp/senza_at.err
OUT=/data/local/tmp/senza_at.out
: > "^ERR"
CANDS=""
for d in /dev/radio/pttycmd1 /dev/radio/atci1 /dev/radio/pttycmd2 /dev/radio/atci2; do
  [ -c "^d" ] && CANDS="^CANDS ^d"
done
if [ -z "^CANDS" ]; then
  echo "@@NODEV"
  ls /dev/radio 2>/dev/null | head -30
  exit 0
fi
echo "@@CANDS^CANDS"
"""

        private const val SH_LOAD1 = """
I1=^(cat '@F1@' 2>/dev/null)
if [ ^{#I1} -ne 15 ]; then echo "@@BADFILE"; exit 0; fi
case "^I1" in *[!0-9]*) echo "@@BADFILE"; exit 0;; esac
"""

        private const val SH_LOAD2 = """
I2=^(cat '@F2@' 2>/dev/null)
if [ ^{#I2} -ne 15 ]; then echo "@@BADFILE"; exit 0; fi
case "^I2" in *[!0-9]*) echo "@@BADFILE"; exit 0;; esac
"""

        // at PORT TERM PERINTAH [BATAS_DALAM_PERSEPULUH_DETIK]: port dibuka sekali (fd 3, baca+tulis), balasan dikumpulkan
        // sampai muncul OK/ERROR atau batas waktu habis. TERM: rn = CR+LF, r = CR.
        private const val SH_AT = """
at() {
  : > "^OUT"
  (
    exec 3<>"^1" || exit 9
    cat <&3 > "^OUT" &
    CP=^!
    sleep 0.2
    if [ "^2" = rn ]; then printf '%s\r\n' "^3" >&3; else printf '%s\r' "^3" >&3; fi
    i=0
    while [ ^i -lt ^{4:-25} ]; do
      sleep 0.1
      grep -q -E 'OK|ERROR' "^OUT" 2>/dev/null && break
      i=^((i+1))
    done
    sleep 0.15
    kill ^CP 2>/dev/null
    wait ^CP 2>/dev/null
  ) 2>>"^ERR"
  tr -d '\r' < "^OUT"
}
"""

        private const val SH_PROBE = """
USE=""
n=0
for p in ^CANDS; do
  for t in rn r; do
    n=^((n+1))
    R=^(at "^p" "^t" AT 20)
    echo "@@P^n ^p ^t"
    echo "^R"
    case "^R" in *OK*) USE="^p ^t"; break 2;; esac
  done
done
if [ -z "^USE" ]; then
  set -- ^CANDS
  USE="^1 rn"
  echo "@@BLIND ^USE"
fi
P=^{USE% *}
T=^{USE#* }
echo "@@USE ^USE"
"""

        private const val SH_BEFORE = """
echo "@@B1"; at "^P" "^T" 'AT+EGMR=0,7'
echo "@@B2"; at "^P" "^T" 'AT+EGMR=0,10'
"""

        private const val SH_WRITE1 = """
echo "@@W1"; at "^P" "^T" "AT+EGMR=1,7,\"^I1\"" 40
"""

        private const val SH_WRITE2 = """
echo "@@W2"; at "^P" "^T" "AT+EGMR=1,10,\"^I2\"" 40
"""

        private const val SH_AFTER = """
echo "@@A1"; at "^P" "^T" 'AT+EGMR=0,7'
echo "@@A2"; at "^P" "^T" 'AT+EGMR=0,10'
echo "@@DIAG"
echo "SELinux: ^(getenforce 2>/dev/null)"
ls -lZ /dev/radio 2>/dev/null | grep -E 'pttycmd|atci' | head -6
lsof ^P 2>/dev/null | head -4
dmesg 2>/dev/null | grep -E 'avc:.*(pttycmd|atci|radio_device)' | tail -3
cat "^ERR" 2>/dev/null | head -4
rm -f "^OUT" "^ERR"
"""

        private val ALTERNATIF = listOf(
            "Cara lain: kalau HP punya Engineering Mode MTK (*#*#3646633#*#*), cari menu AT Command (biasanya di Telephony), pilih Phone 1, lalu kirim AT+EGMR=1,7,\"IMEI1\" (dan AT+EGMR=1,10,\"IMEI2\" untuk IMEI 2).",
            "Atau pakai tool resmi seperti Maui META / SN Writer lewat PC."
        )

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

        /** Ringkas balasan modem untuk laporan: baris tak kosong digabung; kosong menjadi "tidak ada balasan". */
        fun brief(section: String?): String {
            val t = (section ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | ")
            return if (t.isEmpty()) "tidak ada balasan" else t.take(90)
        }

        private fun answered(section: String?): Boolean = !section.isNullOrBlank()

        /** Hasil tes "AT" per port (bagian P1, P2, …) sebagai baris laporan. */
        private fun probes(sec: Map<String, String>): List<String> {
            val out = mutableListOf<String>()
            for ((k, v) in sec) {
                if (k.length >= 2 && k[0] == 'P' && k.drop(1).all { it.isDigit() }) {
                    val rows = v.lines()
                    val head = (rows.firstOrNull() ?: "").trim()
                    val port = head.substringBefore(' ')
                    val term = if (head.endsWith(" rn")) "CR+LF" else "CR"
                    val reply = brief(rows.drop(1).joinToString("\n"))
                    out += "Tes AT $port ($term): $reply"
                }
            }
            return out
        }

        private fun diag(sec: Map<String, String>): List<String> {
            val d = (sec["DIAG"] ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }.take(10)
            val p = probes(sec)
            if (d.isEmpty() && p.isEmpty()) return emptyList()
            return listOf("Info teknis (kirim ke saya kalau masih gagal):") + p + d
        }

        private fun noDev(detail: String?): ImeiWriteReport {
            val ls = (detail ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }.take(12)
            val lines = mutableListOf(
                "ROM ini tidak membuka /dev/radio/pttycmd1, atci1, pttycmd2 maupun atci2 untuk root, jadi tidak ada yang ditulis.",
                "Cara ini tidak bisa dipakai di HP ini."
            )
            lines += ALTERNATIF
            if (ls.isNotEmpty()) lines += "Isi /dev/radio: " + ls.joinToString(", ")
            return ImeiWriteReport(false, "Port AT modem tidak ditemukan", lines)
        }

        /**
         * Susun laporan dari keluaran skrip tulis. Dipisah dari [write] supaya bisa diuji tanpa root.
         * Tiga hasil: terverifikasi (ok), belum terverifikasi (uncertain), atau gagal.
         */
        fun analyzeWrite(sec: Map<String, String>, imei1: String, imei2: String?): ImeiWriteReport {
            if ("NODEV" in sec) return noDev(sec["NODEV"])
            if ("BADFILE" in sec) {
                return ImeiWriteReport(false, "IMEI gagal ditulis", listOf("Berkas IMEI sementara tidak terbaca oleh root. Tidak ada yang ditulis."))
            }
            val two = !imei2.isNullOrEmpty()
            val b1 = parseEgmr(sec["B1"] ?: "")
            val b2 = parseEgmr(sec["B2"] ?: "")
            val a1 = parseEgmr(sec["A1"] ?: "")
            val a2 = parseEgmr(sec["A2"] ?: "")
            val w1 = sec["W1"] ?: ""
            val w2 = sec["W2"] ?: ""
            val w1ok = "OK" in w1 && "ERROR" !in w1
            val w2ok = !two || ("OK" in w2 && "ERROR" !in w2)
            val v1 = a1 == imei1
            val v2 = !two || a2 == imei2

            val useRaw = (sec["USE"] ?: "").trim()
            val usePort = useRaw.substringBefore(' ')
            val useTerm = if (useRaw.endsWith(" rn")) "CR+LF" else "CR"
            val lines = mutableListOf<String>()
            if ("BLIND" in sec) {
                lines += "Tidak ada port AT yang menjawab tes \"AT\". Perintah tetap dikirim ke $usePort tanpa konfirmasi."
            } else {
                lines += "Port AT: $usePort ($useTerm)"
            }
            lines += "Sebelum: IMEI 1 = ${b1 ?: "kosong/tidak terbaca"}" + if (two || b2 != null) ", IMEI 2 = ${b2 ?: "kosong/tidak terbaca"}" else ""
            lines += "Balasan modem tulis IMEI 1: ${brief(w1)}"
            if (two) lines += "Balasan modem tulis IMEI 2: ${brief(w2)}"
            lines += "Dibaca ulang: IMEI 1 = ${a1 ?: "tidak terbaca"}" + if (two) ", IMEI 2 = ${a2 ?: "tidak terbaca"}" else ""

            if (v1 && v2) {
                lines += "Cocok dengan yang ditulis. Restart HP, lalu cek dengan *#06#."
                return ImeiWriteReport(true, "IMEI berhasil ditulis", lines)
            }

            val silent = !answered(sec["A1"]) && !answered(w1) && (!two || (!answered(sec["A2"]) && !answered(w2)))
            if (silent || (w1ok && w2ok)) {
                if (silent) {
                    lines += "Modem tidak menjawab perintah apa pun lewat port ini, jadi hasilnya tidak bisa dipastikan dari dalam app."
                } else {
                    lines += "Modem menjawab OK untuk perintah tulis, tetapi baca ulang belum menunjukkan angka baru."
                }
                lines += "Restart HP lalu cek dengan *#06#. Kalau IMEI sudah benar, selesai. Kalau masih kosong, pakai cara lain di bawah."
                lines += ALTERNATIF
                lines += diag(sec)
                return ImeiWriteReport(false, "IMEI dikirim, belum terverifikasi", lines, uncertain = true)
            }

            lines += "Modem menolak perintah tulis, jadi IMEI TIDAK berubah. Jangan percaya angka di layar sebelum restart dan cek *#06#."
            lines += ALTERNATIF
            lines += diag(sec)
            return ImeiWriteReport(false, "IMEI gagal ditulis", lines)
        }
    }

    private fun script(f1: File?, f2: File?, write: Boolean): String {
        val sb = StringBuilder()
        sb.append(SH_PORTS)
        if (write && f1 != null) {
            sb.append(SH_LOAD1.replace("@F1@", f1.absolutePath))
            if (f2 != null) sb.append(SH_LOAD2.replace("@F2@", f2.absolutePath))
        }
        sb.append(SH_AT)
        sb.append(SH_PROBE)
        if (write && f1 != null) {
            sb.append(SH_BEFORE)
            sb.append(SH_WRITE1)
            if (f2 != null) sb.append(SH_WRITE2)
            sb.append("sleep 1\n")
        }
        sb.append(SH_AFTER)
        return sb.toString().replace('^', '$')
    }

    /** Baca IMEI yang sekarang dilaporkan modem (tidak menulis apa pun). */
    fun read(): ImeiWriteReport {
        if (!sh.rootVerified) return ImeiWriteReport(false, "Butuh akses root", listOf("Izinkan root dulu di Tools → Akses."))
        val r = sh.root(script(null, null, write = false), "Baca IMEI (AT)", 90_000)
        val sec = sections(r.lines)
        if ("NODEV" in sec) return noDev(sec["NODEV"])
        val a1 = parseEgmr(sec["A1"] ?: "")
        val a2 = parseEgmr(sec["A2"] ?: "")
        val lines = mutableListOf<String>()
        lines += "IMEI 1: ${a1 ?: "tidak terbaca / kosong"}"
        lines += "IMEI 2: ${a2 ?: "tidak terbaca / kosong"}"
        if (a1 == null && a2 == null) {
            lines += "Balasan modem IMEI 1: ${brief(sec["A1"])}"
            lines += "Balasan modem IMEI 2: ${brief(sec["A2"])}"
            lines += diag(sec)
        }
        if (r.timedOut) lines += "Perintah root melewati batas waktu; hasil di atas mungkin belum lengkap."
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
            val r = sh.root(script(f1, f2, write = true), "Tulis IMEI (AT)", 150_000)
            val rep = analyzeWrite(sections(r.lines), imei1, imei2)
            return if (r.timedOut) rep.copy(lines = rep.lines + "Perintah root melewati batas waktu; hasil di atas mungkin belum lengkap.") else rep
        } finally {
            f1.delete(); f2?.delete()
        }
    }
}
