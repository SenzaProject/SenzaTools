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
 * Kenapa versi lama gagal di HP MediaTek modern: /dev/radio/pttycmd1 adalah pty yang dibuka TERUS-MENERUS oleh layanan
 * radio (mtkfusionrild). Dua pembaca di satu pty saling berebut data, jadi balasan perintah IMEI kita dimakan rild
 * (dan sebaliknya, "OK" milik rild terbaca seolah balasan kita).
 *
 * Cara kerja sekarang:
 * - Pemegang tiap port dicari (lsof). Port yang TIDAK dipegang proses lain dites lebih dulu dengan perintah "AT"
 *   (CR+LF, lalu CR). Port pertama yang menjawab OK dipakai.
 * - Kalau semua port dipegang layanan radio, layanan itu DIJEDA sebentar (SIGSTOP) supaya kita satu-satunya pembaca,
 *   lalu dilanjutkan lagi (SIGCONT) segera setelah selesai. Dijaga tiga lapis: trap di skrip, langkah "resume" di
 *   [resume] setelah skrip selesai atau timeout, dan restart HP sebagai jalan terakhir. Hanya proses yang namanya
 *   mengandung ril/atci/radio yang dijeda.
 * - Port dibuka SEKALI (baca+tulis lewat satu file descriptor); data lama dibuang sebelum perintah dikirim; tidak
 *   bergantung pada perintah "timeout".
 * - Kalau tidak ada port yang menjawab "AT", TIDAK ada yang ditulis. Tidak ada lagi "tulis buta".
 * - Hanya baris hasil (OK / ERROR / +CME ERROR / +EGMR) yang dianggap balasan; baris lain dari modem (mis. +ECELL) diabaikan.
 */
class ImeiWriter(private val filesDir: File, private val sh: SystemShell) {

