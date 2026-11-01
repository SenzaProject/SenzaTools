package com.senzaproject.senzatools.platform.root

import android.content.Context
import com.senzaproject.senzatools.platform.AccessManager
import java.io.File

/**
 * Pengatur frekuensi CPU/GPU, profil performa, bypass charger, dan driver grafis.
 * Semua penulisan dilakukan lewat root dan hanya ke path/nilai yang divalidasi.
 * Perubahan bersifat runtime (hilang saat reboot) kecuali "Terapkan saat boot" diaktifkan.
 * Frekuensi diterapkan lewat skrip root (assets/senza_lock.sh). Selama "Penjaga frekuensi" aktif, skrip itu
 * berjalan di latar dan menulis ulang nilai target tiap beberapa detik, sehingga frekuensi tidak turun/pindah
 * sendiri akibat governor, PPM, atau HAL performa vendor.
 * Dukungan node sysfs berbeda-beda per SoC/kernel; hasil selalu dilaporkan, tidak diasumsikan berhasil.
 */
class PerfTuner(private val ctx: Context, private val access: AccessManager) {

    data class CpuPolicy(
        val path: String, val cpus: String, val freqs: List<Long>, val curMin: Long, val curMax: Long,
        val govs: List<String>, val gov: String,
        val cur: Long? = null, val idx: Int = 0
    ) {
        val name: String get() = "policy" + path.substringAfterLast("policy")
    }

    /** kind: DEVFREQ (min/max), MTK (kunci frekuensi via /proc/gpufreq). div = pembagi ke MHz. */
    data class GpuInfo(
        val kind: String, val base: String, val freqs: List<Long>, val curMin: Long?, val curMax: Long?,
        val govs: List<String>, val gov: String?, val div: Long, val cur: Long? = null
    )

    data class BypassNode(val path: String, val on: String, val off: String, val label: String)

    data class Profile(
        val name: String, val cpuMin: Double, val cpuMax: Double, val cpuGov: List<String>,
        val gpuMin: Double, val gpuMax: Double, val gpuLock: Double?, val bypass: Boolean,
        val enforce: Boolean = true
    )

    data class Outcome(val commands: List<String>, val failures: List<String>)

    companion object {
        private val SYS_PATH = Regex("^/(sys|proc)/[A-Za-z0-9_./:@+,=-]+$")
        private val VALUE = Regex("^[A-Za-z0-9_.:-]+$")
        private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        private const val MODULE_DIR = "/data/adb/modules/senza_edit"
        private const val SCRIPT = "/data/local/tmp/senza_lock.sh"

        private fun keyOf(line: String): String {
            val t = line.trim().split(' ')
            return if (t[0] == "rng" || t[0] == "drg" || t[0] == "ppm") t.take(2).joinToString(" ") else t[0]
        }

        val GAME = Profile("Mode Game", 0.5, 1.0, listOf("performance", "schedutil", "interactive", "ondemand"), 0.5, 1.0, 1.0, true)
        val MAX = Profile("Mode Maksimal", 1.0, 1.0, listOf("performance", "schedutil", "interactive", "ondemand"), 1.0, 1.0, 1.0, false)
        val BALANCED = Profile("Mode Seimbang", 0.0, 1.0, listOf("schedutil", "interactive", "ondemand"), 0.0, 1.0, null, false, enforce = false)
        val SAVER = Profile("Mode Hemat Daya", 0.0, 0.6, listOf("schedutil", "conservative", "interactive"), 0.0, 0.5, 0.5, false)

        val BYPASS_CANDIDATES = listOf(
            BypassNode("/sys/class/power_supply/battery/input_suspend", "1", "0", "input_suspend"),
            BypassNode("/sys/class/power_supply/battery/charging_enabled", "0", "1", "charging_enabled"),
            BypassNode("/sys/class/power_supply/battery/battery_charging_enabled", "0", "1", "battery_charging_enabled"),
            BypassNode("/sys/class/power_supply/battery/batt_slate_mode", "1", "0", "batt_slate_mode")
        )

        private fun pick(list: List<Long>, frac: Double): Long {
            if (list.isEmpty()) return 0
            val i = Math.round(frac.coerceIn(0.0, 1.0) * (list.size - 1)).toInt()
            return list[i]
        }
    }

    private val prefs = ctx.getSharedPreferences("senza_perf", Context.MODE_PRIVATE)

