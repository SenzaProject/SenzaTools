package old
// ===================================================================== SELinux

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
}

object SelinuxAnalyzer {
    private val AVC = Regex("""avc:\s+denied\s+\{\s*([^}]+?)\s*\}\s+for\s+(.*)""")

    fun parse(log: String): List<AvcDenial> {
        val groups = linkedMapOf<String, AvcDenial>()
        log.lineSequence().forEach { line ->
            val m = AVC.find(line) ?: return@forEach
            val perms = m.groupValues[1].trim()
            val rest = m.groupValues[2]
            fun f(n: String) = Regex("""\b$n=("[^"]*"|\S+)""").find(rest)?.groupValues?.get(1)?.trim('"')
            val s = f("scontext") ?: return@forEach
            val t = f("tcontext") ?: return@forEach
            val c = f("tclass") ?: return@forEach
            val comm = f("comm")
            val permissive = f("permissive")?.let { it == "1" }
            val key = "$perms|$s|$t|$c"
            val old = groups[key]
            groups[key] = old?.copy(count = old.count + 1) ?: AvcDenial(perms, s, t, c, comm, permissive, 1)
        }
        return groups.values.sortedByDescending { it.count }
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
