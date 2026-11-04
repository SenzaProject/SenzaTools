import com.senzaproject.senzatools.platform.root.*
import com.senzaproject.senzatools.utils.StreamResult
import com.senzaproject.senzatools.utils.Sys
import java.io.File

/** Shell sungguhan di atas Sys.stream; 'su' dan 'logcat' dst. adalah skrip palsu di PATH. */
class ProcShell(private val dir: String, override var rootVerified: Boolean = true) : SystemShell {
    override fun readFile(path: String): String? = if (path == "/sys/fs/selinux/enforce") runCatching { File("$dir/enforce").readText().trim() }.getOrNull() else null
    override fun app(command: String, timeoutMs: Long) = Sys.stream(listOf("sh", "-c", command), timeoutMs)
    override fun root(command: String, label: String, timeoutMs: Long, tail: Int, keep: (String) -> Boolean) =
        if (!rootVerified) StreamResult(-1, emptyList(), 0, "no root", false, false) else Sys.stream(listOf("su", "-c", command), timeoutMs, tail, keep)
}

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") { if (!ok) failures++; println((if (ok) "  [OK]   " else "  [GAGAL] ") + name + (if (detail.isNotEmpty()) "  -> $detail" else "")) }
fun flags(dir: String, vararg on: String) { File(dir).listFiles { f -> f.name.startsWith("flag_") }?.forEach { it.delete() }; on.forEach { File("$dir/flag_$it").writeText("1") } }
fun field(json: String, key: String): String = Regex("\"$key\": \"?([^\",}\\n]+)").find(json)!!.groupValues[1]

