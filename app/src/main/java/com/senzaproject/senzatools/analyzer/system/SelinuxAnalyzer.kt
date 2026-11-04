package com.senzaproject.senzatools.analyzer.system

/**
 * Satu kelompok denial: kombinasi (perms, scontext, tcontext, tclass) yang sama.
 * Semua bidang turunan ditentukan secara deterministik dari ISI log, bukan dari urutan atau jendela baris:
 * - [perms] sudah diurutkan abjad ("{ write read }" dan "{ read write }" adalah denial yang sama).
 * - [comm] = nama proses paling sering muncul (seri: urutan abjad).
 * - [permissive] = true hanya bila SEMUA kejadian yang tahu statusnya bertanda permissive=1; null bila tidak ada yang tahu.
 */
data class AvcDenial(
    val perms: String,
    val scontext: String,
    val tcontext: String,
    val tclass: String,
    val comm: String?,
    val permissive: Boolean?,
    val count: Int
) {
    val sType: String get() = scontext.split(':').getOrNull(2) ?: scontext
    val tType: String get() = tcontext.split(':').getOrNull(2) ?: tcontext
    val key: String get() = "$perms|$scontext|$tcontext|$tclass"
}

object SelinuxAnalyzer {
    private val AVC = Regex("""avc:\s+denied\s+\{\s*([^}]+?)\s*\}\s+for\s+(.*)""")
    private val AUDIT_ID = Regex("""audit\((\d+\.\d+):(\d+)\)""")
    private val F_S = Regex("""\bscontext=(\S+)""")
    private val F_T = Regex("""\btcontext=(\S+)""")
    private val F_C = Regex("""\btclass=(\S+)""")
    private val F_COMM = Regex("""\bcomm=("[^"]*"|\S+)""")
    private val F_PERM = Regex("""\bpermissive=([01])""")

    private class Ev(val id: String?, val key: String, val perms: String, val s: String, val t: String, val c: String, val comm: String?, val permissive: Boolean?)

    private class Acc(val perms: String, val s: String, val t: String, val c: String) {
        var idCount = 0
        val noIdPerSource = HashMap<Int, Int>()
        val comms = HashMap<String, Int>()
        var permYes = 0
        var permNo = 0
    }

    /** Kunci denial untuk satu baris, null bila bukan baris "avc: denied" yang lengkap. */
    private fun event(line: String): Ev? {
        val m = AVC.find(line) ?: return null
        val rest = m.groupValues[2]
        val s = F_S.find(rest)?.groupValues?.get(1) ?: return null
        val t = F_T.find(rest)?.groupValues?.get(1) ?: return null
        val c = F_C.find(rest)?.groupValues?.get(1) ?: return null
        val perms = m.groupValues[1].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.sorted().joinToString(" ")
        val comm = F_COMM.find(rest)?.groupValues?.get(1)?.trim('"')
        val permissive = F_PERM.find(rest)?.groupValues?.get(1)?.let { it == "1" }
        val id = AUDIT_ID.find(line)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
        return Ev(id, "$perms|$s|$t|$c", perms, s, t, c, comm, permissive)
    }

    fun parse(log: String): List<AvcDenial> = parseSources(listOf(log))

    /**
     * Menggabungkan beberapa sumber (mis. dmesg dan logcat) tanpa hitung ganda.
     * Kejadian yang punya id audit ("audit(1699999999.123:456)") dihitung SEKALI di seluruh sumber.
     * Kejadian tanpa id dihitung per sumber lalu diambil yang terbesar (sumber-sumber itu memuat kejadian yang sama).
     * Hasil tidak bergantung pada urutan baris maupun urutan sumber: urut berdasarkan jumlah (besar ke kecil), lalu kunci (abjad).
     */
    fun parseSources(logs: List<String>): List<AvcDenial> {
        val groups = HashMap<String, Acc>()
        val seen = HashSet<String>()
        logs.forEachIndexed { idx, log ->
            log.lineSequence().forEach { line ->
                val e = event(line) ?: return@forEach
                if (e.id != null && !seen.add(e.id)) return@forEach
                val g = groups.getOrPut(e.key) { Acc(e.perms, e.s, e.t, e.c) }
                if (e.id != null) g.idCount++ else g.noIdPerSource.merge(idx, 1, Int::plus)
                e.comm?.let { g.comms.merge(it, 1, Int::plus) }
                when (e.permissive) { true -> g.permYes++; false -> g.permNo++; null -> {} }
            }
        }
        return groups.map { (key, g) ->
            val comm = g.comms.entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()?.key
            val permissive = if (g.permYes + g.permNo == 0) null else g.permNo == 0
            key to AvcDenial(g.perms, g.s, g.t, g.c, comm, permissive, g.idCount + (g.noIdPerSource.values.maxOrNull() ?: 0))
        }.sortedWith(compareBy({ -it.second.count }, { it.first })).map { it.second }
    }

    /** Sidik jari hasil (jumlah dan kunci tiap kelompok) untuk membandingkan dua pemindaian. */
    fun fingerprint(list: List<AvcDenial>): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        list.forEach { md.update("${it.key}#${it.count}#${it.comm}#${it.permissive}\n".toByteArray(Charsets.UTF_8)) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    fun explain(d: AvcDenial): String = buildString {
        append("Domain '${d.sType}'")
        if (d.comm != null) append(" (proses ${d.comm})")
        append(" mencoba { ${d.perms} } pada '${d.tType}' (class ${d.tclass}) dan ditolak ${d.count}×.")
        if (d.permissive == true) append(" Mode permissive: tindakan tetap diizinkan, hanya dicatat.")
    }

    fun suggestion(d: AvcDenial): String =
        "allow ${d.sType} ${d.tType}:${d.tclass} { ${d.perms} };"

    fun caution(d: AvcDenial): String? = when {
        d.sType.startsWith("untrusted_app") || d.sType == "isolated_app" || d.sType == "ephemeral_app" ->
            "Domain aplikasi pihak ketiga: jangan membuka akses lewat policy; perbaiki cara aplikasi mengakses resource."
        d.tType in setOf("kernel", "init", "vold", "system_server") ->
            "Target sensitif: tinjau manual dan cek neverallow sebelum menambah rule apa pun."
        else -> null
    }
}
