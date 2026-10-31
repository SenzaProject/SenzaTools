package com.senzaproject.senzatools.data

import com.senzaproject.senzatools.domain.model.InfoRow

/** Satu angka kesehatan baterai beserta asal-usulnya. */
data class HealthSource(val label: String, val percent: Float, val detail: String)

/**
 * Hasil pembacaan kesehatan baterai. Semua angka berasal dari node/API yang benar-benar terbaca;
 * bila tidak ada sumber yang bisa dipakai, [primary] null dan [missing] menjelaskan alasannya (tidak ditebak).
 */
data class BatteryHealthInfo(
    /** Angka utama (sumber paling langsung yang tersedia). */
    val primary: HealthSource?,
    /** Sumber lain yang juga terbaca, untuk dibandingkan dengan angka utama. */
    val others: List<HealthSource>,
    val fullMah: Float?,
    val designMah: Float?,
    val cycles: Int?,
    val cycleSource: String?,
    /** Teks status dari sistem (mis. "Good"), apa adanya dari sysfs. */
    val statusText: String?,
    /** Nilai mentah node/perintah yang dibaca; bisa dicocokkan satu per satu dengan Termux. */
    val evidence: List<InfoRow>,
    val notes: List<String>,
    val missing: String?,
    val viaRoot: Boolean,
    /** Perintah untuk dijalankan di Termux supaya mendapat nilai mentah yang sama. */
    val verifyCommand: String
)

/** Satu power_supply bertipe Battery. Kunci sudah dinormalkan (huruf kecil, tanpa awalan POWER_SUPPLY_). */
data class BatterySupply(val dir: String, val nodes: Map<String, String>) {
    val name: String get() = dir.trimEnd('/').substringAfterLast('/')
}

data class ParsedBattery(
    val supplies: List<BatterySupply>,
    /** Hasil `dumpsys batterystats` (hanya root): kunci "estimated", "last", "min", "max" dalam mAh. */
    val learnedMah: Map<String, Double>,
    val learnedRaw: List<String>
)

/**
 * Pembaca/penghitung kesehatan baterai. Bagian ini murni (tanpa API Android) supaya mudah diuji.
 *
 * Format keluaran skrip [SCRIPT] (satu per baris):
 *   S|<dir>            awal satu power_supply bertipe Battery
 *   N|<node>|<nilai>   isi file node di direktori itu
 *   U|KEY=VALUE        satu baris dari uevent
 *   D|<teks>           baris dari `dumpsys batterystats` (root saja)
 */
object BatteryHealth {

    /** Node yang dibaca satu per satu (selain uevent). Nama-nama ini yang dipakai berbagai kernel. */
    val NODES = listOf(
        "charge_full", "charge_full_design", "charge_counter",
        "energy_full", "energy_full_design",
        "cycle_count", "battery_cycle", "batt_cycle", "bat_cycle",
        "soh", "battery_soh", "batt_soh", "bat_soh",
        "fg_fullcapnom", "fg_fullcaprep",
        "health", "capacity", "status", "technology"
    )

    private val CYCLE_KEYS = listOf("cycle_count", "battery_cycle", "batt_cycle", "bat_cycle")
    private val SOH_KEYS = listOf("soh", "battery_soh", "batt_soh", "bat_soh")

    /** Skrip baca-saja. Dijalankan lewat `sh -c` (Normal) atau `su -c` (root); keduanya membaca file yang sama dengan Termux. */
    val SCRIPT: String = buildString {
        append("for d in /sys/class/power_supply/*; do ")
        append("[ -d \"\$d\" ] || continue; ")
        append("t=\$(cat \"\$d/type\" 2>/dev/null); ")
        append("[ \"\$t\" = \"Battery\" ] || continue; ")
        append("echo \"S|\$d\"; ")
        append("for f in ").append(NODES.joinToString(" ")).append("; do ")
        append("[ -r \"\$d/\$f\" ] && echo \"N|\$f|\$(cat \"\$d/\$f\" 2>/dev/null)\"; ")
        append("done; ")
        append("cat \"\$d/uevent\" 2>/dev/null | sed 's/^/U|/'; ")
        append("done; ")
        append("if [ \"\$(id -u)\" = \"0\" ]; then dumpsys batterystats 2>/dev/null | grep -iE 'battery capacity' | head -n 8 | sed 's/^/D|/'; fi")
    }

    private class Acc(val dir: String, val nodes: MutableMap<String, String> = linkedMapOf())

    private val LEARNED = Regex("(?i)(estimated|last learned|min learned|max learned)\\s+battery capacity:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*mAh")

