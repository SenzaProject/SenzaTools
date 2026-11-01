package com.senzaproject.senzatools.kernel

/**
 * Aturan murni (tanpa Android): validasi, pembangun skrip, pengurai keluaran, deteksi SoC, dan evaluasi
 * kompatibilitas. Semua perintah root dirakit DI SINI dari konstanta katalog + nilai yang sudah divalidasi,
 * tidak pernah dari teks bebas.
 */
object KernelRules {

    // ------------------------------------------------------------------ Validasi

    private val PATH_OK = Regex("^/(sys|proc/sys)/[A-Za-z0-9_./@:+,=-]+$")
    private val VALUE_OK = Regex("^[A-Za-z0-9_.,:+-]{1,128}$")

    /** Path harus berada di katalog (whitelist), bentuk aman, tanpa "..". */
    fun checkPath(node: KNode, path: String): String {
        require(path in node.paths) { "Path bukan bagian dari ${node.id}" }
        require(PATH_OK.matches(path) && !path.split('/').contains("..")) { "Path tidak aman: $path" }
        return path
    }

    /** @return pesan galat, atau null bila [value] sah untuk tipe node. */
    fun validateValue(node: KNode, value: String, options: List<String> = emptyList()): String? {
        if (!VALUE_OK.matches(value)) return "Nilai memuat karakter yang tidak diizinkan"
        return when (val t = node.type) {
            is KType.Toggle -> if (value == t.on || value == t.off) null else "Harus ${t.on} atau ${t.off}"
            is KType.Num -> {
                val n = value.toLongOrNull() ?: return "Harus bilangan bulat"
                if (n < t.min || n > t.max) "Di luar rentang ${t.min}–${t.max}" else null
            }
            is KType.Choice -> {
                val allowed = options.ifEmpty { t.fallback }
                if (allowed.isEmpty()) "Daftar pilihan belum terbaca"
                else if (value in allowed) null else "Pilihan tidak tersedia di kernel ini"
            }
            is KType.Csv -> {
                val parts = value.split(',')
                if (parts.size != t.count) return "Harus ${t.count} angka dipisah koma"
                val nums = parts.map { it.toLongOrNull() ?: return "Semua bagian harus angka" }
                if (nums.any { it < t.min || it > t.max }) return "Setiap angka harus ${t.min}–${t.max}"
                if (nums.zipWithNext().any { (a, b) -> a > b }) return "Angka harus naik (kecil ke besar)"
                null
            }
        }
    }

    /**
     * Aturan lintas-node, memakai nilai yang AKAN berlaku (saat ini + perubahan tertunda).
     * @return pesan galat atau null.
     */
    fun crossCheck(effective: Map<String, String>): String? {
        val dirty = effective["vm_dirty_ratio"]?.toLongOrNull()
        val bg = effective["vm_dirty_background_ratio"]?.toLongOrNull()
        if (dirty != null && bg != null && bg > dirty) return "Dirty background ratio ($bg) tidak boleh lebih besar dari dirty ratio ($dirty)"
        val lim = effective["th_msm_limit_temp"]?.toLongOrNull()
        val core = effective["th_msm_core_limit_temp"]?.toLongOrNull()
        if (lim != null && core != null && core < lim) return "Batas suhu core ($core °C) tidak boleh di bawah batas throttle ($lim °C)"
        return null
    }

    /** Urutan aman: tulis penurun dulu lalu penaik, supaya aturan lintas-node tidak dilanggar di tengah jalan. */
    fun orderChanges(changes: List<Change>): List<Change> {
        val rank = mapOf("vm_dirty_background_ratio" to 0, "vm_dirty_ratio" to 1)
        return changes.sortedBy { rank[it.nodeId] ?: 5 }
    }

    // ------------------------------------------------------------------ Normalisasi nilai

    private val BRACKET = Regex("""\[([^\]]+)]""")

