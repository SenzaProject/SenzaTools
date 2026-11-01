package com.senzaproject.senzatools.platform.root

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.senzaproject.senzatools.platform.AccessManager
import java.io.File
import kotlin.math.roundToInt

/**
 * Kunci refresh rate layar (mis. 60/90/120 Hz) lewat root.
 *
 * Di sebagian perangkat pengaturan refresh rate bawaan tidak bekerja (bug firmware: nilai dikembalikan sendiri,
 * atau pilihan di Settings tidak berpengaruh). Karena itu kunci dipasang di dua lapis:
 *  1. Settings.System min_refresh_rate = peak_refresh_rate = N (jalur yang dibaca DisplayModeDirector AOSP),
 *     plus user_refresh_rate bila kunci itu ada (Xiaomi dan sebagian ROM lain);
 *  2. skrip penjaga (assets/senza_hz.sh) yang memeriksa dan menulis ulang tiap 10 detik bila firmware menimpanya.
 * Hasil tidak diasumsikan berhasil: refresh rate yang dilaporkan layar dibaca ulang dan dibandingkan.
 * Nilai awal disimpan sehingga "Lepas kunci" mengembalikan pengaturan sebelumnya.
 */
class DisplayTuner(private val ctx: Context, private val access: AccessManager) {

    data class Outcome(val failures: List<String>, val raw: String)

    companion object {
        private const val SCRIPT = "/data/local/tmp/senza_hz.sh"
        private const val MODULE_DIR = "/data/adb/modules/senza_display"
        private val VALUE = Regex("^(null|[0-9]{1,3}(\\.[0-9]{1,3})?)$")
        private val KEYS = listOf("min_refresh_rate", "peak_refresh_rate", "user_refresh_rate")
        val RATES = listOf(60, 90, 120)
    }

    private val prefs = ctx.getSharedPreferences("senza_display", Context.MODE_PRIVATE)

    /** 0 = tidak dikunci. */
    var lockedHz: Int
        get() = prefs.getInt("locked_hz", 0)
        private set(v) { prefs.edit().putInt("locked_hz", v).apply() }

    private fun display(): Display? = try {
        ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
    } catch (_: Throwable) {
        null
    }

    /** Refresh rate mode layar yang sedang aktif menurut sistem (nilai yang sama dilihat aplikasi lain). */
    fun currentHz(): Float? = display()?.refreshRate?.takeIf { it > 0f }