    var customNode: BypassNode?
        get() {
            val p = prefs.getString("bp_path", null) ?: return null
            return BypassNode(p, prefs.getString("bp_on", "1") ?: "1", prefs.getString("bp_off", "0") ?: "0", "kustom")
        }
        set(v) {
            prefs.edit().apply {
                if (v == null) remove("bp_path") else {
                    putString("bp_path", v.path); putString("bp_on", v.on); putString("bp_off", v.off)
                }
            }.apply()
        }

    var lastProfile: String
        get() = prefs.getString("last_profile", "") ?: ""
        set(v) { prefs.edit().putString("last_profile", v).apply() }

    /** Target yang sedang dijaga: satu baris perintah skrip per target (kunci = nama fungsi + path). */
    private val targets = linkedMapOf<String, String>().also { m ->
        prefs.getString("targets", "").orEmpty().lineSequence().filter { it.isNotBlank() }.forEach { m[keyOf(it)] = it.trim() }
    }

    /** Penjaga frekuensi: bila aktif, target ditulis ulang terus supaya tidak turun/pindah sendiri. */
    var guardOn: Boolean
        get() = prefs.getBoolean("guard", true)
        set(v) { prefs.edit().putBoolean("guard", v).apply() }

    var ignoreThermal: Boolean
        get() = prefs.getBoolean("ignore_thermal", false)
        set(v) { prefs.edit().putBoolean("ignore_thermal", v).apply() }

    private fun chk(path: String): String {
        require(SYS_PATH.matches(path) && !path.contains("..")) { "Path tidak valid: $path" }
        return path
    }

    private fun setTarget(line: String) { targets[keyOf(line)] = line }

    private fun w(path: String, v: String): String {
        require(SYS_PATH.matches(path) && !path.contains("..")) { "Path tidak valid: $path" }
        require(VALUE.matches(v)) { "Nilai tidak valid: $v" }
        return "echo $v > '$path' || echo \"GAGAL: $path\""
    }

    private fun kv(text: String): List<MutableMap<String, String>> {
        val res = mutableListOf<MutableMap<String, String>>()
        text.lineSequence().forEach { l ->
            val i = l.indexOf('=')
            if (i <= 0) return@forEach
            val k = l.substring(0, i).trim()
            val v = l.substring(i + 1).trim()
            if (k == "P" || k == "G" || k == "M") res.add(mutableMapOf(k to v)) else res.lastOrNull()?.put(k, v)
        }
        return res
    }