    /** "mq-deadline [kyber] none" → opsi [mq-deadline, kyber, none], aktif kyber. Tanpa kurung: aktif = null. */
    fun parseChoice(raw: String): Pair<List<String>, String?> {
        val active = BRACKET.find(raw)?.groupValues?.get(1)
        val opts = raw.replace("[", " ").replace("]", " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return opts to active
    }

    /** Bentuk kanonik nilai terbaca untuk node. */
    fun normalize(node: KNode, raw: String): String {
        val t = raw.trim()
        return when (node.type) {
            is KType.Choice -> parseChoice(t).let { (opts, act) -> act ?: opts.firstOrNull() ?: t }
            is KType.Csv -> t.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.joinToString(",")
            is KType.Toggle -> t.firstOrNull()?.let { c ->
                val tg = node.type as KType.Toggle
                when {
                    t == tg.on || t == tg.off -> t
                    // kernel memetakan Y/N <-> 1/0 untuk parameter bool
                    c == 'Y' || c == 'y' || t == "1" -> tg.on
                    c == 'N' || c == 'n' || t == "0" -> tg.off
                    else -> t
                }
            } ?: t
            else -> t
        }
    }

    /** Apakah [readback] setara [requested] untuk node ini. */
    fun sameValue(node: KNode, requested: String, readback: String): Boolean =
        normalize(node, requested) == normalize(node, readback)

    // ------------------------------------------------------------------ Skrip baca/tulis

    private fun q(s: String) = "'$s'"

    /** Satu skrip baca untuk semua node: keluaran baris "N|path|r|w|isi" dan "A|path|isi" untuk daftar opsi. */
    fun readScript(nodes: List<KNode>): String {
        val paths = nodes.flatMap { it.paths }.distinct()
        val avail = nodes.mapNotNull { (it.type as? KType.Choice)?.availablePath }.distinct()
        paths.forEach { require(PATH_OK.matches(it)) { "Path katalog tidak aman: $it" } }
        avail.forEach { require(PATH_OK.matches(it)) { "Path katalog tidak aman: $it" } }
        val sb = StringBuilder()
        sb.append("for p in ").append(paths.joinToString(" ") { q(it) }).append("; do ")
        sb.append("[ -e \"\$p\" ] || continue; r=0; w=0; [ -r \"\$p\" ] && r=1; [ -w \"\$p\" ] && w=1; ")
        sb.append("v=; [ \"\$r\" = 1 ] && v=\$(head -c 512 \"\$p\" 2>/dev/null | tr '\\n' ' '); ")
        sb.append("printf 'N|%s|%s|%s|%s\\n' \"\$p\" \"\$r\" \"\$w\" \"\$v\"; done; ")
        if (avail.isNotEmpty()) {
            sb.append("for p in ").append(avail.joinToString(" ") { q(it) }).append("; do ")
            sb.append("[ -r \"\$p\" ] && printf 'A|%s|%s\\n' \"\$p\" \"\$(head -c 512 \"\$p\" 2>/dev/null | tr '\\n' ' ')\"; done")
        }
        return sb.toString()
    }

    private data class RawNode(val readable: Boolean, val writable: Boolean, val value: String)

    /** Mengurai keluaran [readScript] menjadi keadaan tiap node. */
    fun parseRead(nodes: List<KNode>, output: String, device: DeviceProfile?): List<NodeState> {
        val raw = HashMap<String, RawNode>()
        val avail = HashMap<String, String>()
        output.lineSequence().forEach { line ->
            val parts = line.split('|', limit = 5)
            when {
                parts.size == 5 && parts[0] == "N" -> raw[parts[1]] = RawNode(parts[2] == "1", parts[3] == "1", parts[4].trim())
                parts.size >= 3 && parts[0] == "A" -> avail[parts[1]] = line.substringAfter("A|${parts[1]}|").trim()
            }
        }
        return nodes.map { n ->
            val hit = n.paths.firstOrNull { it in raw }
            if (hit == null) return@map NodeState(n, Support.Missing(missingReason(n, device)))
            val r = raw.getValue(hit)
            when {
                !r.readable -> NodeState(n, Support.Protected(hit))
                else -> {
                    val value = normalize(n, r.value)
                    val opts = when (val t = n.type) {
                        is KType.Choice -> {
                            val src = t.availablePath?.let { avail[it] } ?: r.value
                            parseChoice(src).first.ifEmpty { t.fallback }
                        }
                        else -> emptyList()
                    }
                    NodeState(n, if (r.writable) Support.Ready(hit) else Support.ReadOnly(hit), value, opts)
                }
            }
        }
    }

    fun missingReason(n: KNode, d: DeviceProfile?): String {
        if (d != null && n.vendors.isNotEmpty() && d.soc != SocVendor.UNKNOWN && d.soc !in n.vendors)
            return "Khusus ${n.vendors.joinToString("/") { it.label }}; perangkat ini ${d.soc.label}"
        return "Kernel perangkat ini tidak menyediakan node ini"
    }

    /** Perintah tulis satu nilai. Mengeluarkan "ERR|alasan" atau "RES|lama|baru". */
    fun writeScript(node: KNode, path: String, value: String): String {
        checkPath(node, path)
        require(VALUE_OK.matches(value)) { "Nilai tidak aman" }
        return "p=${q(path)}; v=${q(value)}; " +
            "[ -w \"\$p\" ] || { echo 'ERR|tidak bisa ditulis'; exit 0; }; " +
            "old=\$(head -c 512 \"\$p\" 2>/dev/null | tr '\\n' ' '); " +
            "if echo \"\$v\" > \"\$p\" 2>/dev/null; then new=\$(head -c 512 \"\$p\" 2>/dev/null | tr '\\n' ' '); " +
            "printf 'RES|%s|%s\\n' \"\$old\" \"\$new\"; else echo 'ERR|kernel menolak nilai'; fi"
    }

    sealed interface ParsedWrite {
        data class Res(val old: String, val new: String) : ParsedWrite
        data class Err(val reason: String) : ParsedWrite
    }

    fun parseWrite(output: String): ParsedWrite {
        val line = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("RES|") || it.startsWith("ERR|") }
            ?: return ParsedWrite.Err("tidak ada respons dari shell root")
        val p = line.split('|', limit = 3)
        return if (p[0] == "RES" && p.size == 3) ParsedWrite.Res(p[1].trim(), p[2].trim()) else ParsedWrite.Err(p.getOrElse(1) { "galat" })
    }

