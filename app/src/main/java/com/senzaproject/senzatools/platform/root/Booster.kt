package com.senzaproject.senzatools.platform.root

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import com.senzaproject.senzatools.platform.AccessManager
import com.senzaproject.senzatools.utils.Sys
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

/**
 * Fitur booster: Mode Fokus Game, DPI changer, RAM cleaner, penyegar sinyal, dan monitor fast charger.
 * Semua perintah root disusun di sini dari nilai yang divalidasi (bukan teks bebas) dan dicatat di log.
 */
class Booster(private val ctx: Context, private val access: AccessManager) {

    data class AppItem(val pkg: String, val label: String, val system: Boolean)
    data class Mem(val totalMb: Long, val availMb: Long)
    data class Dpi(val physical: Int?, val current: Int?)
    data class WifiSig(val rssi: Int, val freqMhz: Int, val linkMbps: Int)
    data class Res(val ok: Boolean, val message: String)
    data class Charge(
        val plugged: String, val status: String, val level: Int, val tempC: Double?,
        val mA: Double?, val volts: Double?, val watts: Double?,
        val chargeType: String?, val usbType: String?, val maxWatts: Double?,
        val extra: List<Pair<String, String>>
    )

    companion object {
        private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        private const val PS = "ps -A -o PID,NAME"
        private const val SUPPLY = "/sys/class/power_supply"
    }

    private val prefs = ctx.getSharedPreferences("senza_boost", Context.MODE_PRIVATE)

    var gameModeOn: Boolean
        get() = prefs.getBoolean("game_on", false)
        set(v) { prefs.edit().putBoolean("game_on", v).apply() }
    var gamePkg: String
        get() = prefs.getString("game_pkg", "") ?: ""
        set(v) { prefs.edit().putString("game_pkg", v).apply() }
    var keepList: String
        get() = prefs.getString("keep", "") ?: ""
        set(v) { prefs.edit().putString("keep", v).apply() }
    var hardMode: Boolean
        get() = prefs.getBoolean("hard", false)
        set(v) { prefs.edit().putBoolean("hard", v).apply() }

    // ------------------------------------------------------------------ Daftar aplikasi