    private fun nums(s: String?) = s.orEmpty().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }.sorted()

    // ------------------------------------------------------------------ CPU

    suspend fun readCpu(asRoot: Boolean): List<CpuPolicy> {
        val cmd = "for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d \"\$p\" ] || continue; echo \"P=\$p\"; " +
            "for f in related_cpus scaling_available_frequencies scaling_min_freq scaling_max_freq cpuinfo_min_freq cpuinfo_max_freq scaling_available_governors scaling_governor scaling_cur_freq; do " +
            "echo \"\$f=\$(cat \"\$p/\$f\" 2>/dev/null)\"; done; done"
        val out = access.run(SafeAction.ReadScript(cmd, "Baca cpufreq"), asRoot).output
        val parsed = kv(out).filter { it.containsKey("P") }.mapNotNull { m ->
            val minHw = m["cpuinfo_min_freq"]?.toLongOrNull()
            val maxHw = m["cpuinfo_max_freq"]?.toLongOrNull()
            var freqs = nums(m["scaling_available_frequencies"])
            if (freqs.isEmpty() && minHw != null && maxHw != null) freqs = listOf(minHw, maxHw).distinct()
            val cmin = m["scaling_min_freq"]?.toLongOrNull()
            val cmax = m["scaling_max_freq"]?.toLongOrNull()
            if (freqs.isEmpty() || cmin == null || cmax == null) null
            else CpuPolicy(m["P"]!!, m["related_cpus"].orEmpty(), freqs, cmin, cmax,
                m["scaling_available_governors"].orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }, m["scaling_governor"].orEmpty(),
                m["scaling_cur_freq"]?.toLongOrNull())
        }
        // Urutan cluster (indeks PPM MediaTek) = urutan policy menurut CPU pertamanya.
        return parsed.sortedBy { it.cpus.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: 99 }
            .mapIndexed { i, p -> p.copy(idx = i) }
    }

    private fun cpuTargets(p: CpuPolicy, min: Long, max: Long, gov: String?) {
        val lo = p.freqs.first()
        val hi = p.freqs.last()
        val g = if (gov.isNullOrBlank()) "-" else { require(VALUE.matches(gov)) { "Governor tidak valid: $gov" }; gov }
        setTarget("rng '${chk(p.path)}' $min $max $lo $g")
        setTarget("ppm ${p.idx} ${if (min <= lo) -1 else min} ${if (max >= hi) -1 else max}")
    }

    // ------------------------------------------------------------------ GPU

    suspend fun readGpu(asRoot: Boolean): GpuInfo? {
        val cmd = "for d in /sys/class/devfreq/*; do [ -d \"\$d\" ] || continue; case \"\$(basename \"\$d\")\" in *mali*|*gpu*|*GPU*|*kgsl*) " +
            "echo \"G=\$d\"; for f in available_frequencies min_freq max_freq governor available_governors cur_freq; do echo \"\$f=\$(cat \"\$d/\$f\" 2>/dev/null)\"; done; break;; esac; done; " +
            "if [ -f /proc/gpufreq/gpufreq_opp_dump ]; then echo \"M=/proc/gpufreq\"; echo \"dump=\$(cat /proc/gpufreq/gpufreq_opp_dump 2>/dev/null | tr '\\n' ';')\"; fi"
        val groups = kv(access.run(SafeAction.ReadScript(cmd, "Baca GPU"), asRoot).output)
        groups.firstOrNull { it.containsKey("G") }?.let { m ->
            val freqs = nums(m["available_frequencies"])
            if (freqs.isNotEmpty()) return GpuInfo(
                "DEVFREQ", m["G"]!!, freqs, m["min_freq"]?.toLongOrNull(), m["max_freq"]?.toLongOrNull(),
                m["available_governors"].orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }, m["governor"], 1_000_000L,
                m["cur_freq"]?.toLongOrNull()
            )
        }
        groups.firstOrNull { it.containsKey("M") }?.let { m ->
            val freqs = Regex("freq\\s*=\\s*(\\d+)").findAll(m["dump"].orEmpty()).mapNotNull { it.groupValues[1].toLongOrNull() }.toList().distinct().sorted()
            if (freqs.isNotEmpty()) return GpuInfo("MTK", m["M"]!!, freqs, null, null, emptyList(), null, 1_000L)
        }
        return null
    }

    private fun gpuTargets(g: GpuInfo, min: Long, max: Long, lock: Long?) {
        val lo = g.freqs.first()
        val hi = g.freqs.last()
        if (g.kind == "DEVFREQ") {
            setTarget("drg '${chk(g.base)}' $min $max $lo")
            // Pada MediaTek, GED bisa menimpa devfreq; batasnya (kHz) ikut diset bila tersedia.
            setTarget("ged ${if (min <= lo) 0 else min / 1000} ${if (max >= hi) 0 else max / 1000}")
        } else {
            setTarget("gmtk ${lock ?: 0L}")
        }
    }

    // ------------------------------------------------------------------ Bypass charger

    suspend fun detectBypass(asRoot: Boolean): List<BypassNode> {
        val all = BYPASS_CANDIDATES + listOfNotNull(customNode)
        val cmd = "for f in " + all.joinToString(" ") { "'${it.path}'" } + "; do [ -e \"\$f\" ] && echo \"\$f\"; done"
        val out = access.run(SafeAction.ReadScript(cmd, "Deteksi node bypass charger"), asRoot).output.lines().map { it.trim() }
        return all.filter { it.path in out }.distinctBy { it.path }
    }

    suspend fun setBypass(node: BypassNode, enable: Boolean): Outcome =
        runScript(listOf(w(node.path, if (enable) node.on else node.off)), "bypass charger ${if (enable) "ON" else "OFF"}")

    // ------------------------------------------------------------------ Profil

    suspend fun applyProfile(p: Profile, cpu: List<CpuPolicy>, gpu: GpuInfo?, bypass: BypassNode?): Outcome {
        cpu.forEach { pol ->
            val min = pick(pol.freqs, p.cpuMin)
            val max = maxOf(min, pick(pol.freqs, p.cpuMax))
            cpuTargets(pol, min, max, p.cpuGov.firstOrNull { it in pol.govs })
        }
        if (gpu != null) {
            val min = pick(gpu.freqs, p.gpuMin)
            val max = maxOf(min, pick(gpu.freqs, p.gpuMax))
            gpuTargets(gpu, min, max, p.gpuLock?.let { pick(gpu.freqs, it) })
        }
        val result = commit(p.name, p.enforce)
        lastProfile = p.name
        val bp = if (bypass != null) setBypass(bypass, p.bypass) else null
        return Outcome(result.commands, result.failures + (bp?.failures ?: emptyList()))
    }

    suspend fun setCpu(p: CpuPolicy, min: Long, max: Long, gov: String?): Outcome {
        cpuTargets(p, min, max, gov)
        return commit("CPU ${p.name}", true)
    }

    suspend fun setGpu(g: GpuInfo, min: Long, max: Long, lock: Long?): Outcome {
        gpuTargets(g, min, max, lock)
        return commit("GPU", true)
    }

    suspend fun setThermalIgnore(on: Boolean): Outcome {
        ignoreThermal = on
        setTarget("thermal ${if (on) 1 else 0}")
        return commit("limit thermal GPU ${if (on) "diabaikan" else "normal"}", guardOn)
    }

    // ------------------------------------------------------------------ Penerap & penjaga

    private fun buildScript(boot: Boolean, loop: Boolean): String {
        val tpl = ctx.assets.open("senza_lock.sh").bufferedReader().use { it.readText() }
        val wait = if (boot) "while [ \"\$(getprop sys.boot_completed)\" != \"1\" ]; do sleep 2; done\nsleep 15" else ""
        return tpl.replace("#BOOTWAIT#", wait)
            .replace("#DEFMODE#", if (loop) "loop" else "once")
            .replace("#TARGETS#", targets.values.joinToString("\n"))
    }

    /**
     * Simpan target, pasang skrip, terapkan sekali (hasilnya diperiksa), lalu nyalakan/matikan penjaga.
     * Penjaga lama dihentikan dulu supaya tidak ada dua proses yang saling menimpa.
     */
    private suspend fun commit(label: String, guard: Boolean): Outcome {
        guardOn = guard
        prefs.edit().putString("targets", targets.values.joinToString("\n")).apply()
        val tmp = File(ctx.cacheDir, "senza_lock.sh.tmp")
        tmp.writeText(buildScript(boot = false, loop = false))
        val start = if (guard) "( nohup sh '$SCRIPT' loop >/dev/null 2>&1 </dev/null & )" else "sh '$SCRIPT' stop"
        val cmd = "[ -f '$SCRIPT' ] && sh '$SCRIPT' stop; cat '${tmp.absolutePath}' > '$SCRIPT' && chmod 755 '$SCRIPT' && " +
            "sh '$SCRIPT' once; $start"
        val r = access.runRootEdit(cmd, label, 40_000)
        tmp.delete()
        val fails = r.output.lines().filter { it.startsWith("GAGAL") }
        val extra = if (fails.isEmpty() && r.output.isNotBlank() && !r.ok) listOf(r.output.trim().take(160)) else emptyList()
        return Outcome(targets.values.toList(), fails.distinct() + extra)
    }

    /** True bila proses penjaga frekuensi sedang berjalan. */
    suspend fun guardRunning(): Boolean =
        access.runRootEdit("[ -f '$SCRIPT' ] && sh '$SCRIPT' status", "cek penjaga frekuensi").output.contains("ON")

    /** Nyalakan/matikan penjaga tanpa mengubah target. */
    suspend fun setGuard(on: Boolean): Outcome {
        if (targets.isEmpty()) return Outcome(emptyList(), listOf("Belum ada target. Terapkan profil atau frekuensi dulu."))
        return commit(if (on) "penjaga frekuensi ON" else "penjaga frekuensi OFF", on)
    }

    private suspend fun runScript(cmds: List<String>, label: String): Outcome {
        if (cmds.isEmpty()) return Outcome(emptyList(), emptyList())
        val r = access.runRootEdit(cmds.joinToString("; "), label)
        val fails = r.output.lines().filter { it.startsWith("GAGAL") }
        val extra = if (!r.ok && fails.isEmpty() && r.output.isNotBlank()) listOf(r.output.trim().take(160)) else emptyList()
        return Outcome(cmds, fails + extra)
    }

    // ------------------------------------------------------------------ Boot

    suspend fun bootScriptInstalled(): Boolean = access.runRootEdit("test -f '$MODULE_DIR/service.sh'", "cek profil boot").ok

    /** Simpan pengaturan CPU/GPU terakhir sebagai skrip boot di module systemless (bypass charger tidak ikut). */
    suspend fun installBootScript(): String? {
        if (targets.isEmpty()) return "Belum ada pengaturan untuk disimpan. Terapkan profil atau frekuensi dulu."
        val tmp = File(ctx.cacheDir, "service.sh.tmp")
        tmp.writeText(buildScript(boot = true, loop = guardOn))
        val prop = File(ctx.cacheDir, "module.prop.tmp")
        prop.writeText("id=senza_edit\nname=Senza Tools Edit Overlay\nversion=1.0\nversionCode=1\nauthor=SenzaProject\ndescription=File hasil edit dan profil performa dari Senza Tools\n")
        val r = access.runRootEdit(
            "mkdir -p '$MODULE_DIR' && ([ -f '$MODULE_DIR/module.prop' ] || cat '${prop.absolutePath}' > '$MODULE_DIR/module.prop') && " +
                "rm -f '$MODULE_DIR/disable' && cat '${tmp.absolutePath}' > '$MODULE_DIR/service.sh' && chmod 755 '$MODULE_DIR/service.sh'",
            "pasang profil boot"
        )
        tmp.delete(); prop.delete()
        return if (r.ok) null else "Gagal memasang profil boot (butuh Magisk/KernelSU/APatch): ${r.output.trim().take(120)}"
    }

    suspend fun removeBootScript(): Boolean = access.runRootEdit("rm -f '$MODULE_DIR/service.sh'", "hapus profil boot").ok

    // ------------------------------------------------------------------ Driver grafis

    suspend fun readDriverInfo(asRoot: Boolean): Map<String, String> {
        val keys = listOf("ro.board.platform", "ro.hardware.egl", "ro.hardware.vulkan", "ro.opengles.version", "debug.hwui.renderer", "ro.hwui.use_vulkan")
        val cmd = keys.joinToString("; ") { "echo \"$it=\$(getprop $it)\"" } +
            "; echo \"angle_pkgs=\$(settings get global angle_gl_driver_selection_pkgs 2>/dev/null)\"; echo \"angle_vals=\$(settings get global angle_gl_driver_selection_values 2>/dev/null)\""
        val out = access.run(SafeAction.ReadScript(cmd, "Baca info driver grafis"), asRoot).output
        return out.lines().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
        }.toMap()
    }

    /** value: "skiagl", "skiavk", atau kosong untuk default. Berlaku runtime; aplikasi perlu dibuka ulang. */
    suspend fun setRenderer(value: String): Outcome {
        require(value.isEmpty() || value == "skiagl" || value == "skiavk")
        return runScript(listOf("setprop debug.hwui.renderer '$value' || echo \"GAGAL: debug.hwui.renderer\""), "renderer HWUI ${value.ifEmpty { "default" }}")
    }

    /** Pilih driver GL per aplikasi: "angle" atau "native" (null = hapus pilihan untuk paket itu). */
    suspend fun setAngle(pkg: String, mode: String?, curPkgs: String, curVals: String): Outcome {
        require(PKG.matches(pkg)) { "Nama paket tidak valid" }
        require(mode == null || mode == "angle" || mode == "native")
        val pk = curPkgs.takeIf { it != "null" }.orEmpty().split(',').filter { it.isNotBlank() }.toMutableList()
        val vl = curVals.takeIf { it != "null" }.orEmpty().split(',').filter { it.isNotBlank() }.toMutableList()
        while (vl.size < pk.size) vl.add("native")
        val idx = pk.indexOf(pkg)
        if (idx >= 0) { pk.removeAt(idx); vl.removeAt(idx) }
        if (mode != null) { pk.add(pkg); vl.add(mode) }
        val cmds = if (pk.isEmpty()) listOf(
            "settings delete global angle_gl_driver_selection_pkgs || echo \"GAGAL: angle pkgs\"",
            "settings delete global angle_gl_driver_selection_values || echo \"GAGAL: angle values\""
        ) else listOf(
            "settings put global angle_gl_driver_selection_pkgs '${pk.joinToString(",")}' || echo \"GAGAL: angle pkgs\"",
            "settings put global angle_gl_driver_selection_values '${vl.joinToString(",")}' || echo \"GAGAL: angle values\""
        )
        return runScript(cmds, "driver GL $pkg ${mode ?: "hapus"}")
    }
}