    fun parse(output: String): ParsedBattery {
        val accs = mutableListOf<Acc>()
        val learned = linkedMapOf<String, Double>()
        val learnedRaw = mutableListOf<String>()
        for (line in output.lineSequence()) {
            val l = line.trimEnd('\r')
            when {
                l.startsWith("S|") -> accs += Acc(l.substring(2).trim())
                l.startsWith("N|") -> {
                    val cur = accs.lastOrNull() ?: continue
                    val rest = l.substring(2)
                    val i = rest.indexOf('|')
                    if (i > 0) {
                        val v = rest.substring(i + 1).trim()
                        if (v.isNotEmpty()) cur.nodes[rest.substring(0, i)] = v
                    }
                }
                l.startsWith("U|") -> {
                    val cur = accs.lastOrNull() ?: continue
                    val kv = l.substring(2)
                    val i = kv.indexOf('=')
                    if (i > 0 && kv.startsWith("POWER_SUPPLY_")) {
                        val k = kv.substring("POWER_SUPPLY_".length, i).lowercase()
                        val v = kv.substring(i + 1).trim()
                        // Node file diutamakan; uevent hanya mengisi yang belum ada.
                        if (v.isNotEmpty() && k !in cur.nodes) cur.nodes[k] = v
                    }
                }
                l.startsWith("D|") -> {
                    val t = l.substring(2).trim()
                    val m = LEARNED.find(t)
                    if (m != null) {
                        learnedRaw += t
                        val key = m.groupValues[1].lowercase().substringBefore(' ')
                        m.groupValues[2].toDoubleOrNull()?.let { learned[key] = it }
                    }
                }
            }
        }
        return ParsedBattery(accs.map { BatterySupply(it.dir, it.nodes) }, learned, learnedRaw)
    }

    /** Nilai kapasitas mentah -> mAh. Kernel melaporkan µAh (>= 100000) atau sudah mAh. Hanya untuk tampilan; persen memakai rasio mentah. */
    private fun toMah(raw: Double): Float = (if (raw >= 100_000.0) raw / 1000.0 else raw).toFloat()