    // ------------------------------------------------------------------ Boot

    const val BOOT_DIR = "/data/adb/senza_kernel"

    /**
     * Skrip boot. Penjaga:
     *  - hanya path /sys|/proc/sys berkarakter aman, nilai berkarakter aman (divalidasi ulang di sisi shell);
     *  - penanda "pending" dibuat sebelum menerapkan dan dihapus setelah 120 dtk. Bila boot berikutnya masih
     *    menemukan "pending", boot sebelumnya tidak stabil → penerapan otomatis dinonaktifkan dan dilewati.
     */
    fun bootScript(): String = """
        |#!/system/bin/sh
        |# Senza Tools - penerapan kernel saat boot. Dibuat otomatis; jangan diedit.
        |D=$BOOT_DIR
        |[ -f "${'$'}D/enabled" ] || exit 0
        |if [ -f "${'$'}D/pending" ]; then
        |  rm -f "${'$'}D/enabled" "${'$'}D/pending"
        |  echo "dilewati: boot sebelumnya tidak stabil, penerapan otomatis dinonaktifkan" > "${'$'}D/last_status"
        |  exit 0
        |fi
        |: > "${'$'}D/pending"
        |n=0
        |while IFS='|' read -r p v; do
        |  case "${'$'}p" in /sys/*|/proc/sys/*) ;; *) continue ;; esac
        |  case "${'$'}p" in *..*|*[!A-Za-z0-9_./:@,+=-]*) continue ;; esac
        |  case "${'$'}v" in ''|*[!A-Za-z0-9_.,:+-]*) continue ;; esac
        |  if [ -w "${'$'}p" ]; then echo "${'$'}v" > "${'$'}p" 2>/dev/null && n=${'$'}((n+1)); fi
        |done < "${'$'}D/values.conf"
        |echo "diterapkan ${'$'}n nilai" > "${'$'}D/last_status"
        |nohup sh -c 'sleep 120; rm -f "${'$'}1"' sh "${'$'}D/pending" >/dev/null 2>&1 </dev/null &
        |exit 0
    """.trimMargin() + "\n"