fun main(args: Array<String>) {
    val dir = args[0]; val runs = args.getOrNull(1)?.toInt() ?: 1000
    val exp = File("$dir/expected.json").readText()
    fun expFp(n: String) = Regex("\"$n\": \\{\\s*\"fp\": \"(\\w+)\",\\s*\"n\": (\\d+)").find(exp)!!.groupValues.let { it[1] to it[2].toInt() }
    val sh = ProcShell(dir)
    val engine = LogEngine(sh)
    val sel = SelinuxEngine(sh, sleep = {})

    println("\n=== A. Logcat/Kernel: buffer beku, $runs kali, penanda buffer diacak + stderr ikut berisik ===")
    flags(dir, "markers_random", "stderr_noise")
    for ((name, src, lvl, n) in listOf(
        Quad("LOGCAT_ALL_500", LogSource.LOGCAT, LogLevel.ALL, 500), Quad("LOGCAT_ALL_2000", LogSource.LOGCAT, LogLevel.ALL, 2000),
        Quad("LOGCAT_WARN_1000", LogSource.LOGCAT, LogLevel.WARN, 1000), Quad("LOGCAT_ERROR_200", LogSource.LOGCAT, LogLevel.ERROR, 200),
        Quad("CRASH_200", LogSource.CRASH, LogLevel.ALL, 200), Quad("KERNEL_2000", LogSource.KERNEL, LogLevel.ALL, 2000))) {
        val seen = HashMap<String, Int>(); var allOk = true
        repeat(runs) { val d = engine.read(src, lvl, n); allOk = allOk && d.ok; seen.merge(d.fingerprint, 1, Int::plus) }
        val (efp, en) = expFp(name)
        check("$name: $runs kali -> ${seen.size} hasil berbeda, sama dengan jawaban benar independen ($efp, $en baris)", allOk && seen.size == 1 && seen.keys.first() == efp, "didapat ${seen}")
    }

    println("\n=== B. Logcat: kode LAMA pada buffer beku yang sama (Sys.execute + -t, batas 300.000 karakter) ===")
    flags(dir)
    val lastLine = File("$dir/logcat.txt").readLines().last()
    val oldRes = Sys.execute(listOf("su", "-c", "logcat -d -v threadtime -t 2000"), 30_000)
    val oldLines = oldRes.output.lines().filter { it.isNotBlank() }
    println("  kode lama: ${oldLines.size} baris diterima, 2000 diminta; panjang keluaran ${oldRes.output.length} karakter")
    check("kode lama KEHILANGAN baris terbaru (terpotong di 300.000 karakter)", oldLines.last() != lastLine)
    val nw = engine.read(LogSource.LOGCAT, LogLevel.ALL, 2000)
    check("kode baru: baris paling baru ada & tepat 2000 baris", nw.lines.size == 2000 && nw.lines.last() == lastLine)

    println("\n=== C. dmesg dibatasi kernel (dmesg_restrict) ===")
    flags(dir, "dmesg_restrict")
    val oldK = Sys.execute(listOf("su", "-c", "dmesg 2>&1 | tail -n 2000"), 30_000)
    println("  kode lama menampilkan sebagai 'log': \"${oldK.output.trim()}\" (exit=${oldK.exitCode}, ok=${oldK.ok})")
    check("kode lama menganggap pesan error sebagai isi log (ok=true)", oldK.ok && oldK.output.isNotBlank())
    val newK = engine.read(LogSource.KERNEL, LogLevel.ALL, 2000)
    check("kode baru menolak menampilkan error sebagai log", !newK.ok && newK.lines.isEmpty(), newK.message)

    println("\n=== D. SELinux denial: perangkat 'hidup' (tiap pembacaan log menambah baris baru), $runs kali ===")
    flags(dir, "grow"); File("$dir/grow_n").delete()
    fun oldScan(): String {
        var r = Sys.execute(listOf("su", "-c", "dmesg | tail -n 500"), 30_000)
        var found = old.SelinuxAnalyzer.parse(r.output); var from = "dmesg"
        if (found.isEmpty()) { r = Sys.execute(listOf("su", "-c", "logcat -d -t 2000"), 30_000); found = old.SelinuxAnalyzer.parse(r.output); from = "logcat" }
        return "$from|${found.size} kelompok|${found.sumOf { it.count }} kejadian|top=" + found.take(3).joinToString(",") { "${it.tType}:${it.count}" }
    }
    val oldSeen = LinkedHashMap<String, Int>(); repeat(runs) { oldSeen.merge(oldScan(), 1, Int::plus) }
    println("  KODE LAMA: $runs kali dijalankan -> ${oldSeen.size} hasil berbeda. Contoh:")
    oldSeen.entries.take(4).forEach { println("     ${it.value}x  ${it.key}") }
    check("kode lama memang tidak konsisten (bukti uji ini bisa gagal)", oldSeen.size > 1)

    File("$dir/grow_n").delete()
    val newSeen = HashMap<String, Int>(); var complete = true
    repeat(runs) { val s = sel.scanDenials(); complete = complete && s.complete; newSeen.merge("${s.fingerprint}|${s.denials.size}|${s.totalEvents}", 1, Int::plus) }
    println("  KODE BARU: $runs kali -> ${newSeen.size} hasil berbeda: $newSeen")
    check("kode baru: hasil identik $runs kali walau log terus bertambah", newSeen.size == 1 && complete)
    val s = sel.scanDenials()
    val expAvc = Regex("\\[\\s*\"([^\"]+)\",\\s*(\\d+)\\s*\\]").findAll(exp.substringAfter("\"AVC\"").substringBefore("\"AVC_total\"")).map { it.groupValues[1] to it.groupValues[2].toInt() }.toList()
    val got = s.denials.map { it.key to it.count }
    check("kode baru: ${expAvc.size} kelompok & ${expAvc.sumOf { it.second }} kejadian SAMA PERSIS (urutan dan jumlah) dengan jawaban benar independen", got == expAvc, if (got != expAvc) "got=${got.take(3)} exp=${expAvc.take(3)}" else "")
    println("  rincian sumber: " + s.sources.joinToString(" | ") { "${it.name}: ${it.note}" })

    println("\n=== E. SELinux: cadangan logcat -b all tidak didukung ===")
    flags(dir, "no_ball"); val e2 = sel.scanDenials()
    check("tetap lengkap lewat cadangan tetap", e2.complete && e2.totalEvents == 420, "complete=${e2.complete} total=${e2.totalEvents}")
    flags(dir, "dmesg_restrict"); val e3 = sel.scanDenials()
    check("dmesg gagal dilaporkan, TIDAK disimpulkan 'tidak ada denial'", !e3.complete && e3.sources.first { it.name == "dmesg" }.note.startsWith("gagal") && e3.totalEvents == 420, e3.sources.joinToString { "${it.name}: ${it.note}" })

    println("\n=== F. Mode SELinux: $runs kali, root belum/sudah terverifikasi ===")
    flags(dir); File("$dir/enforce").writeText("1")
    for (rootOn in listOf(false, true)) { sh.rootVerified = rootOn
        val seen = HashSet<String>(); repeat(runs) { val st = sel.status(); seen += "${st.mode}|${st.conflict}" }
        check("root=$rootOn: $runs kali -> $seen", seen == setOf("Enforcing|false")) }
    sh.rootVerified = true
    check("setPermissive(true) terverifikasi", sel.setPermissive(true).ok && sel.status().mode == "Permissive")
    val seenP = HashSet<String>(); repeat(runs) { seenP += sel.status().mode ?: "null" }; check("Permissive stabil $runs kali: $seenP", seenP == setOf("Permissive"))
    check("setPermissive(false) terverifikasi", sel.setPermissive(false).ok && sel.status().mode == "Enforcing")
    flags(dir, "force_enforcing"); val forced = sel.setPermissive(true)
    check("kernel memaksa Enforcing -> dilaporkan gagal, bukan sukses palsu", !forced.ok, forced.message)

    println("\n${if (failures == 0) "SEMUA PEMERIKSAAN LULUS" else "$failures PEMERIKSAAN GAGAL"}")
    System.exit(if (failures == 0) 0 else 1)
}
data class Quad(val a: String, val b: LogSource, val c: LogLevel, val d: Int)