    /** Refresh rate yang dilaporkan didukung oleh mode-mode layar. */
    fun supportedHz(): List<Int> = try {
        display()?.supportedModes?.map { it.refreshRate.roundToInt() }?.distinct()?.sorted().orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun buildScript(hz: Int, boot: Boolean, loop: Boolean): String {
        require(hz in 30..240) { "Refresh rate tidak valid: $hz" }
        val tpl = ctx.assets.open("senza_hz.sh").bufferedReader().use { it.readText() }
        val wait = if (boot) "while [ \"\$(getprop sys.boot_completed)\" != \"1\" ]; do sleep 2; done\nsleep 10" else ""
        return tpl.replace("#BOOTWAIT#", wait)
            .replace("#HZ#", hz.toString())
            .replace("#DEFMODE#", if (loop) "loop" else "once")
    }

    /** Simpan nilai awal Settings.System sekali (sebelum kunci pertama), untuk dipulihkan saat dilepas. */
    private suspend fun savePrevious() {
        if (prefs.getBoolean("has_prev", false)) return
        val cmd = KEYS.joinToString("; ") { "echo \"$it=\$(settings get system $it)\"" }
        val r = access.runRootEdit(cmd, "baca refresh rate awal")
        if (!r.ok) return
        val e = prefs.edit()
        r.output.lines().forEach { l ->
            val i = l.indexOf('=')
            if (i > 0) {
                val k = l.substring(0, i).trim()
                val v = l.substring(i + 1).trim()
                if (k in KEYS && VALUE.matches(v)) e.putString("prev_$k", v)
            }
        }
        e.putBoolean("has_prev", true).apply()
    }

    /** Kunci ke [hz]: terapkan sekali (hasil diperiksa), lalu nyalakan penjaga. */
    suspend fun lock(hz: Int): Outcome {
        savePrevious()
        val tmp = File(ctx.cacheDir, "senza_hz.sh.tmp")
        tmp.writeText(buildScript(hz, boot = false, loop = false))
        val cmd = "[ -f '$SCRIPT' ] && sh '$SCRIPT' stop; cat '${tmp.absolutePath}' > '$SCRIPT' && chmod 755 '$SCRIPT' && " +
            "sh '$SCRIPT' once; ( nohup sh '$SCRIPT' loop >/dev/null 2>&1 </dev/null & )"
        val r = access.runRootEdit(cmd, "kunci refresh rate $hz Hz", 30_000)
        tmp.delete()
        val fails = r.output.lines().filter { it.startsWith("GAGAL") }.distinct()
        lockedHz = hz
        // Bila profil boot sudah terpasang, perbarui supaya memakai nilai baru.
        if (bootInstalled()) installBoot()
        return Outcome(fails, r.output.trim())
    }

    /** Hentikan penjaga, kembalikan pengaturan awal, dan hapus profil boot. */
    suspend fun unlock(): Outcome {
        val restore = KEYS.joinToString("; ") { k ->
            val prev = prefs.getString("prev_$k", null)
            if (prev == null || prev == "null") "settings delete system $k"
            else {
                require(VALUE.matches(prev))
                "settings put system $k $prev"
            }
        }
        val r = access.runRootEdit("[ -f '$SCRIPT' ] && sh '$SCRIPT' stop; $restore; rm -f '$SCRIPT'; true", "lepas kunci refresh rate", 30_000)
        removeBoot()
        lockedHz = 0
        prefs.edit().putBoolean("has_prev", false).apply()
        return Outcome(if (r.ok) emptyList() else listOf(r.output.trim().take(120)), r.output.trim())
    }

    suspend fun guardRunning(): Boolean =
        access.runRootEdit("[ -f '$SCRIPT' ] && sh '$SCRIPT' status", "cek penjaga refresh rate").output.contains("ON")

    suspend fun bootInstalled(): Boolean = access.runRootEdit("test -f '$MODULE_DIR/service.sh'", "cek profil boot refresh rate").ok

    /** Pasang skrip boot (module systemless terpisah) supaya kunci aktif lagi setelah restart. */
    suspend fun installBoot(): String? {
        val hz = lockedHz
        if (hz == 0) return "Belum ada kunci refresh rate. Pilih 60/90/120 Hz dulu."
        val tmp = File(ctx.cacheDir, "senza_hz_service.sh.tmp")
        tmp.writeText(buildScript(hz, boot = true, loop = true))
        val prop = File(ctx.cacheDir, "senza_hz_module.prop.tmp")
        prop.writeText("id=senza_display\nname=Senza Tools Refresh Rate\nversion=1.0\nversionCode=1\nauthor=SenzaProject\ndescription=Kunci refresh rate layar dari Senza Tools\n")
        val r = access.runRootEdit(
            "mkdir -p '$MODULE_DIR' && ([ -f '$MODULE_DIR/module.prop' ] || cat '${prop.absolutePath}' > '$MODULE_DIR/module.prop') && " +
                "rm -f '$MODULE_DIR/disable' && cat '${tmp.absolutePath}' > '$MODULE_DIR/service.sh' && chmod 755 '$MODULE_DIR/service.sh'",
            "pasang kunci refresh rate saat boot"
        )
        tmp.delete(); prop.delete()
        return if (r.ok) null else "Gagal memasang profil boot (butuh Magisk/KernelSU/APatch): ${r.output.trim().take(120)}"
    }

    suspend fun removeBoot(): Boolean = access.runRootEdit("rm -rf '$MODULE_DIR'", "hapus kunci refresh rate saat boot").ok
}