    companion object {
        private val EGMR = Regex("""\+EGMR:\s*"?(\d{15})"?""")
        private val RESULT_LINE = Regex("""^(OK|ERROR|AT|\+CM[ES] ERROR|\+EGMR).*""")
        private val RESULT_CODE = Regex("""(?m)^(OK|ERROR|\+CM[ES] ERROR|\+EGMR)""")

        // Potongan skrip shell. Tanda ^ diganti menjadi $ saat skrip dirakit (supaya Kotlin tidak menganggapnya template string).
        private const val SH_PORTS = """
ERR=/data/local/tmp/senza_at.err
OUT=/data/local/tmp/senza_at.out
FZ=/data/local/tmp/senza_at.frozen
: > "^ERR"
: > "^FZ"
trap 'for pid in ^(cat "^FZ" 2>/dev/null); do kill -CONT "^pid" 2>/dev/null; done' EXIT HUP INT TERM
CANDS=""
for d in /dev/radio/atci1 /dev/radio/atci2 /dev/radio/pttycmd1 /dev/radio/pttycmd2; do
  [ -c "^d" ] && CANDS="^CANDS ^d"
done
if [ -z "^CANDS" ]; then
  echo "@@NODEV"
  ls /dev/radio 2>/dev/null | head -30
  exit 0
fi
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

        // at PORT TERM PERINTAH [BATAS_DALAM_PERSEPULUH_DETIK]: port dibuka sekali (fd 3, baca+tulis). Data lama dibuang dulu,
        // lalu balasan dikumpulkan sampai muncul OK/ERROR atau batas waktu habis. TERM: rn = CR+LF, r = CR.
        private const val SH_AT = """
at() {
  : > "^OUT"
  (
    exec 3<>"^1" || exit 9
    cat <&3 > /dev/null &
    DP=^!
    sleep 0.3
    kill ^DP 2>/dev/null
    wait ^DP 2>/dev/null
    cat <&3 > "^OUT" &
    CP=^!
    sleep 0.1
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
diag() {
  echo "@@DIAG"
  echo "SELinux: ^(getenforce 2>/dev/null)"
  dmesg 2>/dev/null | grep -E 'avc:.*(pttycmd|atci|radio_device)' | tail -3
  cat "^ERR" 2>/dev/null | head -4
  rm -f "^OUT" "^ERR"
}
"""

        // Tahap 1: port tanpa pemegang lain dites langsung. Tahap 2: port yang dipegang layanan radio — layanan dijeda dulu.
        private const val SH_PROBE = """
USE=""
n=0
i=0
for p in ^CANDS; do
  HL=^(lsof "^p" 2>/dev/null | grep -v '[C]OMMAND')
  i=^((i+1))
  echo "@@H^i ^p"
  if [ -n "^HL" ]; then echo "^HL" | while read c q r; do echo "^c(^q)"; done; else echo "-"; fi
  if [ -z "^HL" ] && [ -z "^USE" ] && [ ^n -lt 4 ]; then
    for t in rn r; do
      n=^((n+1))
      R=^(at "^p" "^t" AT 20)
      echo "@@P^n ^p ^t"
      echo "^R"
      case "^R" in *OK*) USE="^p ^t"; break;; esac
    done
  fi
done
m=0
if [ -z "^USE" ]; then
  for p in /dev/radio/pttycmd1 /dev/radio/atci1 /dev/radio/pttycmd2 /dev/radio/atci2; do
    [ -c "^p" ] || continue
    HL=^(lsof "^p" 2>/dev/null | grep -v '[C]OMMAND')
    [ -n "^HL" ] || continue
    FR=""
    FN=""
    for pid in ^(echo "^HL" | while read c q r; do echo "^q"; done | sort -u); do
      case "^(cat /proc/^pid/comm 2>/dev/null)" in
        *ril*|*atci*|*radio*) if kill -STOP "^pid" 2>/dev/null; then FR="^FR ^pid"; FN="^FN ^(cat /proc/^pid/comm)(^pid)"; fi;;
      esac
    done
    echo "^FR" > "^FZ"
    [ -n "^FR" ] || continue
    sleep 0.3
    for t in rn r; do
      n=^((n+1))
      R=^(at "^p" "^t" AT 20)
      echo "@@P^n ^p ^t frozen"
      echo "^R"
      case "^R" in *OK*) USE="^p ^t"; break;; esac
    done
    if [ -n "^USE" ]; then echo "@@FROZEN^FN"; break; fi
    for pid in ^FR; do kill -CONT "^pid" 2>/dev/null; done
    : > "^FZ"
    m=^((m+1))
    [ ^m -ge 2 ] && break
  done
fi
if [ -z "^USE" ]; then
  echo "@@NOPORT"
  diag
  exit 0
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
for pid in ^(cat "^FZ" 2>/dev/null); do kill -CONT "^pid" 2>/dev/null; done
: > "^FZ"
diag
"""

        // Jaring pengaman: dijalankan SETELAH skrip utama (selesai, gagal, atau timeout) supaya layanan radio tidak tertinggal terjeda.
        private val SH_RESUME = """
for pid in ^(cat /data/local/tmp/senza_at.frozen 2>/dev/null); do kill -CONT "^pid" 2>/dev/null; done
rm -f /data/local/tmp/senza_at.frozen
""".replace('^', '$')

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

        /**
         * Ringkas balasan modem untuk laporan. Hanya baris hasil (OK / ERROR / +CME ERROR / +EGMR) yang ditampilkan;
         * baris lain (mis. +ECELL milik layanan radio) hanya dihitung. Kosong menjadi "tidak ada balasan".
         */
        fun brief(section: String?): String {
            val all = (section ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
            val keep = all.filter { RESULT_LINE.matches(it) }
            val other = all.size - keep.size
            val body = if (keep.isEmpty()) "tidak ada balasan" else keep.joinToString(" | ").take(90)
            return if (other > 0) "$body (+$other baris lain, bukan jawaban)" else body
        }

        /** True hanya kalau ada kode hasil sungguhan (bukan sekadar data lain seperti +ECELL). */
        private fun answered(section: String?): Boolean = RESULT_CODE.containsMatchIn(section ?: "")

        /** Siapa yang memegang tiap port (bagian H1, H2, …) sebagai baris laporan. */
        private fun holders(sec: Map<String, String>): List<String> {
            val out = mutableListOf<String>()
            for ((k, v) in sec) {
                if (k.length >= 2 && k[0] == 'H' && k.drop(1).all { it.isDigit() }) {
                    val rows = v.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val port = rows.firstOrNull() ?: ""
                    val who = rows.drop(1).filter { it != "-" }
                    out += "Pemegang $port: " + (if (who.isEmpty()) "tidak ada" else who.joinToString(", "))
                }
            }
            return out
        }

        /** Hasil tes "AT" per port (bagian P1, P2, …) sebagai baris laporan. */
        private fun probes(sec: Map<String, String>): List<String> {
            val out = mutableListOf<String>()
            for ((k, v) in sec) {
                if (k.length >= 2 && k[0] == 'P' && k.drop(1).all { it.isDigit() }) {
                    val rows = v.lines()
                    val tk = (rows.firstOrNull() ?: "").trim().split(" ")
                    val port = tk.firstOrNull() ?: ""
                    val term = if (tk.getOrNull(1) == "rn") "CR+LF" else "CR"
                    val frozen = if (tk.getOrNull(2) == "frozen") ", radio dijeda" else ""
                    val reply = brief(rows.drop(1).joinToString("\n"))
                    out += "Tes AT $port ($term$frozen): $reply"
                }
            }
            return out
        }

        private fun diag(sec: Map<String, String>): List<String> {
            val d = (sec["DIAG"] ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }.take(10)
            val h = holders(sec)
            val p = probes(sec)
            if (d.isEmpty() && h.isEmpty() && p.isEmpty()) return emptyList()
            return listOf("Info teknis (kirim ke saya kalau masih gagal):") + h + p + d
        }

        private fun noDev(detail: String?): ImeiWriteReport {
            val ls = (detail ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }.take(12)
            val lines = mutableListOf(
                "ROM ini tidak membuka /dev/radio/atci1, atci2, pttycmd1 maupun pttycmd2 untuk root, jadi tidak ada yang ditulis.",
                "Cara ini tidak bisa dipakai di HP ini."
            )
            lines += ALTERNATIF
            if (ls.isNotEmpty()) lines += "Isi /dev/radio: " + ls.joinToString(", ")
            return ImeiWriteReport(false, "Port AT modem tidak ditemukan", lines)
        }

        private fun noPort(sec: Map<String, String>, title: String): ImeiWriteReport {
            val lines = mutableListOf(
                "Tidak ada port AT yang menjawab tes \"AT\", termasuk setelah layanan radio dijeda sebentar. Tidak ada yang ditulis."
            )
            lines += ALTERNATIF
            lines += diag(sec)
            return ImeiWriteReport(false, title, lines)
        }

        /** Susun laporan baca dari keluaran skrip baca. Dipisah dari [read] supaya bisa diuji tanpa root. */
        fun analyzeRead(sec: Map<String, String>): ImeiWriteReport {
            if ("NODEV" in sec) return noDev(sec["NODEV"])
            if ("NOPORT" in sec) return noPort(sec, "Port AT tidak menjawab")
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
            return ImeiWriteReport(a1 != null || a2 != null, "IMEI yang dilaporkan modem", lines)
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
            if ("NOPORT" in sec) return noPort(sec, "IMEI gagal ditulis")
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
            val fz = (sec["FROZEN"] ?: "").trim()
            val lines = mutableListOf<String>()
            if (fz.isNotEmpty()) {
                lines += "Layanan radio ($fz) dijeda sebentar agar balasan modem bisa dibaca, lalu dilanjutkan."
            }
            lines += "Port AT: $usePort ($useTerm)"
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

            lines += "Modem menolak perintah tulis (lihat balasan di atas), jadi IMEI TIDAK berubah. Jangan percaya angka di layar sebelum restart dan cek *#06#."
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

    /** Pastikan layanan radio tidak tertinggal terjeda, apa pun yang terjadi pada skrip utama. */
    private fun resume() {
        try {
            sh.root(SH_RESUME, "Lanjutkan layanan radio", 15_000)
        } catch (_: Throwable) {
        }
    }

    /** Baca IMEI yang sekarang dilaporkan modem (tidak menulis apa pun). */
    fun read(): ImeiWriteReport {
        if (!sh.rootVerified) return ImeiWriteReport(false, "Butuh akses root", listOf("Izinkan root dulu di Tools → Akses."))
        try {
            val r = sh.root(script(null, null, write = false), "Baca IMEI (AT)", 120_000)
            val rep = analyzeRead(sections(r.lines))
            return if (r.timedOut) rep.copy(lines = rep.lines + "Perintah root melewati batas waktu; hasil di atas mungkin belum lengkap.") else rep
        } finally {
            resume()
        }
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
            resume()
        }
    }
}