    fun listApps(): List<AppItem> {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, 0).mapNotNull { ri ->
            val ai = ri.activityInfo.applicationInfo
            if (ai.packageName == ctx.packageName) null
            else {
                val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                AppItem(ai.packageName, ri.loadLabel(pm).toString(), sys)
            }
        }.distinctBy { it.pkg }.sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
    }

    private suspend fun thirdParty(): List<String> =
        access.runRootEdit("pm list packages -3 | cut -d: -f2", "daftar aplikasi pihak ketiga").output
            .lines().map { it.trim() }.filter { PKG.matches(it) }

    /** Paket yang tidak boleh disentuh: launcher, keyboard, aplikasi ini, SystemUI, dan pengecualian pengguna. */
    private suspend fun protectedPkgs(keep: List<String>): Set<String> {
        val r = access.runRootEdit(
            "echo H=\$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -n 1 | cut -d/ -f1); " +
                "echo I=\$(settings get secure default_input_method 2>/dev/null | cut -d/ -f1)",
            "baca launcher dan keyboard"
        )
        val found = r.output.lines().mapNotNull { l -> l.substringAfter('=', "").trim().takeIf { PKG.matches(it) } }
        return (keep + found + ctx.packageName + listOf("com.android.systemui", "com.android.phone")).toSet()
    }

    private fun signalCmd(pkgs: List<String>, sig: String): String {
        if (pkgs.isEmpty()) return "echo 0"
        val pat = pkgs.joinToString("|") { "$it|$it:*" }
        return "$PS | while read pid name; do case \"\$name\" in $pat) kill -$sig \$pid 2>/dev/null && echo x;; esac; done | wc -l"
    }

    // ------------------------------------------------------------------ Mode Fokus Game

    suspend fun enterGameMode(game: String, keep: List<String>, hard: Boolean): Res {
        if (!access.isRootVerified) return Res(false, "Butuh akses root yang sudah diverifikasi.")
        if (!PKG.matches(game)) return Res(false, "Pilih game dulu.")
        if (keep.any { !PKG.matches(it) }) return Res(false, "Ada nama paket pengecualian yang tidak valid.")
        val skip = protectedPkgs(keep + game)
        val targets = thirdParty().filter { it !in skip }
        if (targets.isEmpty()) return Res(true, "Tidak ada aplikasi lain yang perlu dibekukan.")

        val note: String
        if (hard) {
            val cmd = targets.joinToString("; ") { "am force-stop '$it'; cmd appops set '$it' RUN_ANY_IN_BACKGROUND ignore" }
            access.runRootEdit(cmd, "mode game: hentikan + blokir latar belakang", 60_000)
            note = "${targets.size} aplikasi dihentikan dan dilarang jalan di latar belakang."
        } else {
            val r = access.runRootEdit(signalCmd(targets, "STOP"), "mode game: bekukan proses", 30_000)
            note = "${r.output.trim().toIntOrNull() ?: 0} proses dari ${targets.size} aplikasi dibekukan."
        }
        gameModeOn = true; gamePkg = game; keepList = keep.joinToString(","); hardMode = hard
        return Res(true, note)
    }

    suspend fun exitGameMode(): Res {
        if (!access.isRootVerified) return Res(false, "Butuh akses root yang sudah diverifikasi.")
        val all = thirdParty()
        val cmd = signalCmd(all, "CONT") + "; " +
            all.joinToString("; ") { "cmd appops set '$it' RUN_ANY_IN_BACKGROUND default" }.ifEmpty { "true" }
        access.runRootEdit(cmd, "mode game: pulihkan semua aplikasi", 60_000)
        gameModeOn = false
        return Res(true, "Semua aplikasi dipulihkan (${all.size} paket diperiksa).")
    }

    // ------------------------------------------------------------------ RAM cleaner

    fun readMem(): Mem? {
        val t = Sys.readText("/proc/meminfo") ?: return null
        fun kb(k: String) = Regex("^$k:\\s+(\\d+)", RegexOption.MULTILINE).find(t)?.groupValues?.get(1)?.toLongOrNull()
        val total = kb("MemTotal") ?: return null
        val avail = kb("MemAvailable") ?: kb("MemFree") ?: return null
        return Mem(total / 1024, avail / 1024)
    }

    suspend fun cleanRam(aggressive: Boolean, keep: List<String>): Res {
        val before = readMem()
        if (access.isRootVerified) {
            val cmds = mutableListOf("am kill-all", "sync", "echo 3 > /proc/sys/vm/drop_caches", "echo 1 > /proc/sys/vm/compact_memory")
            if (aggressive && keep.all { PKG.matches(it) }) {
                val skip = protectedPkgs(keep)
                thirdParty().filter { it !in skip }.forEach { cmds += "am force-stop '$it'" }
            }
            access.runRootEdit(cmds.joinToString("; ") { "$it 2>/dev/null" }, "RAM cleaner", 60_000)
        } else {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            listApps().filter { !it.system }.forEach { try { am.killBackgroundProcesses(it.pkg) } catch (_: Throwable) {} }
        }
        delay(1500)
        val after = readMem()
        return if (before != null && after != null) {
            val d = after.availMb - before.availMb
            Res(true, "RAM kosong: ${before.availMb} MB -> ${after.availMb} MB (${if (d >= 0) "+" else ""}$d MB)." +
                if (!access.isRootVerified) " Tanpa root hanya proses latar yang diizinkan sistem yang bisa dihentikan." else "")
        } else Res(true, "Pembersihan dijalankan.")
    }

    // ------------------------------------------------------------------ DPI

    suspend fun readDpi(): Dpi {
        val out = (if (access.isRootVerified) access.runRootEdit("wm density", "baca dpi")
        else Sys.execute(listOf("sh", "-c", "wm density"), 5000)).output
        val phys = Regex("Physical density:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
            ?: Sys.prop("ro.sf.lcd_density").toIntOrNull()
        val ovr = Regex("Override density:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        return Dpi(phys, ovr ?: ctx.resources.displayMetrics.densityDpi)
    }

    suspend fun setDpi(v: Int): Res {
        if (!access.isRootVerified) return Res(false, "Butuh akses root yang sudah diverifikasi.")
        if (v !in 160..800) return Res(false, "DPI harus antara 160 dan 800.")
        val r = access.runRootEdit("wm density $v", "ubah DPI ke $v")
        return if (r.ok) Res(true, "DPI diubah ke $v.") else Res(false, "Gagal: ${r.output.trim().take(120)}")
    }

    suspend fun resetDpi(): Res {
        if (!access.isRootVerified) return Res(false, "Butuh akses root yang sudah diverifikasi.")
        val r = access.runRootEdit("wm density reset", "reset DPI")
        return if (r.ok) Res(true, "DPI dikembalikan ke bawaan.") else Res(false, "Gagal: ${r.output.trim().take(120)}")
    }

    // ------------------------------------------------------------------ Sinyal

    @Suppress("DEPRECATION")
    fun wifi(): WifiSig? {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val i = wm.connectionInfo ?: return null
            if (i.rssi <= -127) null else WifiSig(i.rssi, i.frequency, i.linkSpeed)
        } catch (_: Throwable) {
            null
        }
    }

    /** Kekuatan seluler (dBm) dari dumpsys telephony.registry; hanya dengan root. */
    suspend fun cellDbm(): Int? {
        if (!access.isRootVerified) return null
        val out = access.runRootEdit("dumpsys telephony.registry | grep -i 'signalstrength'", "baca sinyal seluler").output
        val vals = Regex("(?:rsrp|rssi|dbm)=(-\\d+)").findAll(out).mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in -140..-40 }.toList()
        return vals.firstOrNull()
    }

    suspend fun refreshWifi(): Res {
        if (!access.isRootVerified) return Res(false, "Butuh akses root yang sudah diverifikasi.")
        access.runRootEdit("svc wifi disable; sleep 2; svc wifi enable", "segarkan Wi-Fi", 20_000)
        return Res(true, "Wi-Fi dimatikan lalu dinyalakan lagi. Tunggu beberapa detik sampai tersambung.")
    }

    suspend fun refreshCellular(): Res {
        if (!access.isRootVerified) return Res(false, "Butuh akses root yang sudah diverifikasi.")
        access.runRootEdit("cmd connectivity airplane-mode enable; sleep 3; cmd connectivity airplane-mode disable", "segarkan jaringan seluler", 25_000)
        return Res(true, "Mode pesawat dinyalakan 3 detik lalu dimatikan agar HP memilih menara terbaik.")
    }

    // ------------------------------------------------------------------ Fast charger

    private fun rd(path: String): String? = Sys.readText(path)?.takeIf { it.isNotBlank() }

    /** Dibaca langsung dari file (tanpa shell) supaya aman dipanggil berkala tanpa memenuhi log. */
    fun readCharge(): Charge {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = when (st?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Charger (AC)"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Tidak tercolok"
        }
        val status = when (st?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Mengisi"
            BatteryManager.BATTERY_STATUS_FULL -> "Penuh"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Tidak mengisi"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Ditahan"
            else -> "?"
        }
        val scale = st?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val level = (st?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0) * 100 / scale
        val temp = st?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        val volts = st?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }?.let { it / 1000.0 }

        val curRaw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val mA = if (curRaw == Long.MIN_VALUE || curRaw == 0L) null else abs(curRaw.toDouble()).let { if (it >= 20000) it / 1000.0 else it }
        val charging = status == "Mengisi"
        val watts = if (charging && mA != null && volts != null) (mA * volts / 1000.0).takeIf { it in 0.1..150.0 } else null

        val chargeType = rd("$SUPPLY/battery/charge_type")
        val usbType = rd("$SUPPLY/usb/usb_type")?.let { Regex("\\[(.*?)]").find(it)?.groupValues?.get(1) ?: it }
        var maxW: Double? = null
        val extra = mutableListOf<Pair<String, String>>()
        val keys = listOf("online", "type", "usb_type", "charge_type", "voltage_max", "current_max", "input_current_limit",
            "constant_charge_current_max", "pd_active", "health")
        File(SUPPLY).list()?.sorted()?.forEach { name ->
            keys.forEach { k -> rd("$SUPPLY/$name/$k")?.let { if (extra.size < 30) extra += "$name/$k" to it } }
            val vm = rd("$SUPPLY/$name/voltage_max")?.toDoubleOrNull()
            val cm = rd("$SUPPLY/$name/current_max")?.toDoubleOrNull()
            if (name != "battery" && vm != null && cm != null && vm > 0 && cm > 0) {
                val v = if (vm > 100000) vm / 1e6 else vm / 1e3
                val a = if (cm >= 20000) cm / 1e6 else cm / 1e3
                (v * a).takeIf { it in 1.0..240.0 }?.let { w -> maxW = maxOf(maxW ?: 0.0, w) }
            }
        }
        return Charge(plugged, status, level, temp, mA, volts, watts, chargeType, usbType, maxW, extra)
    }

    /** Label mode pengisian dari daya yang benar-benar terukur (bukan dari teks ROM). */
    fun chargeLabel(c: Charge): String {
        if (c.status == "Penuh") return "Baterai penuh"
        if (c.plugged == "Tidak tercolok") return "Tidak sedang mengisi"
        val t = c.chargeType?.lowercase().orEmpty()
        val w = c.watts ?: 0.0
        return when {
            w >= 30 -> "Super Fast Charging"
            w >= 15 || t.contains("fast") || t.contains("turbo") -> "Fast Charging"
            w >= 7.5 -> "Pengisian cepat sedang"
            w > 0 -> "Pengisian normal"
            else -> "Mengisi (daya tidak terbaca)"
        }
    }
}