    /** Baris values.conf. Hanya node bootSafe dengan nilai sah; path divalidasi ulang. */
    fun bootConf(
        entries: List<Pair<KNode, String>>,
        pathOf: (KNode) -> String?,
        optionsOf: (KNode) -> List<String> = { emptyList() }
    ): String = entries.filter { it.first.bootSafe }.mapNotNull { (n, v) ->
        val p = pathOf(n) ?: return@mapNotNull null
        if (validateValue(n, v, optionsOf(n)) != null) null else { checkPath(n, p); "$p|$v" }
    }.joinToString("\n") + "\n"

    // ------------------------------------------------------------------ Deteksi perangkat

    fun detectSoc(manufacturer: String, platform: String, hardware: String): SocVendor {
        val m = manufacturer.lowercase()
        val p = platform.lowercase()
        val h = hardware.lowercase()
        return when {
            "qualcomm" in m || h == "qcom" || p.matches(Regex("^(msm|apq|sdm|sm|qcom|sdx)\\d*.*")) ||
                p in QCOM_CODENAMES -> SocVendor.QUALCOMM
            "mediatek" in m || p.matches(Regex("^mt\\d+.*")) || h.startsWith("mt") -> SocVendor.MEDIATEK
            p.matches(Regex("^gs\\d+.*")) || p in setOf("zuma", "zumapro", "lga", "laguna") -> SocVendor.TENSOR
            "samsung" in m || p.startsWith("exynos") || h.startsWith("exynos") || h.startsWith("samsungexynos") ||
                p.matches(Regex("^universal\\d+.*")) || p.matches(Regex("^s5e\\d+.*")) -> SocVendor.EXYNOS
            "unisoc" in m || "spreadtrum" in m || p.startsWith("ums") || p.startsWith("sprd") || p.matches(Regex("^sc\\d+.*")) -> SocVendor.UNISOC
            else -> SocVendor.UNKNOWN
        }
    }

    private val QCOM_CODENAMES = setOf(
        "kona", "lahaina", "taro", "kalama", "pineapple", "sun", "canoe", "lito", "atoll", "bengal", "holi",
        "trinket", "msmnile", "sdmmagpie", "parrot", "crow", "monaco", "cape", "blair", "volcano", "yupik", "shima"
    )

    /** Keluaran "tipe|suhu" per zona termal → pasangan (tipe, °C) terurut dari terpanas. Suhu >1000 dianggap miliderajat. */
    fun parseThermal(output: String): List<Pair<String, Double>> = output.lineSequence().mapNotNull { l ->
        val i = l.indexOf('|')
        if (i < 0) return@mapNotNull null
        val raw = l.substring(i + 1).trim().toDoubleOrNull() ?: return@mapNotNull null
        val c = if (Math.abs(raw) >= 1000) raw / 1000.0 else raw
        if (c < -40 || c > 150) null else l.substring(0, i).trim().ifEmpty { "zone" } to c
    }.sortedByDescending { it.second }.toList()

    const val THERMAL_SCRIPT = "for z in /sys/class/thermal/thermal_zone*; do [ -r \"\$z/temp\" ] && echo \"\$(cat \"\$z/type\" 2>/dev/null)|\$(cat \"\$z/temp\" 2>/dev/null)\"; done"

    data class Summary(val ready: Int, val readOnly: Int, val protectedNodes: Int, val missing: Int, val total: Int)

    fun summarize(states: List<NodeState>): Summary = Summary(
        states.count { it.support is Support.Ready }, states.count { it.support is Support.ReadOnly },
        states.count { it.support is Support.Protected }, states.count { it.support is Support.Missing }, states.size
    )

    /** Peringatan tingkat perangkat (tampil di kartu kompatibilitas). */
    fun deviceWarnings(d: DeviceProfile, rootOk: Boolean): List<String> = buildList {
        if (!rootOk) add("Tanpa root, sebagian besar node sysfs tidak terbaca dan tidak bisa diubah.")
        if (d.selinux.equals("Enforcing", true)) add("SELinux Enforcing: sebagian node bisa terkunci walau ada root; itu dilaporkan sebagai “Dilindungi”.")
        if (d.kernel != null && d.kernel.major >= 5 && d.kernel.minor >= 4) add("Kernel ${d.kernel}: parameter lawas (LMK in-kernel, hotplug driver vendor) umumnya sudah tidak ada.")
        if (d.soc == SocVendor.UNKNOWN) add("SoC tidak dikenali; deteksi memakai keberadaan node saja.")
    }
}