    private fun fmt1(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

    /**
     * @param apiSoh nilai BatteryManager STATE_OF_HEALTH (Android 14+) atau null bila tidak ada.
     * @param apiCycles EXTRA_CYCLE_COUNT (Android 14+) atau null.
     */
    fun compute(p: ParsedBattery, apiSoh: Int?, apiCycles: Int?, viaRoot: Boolean): BatteryHealthInfo {
        val cands = mutableListOf<HealthSource>()
        val notes = mutableListOf<String>()
        val evidence = mutableListOf<InfoRow>()
        var fullMah: Float? = null
        var designMah: Float? = null
        var cycles: Int? = null
        var cycleSource: String? = null
        var statusText: String? = null

        // "battery" diutamakan; supply lain (mis. bms) tetap dihitung sebagai pembanding.
        val ordered = p.supplies.sortedBy { if (it.name == "battery") 0 else 1 }
        var main: BatterySupply? = null

        for (s in ordered) {
            val shown = NODES.filter { it in s.nodes } + s.nodes.keys.filter { it !in NODES && (it.contains("cycle") || it.contains("soh") || it.contains("full")) }
            shown.distinct().forEach { k -> evidence += InfoRow("${s.name}/$k", s.nodes.getValue(k)) }
        }
        p.learnedRaw.forEach { evidence += InfoRow("dumpsys batterystats", it) }

        for (s in ordered) {
            fun num(k: String): Double? = s.nodes[k]?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
            if (statusText == null) statusText = s.nodes["health"]

            // 1) Kapasitas penuh aktual / kapasitas desain (muatan).
            val cf = num("charge_full")
            val cd = num("charge_full_design")
            if (cf != null && cd != null && cf > 0 && cd > 0) {
                val pct = (cf * 100.0 / cd).toFloat()
                if (pct in 10f..150f) {
                    if (main == null) main = s
                    if (fullMah == null) {
                        fullMah = toMah(cf)
                        designMah = toMah(cd)
                    }
                    cands += HealthSource(
                        "${s.name}: charge_full ÷ charge_full_design",
                        pct,
                        "${fmt1(toMah(cf))} mAh dari ${fmt1(toMah(cd))} mAh"
                    )
                    if (cf == cd) notes += "${s.name}: charge_full sama persis dengan charge_full_design. Sebagian kernel mengisi nilai ini secara statis, jadi bandingkan dengan sumber lain di bawah (bila ada)."
                } else {
                    notes += "${s.name}: rasio charge_full/charge_full_design = ${fmt1(pct)} % tidak masuk akal, jadi tidak dipakai."
                }
            }

            // 2) Energi penuh aktual / energi desain.
            val ef = num("energy_full")
            val ed = num("energy_full_design")
            if (ef != null && ed != null && ef > 0 && ed > 0) {
                val pct = (ef * 100.0 / ed).toFloat()
                if (pct in 10f..150f) {
                    if (main == null) main = s
                    cands += HealthSource("${s.name}: energy_full ÷ energy_full_design", pct, "${ef.toLong()} dari ${ed.toLong()} µWh")
                } else {
                    notes += "${s.name}: rasio energy_full/energy_full_design = ${fmt1(pct)} % tidak masuk akal, jadi tidak dipakai."
                }
            }

            // 3) Siklus dari sysfs (supply pertama yang punya).
            if (cycles == null) {
                for (k in CYCLE_KEYS) {
                    val v = num(k)?.toInt()
                    if (v != null && v >= 0) {
                        cycles = v
                        cycleSource = "${s.name}/$k"
                        break
                    }
                }
            }
        }
        if (main == null) main = ordered.firstOrNull()

        // 4) State of Health dari HAL kesehatan (Android 14+).
        if (apiSoh != null && apiSoh in 1..100) {
            cands += HealthSource("BatteryManager STATE_OF_HEALTH", apiSoh.toFloat(), "dilaporkan HAL kesehatan perangkat (Android 14+)")
            evidence += InfoRow("BatteryManager STATE_OF_HEALTH", apiSoh.toString())
        }

        // 5) SOH dari gauge/kernel.
        for (s in p.supplies) {
            for (k in SOH_KEYS) {
                val v = s.nodes[k]?.trim()?.toDoubleOrNull()
                if (v != null && v >= 1.0 && v <= 100.0) {
                    cands += HealthSource("SOH sensor (${s.name}/$k)", v.toFloat(), "dibaca langsung dari node sysfs")
                }
            }
        }

        // 6) Kapasitas hasil pembelajaran sistem (root: dumpsys batterystats).
        val est = p.learnedMah["estimated"]
        val last = p.learnedMah["last"]
        if (est != null && last != null && est > 0 && last > 0) {
            val pct = (last * 100.0 / est).toFloat()
            if (pct in 10f..150f) cands += HealthSource("kapasitas hasil belajar ÷ kapasitas profil", pct, "dumpsys batterystats: ${fmt1(last.toFloat())} dari ${fmt1(est.toFloat())} mAh")
            if (designMah == null) designMah = est.toFloat()
            if (fullMah == null) fullMah = last.toFloat()
        }

        if (cycles == null && apiCycles != null && apiCycles >= 0) {
            cycles = apiCycles
            cycleSource = "BatteryManager EXTRA_CYCLE_COUNT (Android 14+)"
        }
        if (apiCycles != null && apiCycles >= 0) evidence += InfoRow("BatteryManager EXTRA_CYCLE_COUNT", apiCycles.toString())

        val unique = cands.distinctBy { it.label }
        val primary = unique.firstOrNull()
        val missing = if (primary != null) null else buildString {
            append("Perangkat/ROM ini tidak menyediakan data yang cukup untuk menghitung kesehatan baterai")
            append(" (kapasitas desain, SOH, atau kapasitas hasil pembelajaran sistem). ")
            append("Aplikasi tidak menebak angkanya. ")
            if (!viaRoot) append("Aktifkan root agar node sysfs yang dibatasi SELinux dan dumpsys batterystats ikut dibaca.")
        }

        val dir = main?.dir ?: "/sys/class/power_supply/battery"
        val present = main?.nodes?.keys.orEmpty()
        val shellNodes = (listOf("charge_full", "charge_full_design", "energy_full", "energy_full_design") + CYCLE_KEYS + SOH_KEYS + listOf("health", "capacity"))
            .filter { it in present }
        val cmds = mutableListOf<String>()
        if (shellNodes.isNotEmpty()) {
            cmds += "su -c 'cd $dir; for f in ${shellNodes.joinToString(" ")}; do echo \"\$f=\$(cat \$f)\"; done'"
        } else {
            cmds += "su -c 'cat $dir/uevent'"
        }
        if (p.learnedMah.isNotEmpty() || viaRoot) cmds += "su -c 'dumpsys batterystats | grep -i \"battery capacity\"'"

        return BatteryHealthInfo(
            primary = primary,
            others = unique.drop(1),
            fullMah = fullMah,
            designMah = designMah,
            cycles = cycles,
            cycleSource = cycleSource,
            statusText = statusText,
            evidence = evidence,
            notes = notes,
            missing = missing,
            viaRoot = viaRoot,
            verifyCommand = cmds.joinToString("\n")
        )
    }
}
