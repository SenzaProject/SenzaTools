package com.senzaproject.senzaai.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.telephony.TelephonyManager
import android.view.Display
import android.view.WindowManager
import com.senzaproject.senzaai.domain.model.BatterySnapshot
import com.senzaproject.senzaai.domain.model.CpuCore
import com.senzaproject.senzaai.domain.model.CpuSnapshot
import com.senzaproject.senzaai.domain.model.InfoRow
import com.senzaproject.senzaai.domain.model.InfoSection
import com.senzaproject.senzaai.domain.model.MemorySnapshot
import com.senzaproject.senzaai.domain.model.StorageSnapshot
import com.senzaproject.senzaai.utils.Sys
import com.senzaproject.senzaai.utils.f1
import com.senzaproject.senzaai.utils.f2
import com.senzaproject.senzaai.utils.formatBytes
import com.senzaproject.senzaai.utils.formatKhz
import com.senzaproject.senzaai.utils.formatTime
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Sumber data perangkat level Normal Access. Semua fungsi bersifat blocking; panggil dari Dispatchers.IO.
 * Nilai yang tidak dapat dibaca ditampilkan sebagai "Tidak tersedia" (bukan ditebak).
 */
class DeviceRepository(private val ctx: Context) {

    private val na = "Tidak tersedia"

    // ---------------------------------------------------------------- Device

    fun deviceSections(): List<InfoSection> {
        val soc = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != "unknown" }
                .joinToString(" ").ifBlank { na }
        } else na
        val deviceName = try {
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        } catch (_: Throwable) {
            Build.MODEL
        }
        val pageSize = try {
            Os.sysconf(OsConstants._SC_PAGESIZE)
        } catch (_: Throwable) {
            -1L
        }
        val p = Sys.props()
        fun prop(k: String) = p[k]?.takeIf { it.isNotBlank() } ?: na

        return listOf(
            InfoSection(
                "Perangkat", listOf(
                    InfoRow("Nama perangkat", deviceName),
                    InfoRow("Model", Build.MODEL),
                    InfoRow("Produsen", Build.MANUFACTURER),
                    InfoRow("Brand", Build.BRAND),
                    InfoRow("Codename", Build.DEVICE),
                    InfoRow("Product", Build.PRODUCT),
                    InfoRow("Board", Build.BOARD),
                    InfoRow("Hardware", Build.HARDWARE),
                    InfoRow("SoC", soc),
                    InfoRow("Platform", prop("ro.board.platform"))
                )
            ),
            InfoSection(
                "Sistem Android", listOf(
                    InfoRow("Versi Android", Build.VERSION.RELEASE),
                    InfoRow("SDK", Build.VERSION.SDK_INT.toString()),
                    InfoRow("Codename rilis", Build.VERSION.CODENAME),
                    InfoRow("Build ID", Build.ID),
                    InfoRow("Incremental", Build.VERSION.INCREMENTAL),
                    InfoRow("Fingerprint", Build.FINGERPRINT),
                    InfoRow("Security patch", Build.VERSION.SECURITY_PATCH),
                    InfoRow("Build type / tags", "${Build.TYPE} / ${Build.TAGS}"),
                    InfoRow("Waktu build", Build.TIME.formatTime()),
                    InfoRow("Page size kernel", if (pageSize > 0) "${pageSize / 1024} KB" else na)
                )
            ),
            InfoSection(
                "Kernel & Arsitektur", listOf(
                    InfoRow("Kernel", System.getProperty("os.version") ?: na),
                    InfoRow("Arsitektur", System.getProperty("os.arch") ?: na),
                    InfoRow("ABI didukung", Build.SUPPORTED_ABIS.joinToString(", ")),
                    InfoRow("ABI 64-bit", Build.SUPPORTED_64_BIT_ABIS.joinToString(", ").ifBlank { "-" }),
                    InfoRow("ABI 32-bit", Build.SUPPORTED_32_BIT_ABIS.joinToString(", ").ifBlank { "-" }),
                    InfoRow("Bootloader", Build.BOOTLOADER),
                    InfoRow("Radio / baseband", Build.getRadioVersion() ?: na)
                )
            ),
            InfoSection(
                "Boot & Partisi (dari properti)", listOf(
                    InfoRow("SELinux", selinuxMode()),
                    InfoRow("Treble", prop("ro.treble.enabled")),
                    InfoRow("VNDK", prop("ro.vndk.version")),
                    InfoRow("First API level", prop("ro.product.first_api_level")),
                    InfoRow("Verified boot", prop("ro.boot.verifiedbootstate")),
                    InfoRow("Flash locked", prop("ro.boot.flash.locked")),
                    InfoRow("Slot aktif", prop("ro.boot.slot_suffix")),
                    InfoRow("A/B update", prop("ro.build.ab_update")),
                    InfoRow("Dynamic partitions", prop("ro.boot.dynamic_partitions")),
                    InfoRow("Virtual A/B", prop("ro.virtual_ab.enabled"))
                )
            )
        )
    }

    fun selinuxMode(): String {
        Sys.readText("/sys/fs/selinux/enforce")?.let { return if (it == "1") "Enforcing" else "Permissive" }
        val r = Sys.execute(listOf("getenforce"), 3000)
        return if (r.ok && r.output.isNotBlank()) r.output.trim() else na
    }

    // ---------------------------------------------------------------- CPU

    private fun cpuCount(): Int {
        val dirs = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
        return dirs?.size?.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
    }

    private val partNames = mapOf(
        0xd03 to "Cortex-A53", 0xd04 to "Cortex-A35", 0xd05 to "Cortex-A55", 0xd07 to "Cortex-A57",
        0xd08 to "Cortex-A72", 0xd09 to "Cortex-A73", 0xd0a to "Cortex-A75", 0xd0b to "Cortex-A76",
        0xd0c to "Neoverse-N1", 0xd0d to "Cortex-A77", 0xd41 to "Cortex-A78", 0xd44 to "Cortex-X1",
        0xd46 to "Cortex-A510", 0xd47 to "Cortex-A710", 0xd48 to "Cortex-X2", 0xd4d to "Cortex-A715",
        0xd4e to "Cortex-X3", 0xd80 to "Cortex-A520", 0xd81 to "Cortex-A720", 0xd82 to "Cortex-X4",
        0xd85 to "Cortex-X925", 0xd87 to "Cortex-A725"
    )

    private fun coreClusters(cpuinfo: String): String {
        val counts = linkedMapOf<String, Int>()
        var impl: Int? = null
        cpuinfo.lineSequence().forEach { line ->
            val key = line.substringBefore(':').trim()
            val v = line.substringAfter(':', "").trim()
            when (key) {
                "CPU implementer" -> impl = v.removePrefix("0x").toIntOrNull(16)
                "CPU part" -> {
                    val part = v.removePrefix("0x").toIntOrNull(16)
                    if (part != null) {
                        val name = if (impl == 0x41) partNames[part] ?: "part 0x${part.toString(16)}"
                        else "implementer 0x${impl?.toString(16) ?: "?"} part 0x${part.toString(16)}"
                        counts[name] = (counts[name] ?: 0) + 1
                    }
                }
            }
        }
        return if (counts.isEmpty()) na else counts.entries.joinToString(", ") { "${it.key} ×${it.value}" }
    }

    fun cpuSections(): List<InfoSection> {
        val info = Sys.readText("/proc/cpuinfo").orEmpty()
        fun field(name: String) = info.lineSequence().firstOrNull { it.startsWith(name) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
        val features = field("Features") ?: field("flags")
        val snap = cpuSnapshot(null)
        val zones = thermalZones()
        return listOf(
            InfoSection(
                "CPU", listOf(
                    InfoRow("Model", field("model name") ?: field("Hardware") ?: field("Processor") ?: na),
                    InfoRow("Inti (core)", cpuCount().toString()),
                    InfoRow("Komposisi core", coreClusters(info)),
                    InfoRow("Arsitektur", System.getProperty("os.arch") ?: na),
                    InfoRow("ABI utama", Build.SUPPORTED_ABIS.firstOrNull() ?: na),
                    InfoRow("Fitur", features?.take(160) ?: na),
                    InfoRow("Suhu CPU", snap.tempC?.let { "${it.f1()} °C" } ?: na)
                )
            ),
            InfoSection(
                "Status per core", snap.cores.map { c ->
                    val v = if (!c.online) "Offline" else buildString {
                        append(c.curKhz?.formatKhz() ?: "?")
                        if (c.maxKhz != null) append(" / maks ${c.maxKhz.formatKhz()}")
                        if (c.governor != null) append(" · ${c.governor}")
                    }
                    InfoRow("CPU${c.index}", v)
                }
            ),
            InfoSection(
                "Zona termal (yang dapat dibaca)",
                if (zones.isEmpty()) listOf(InfoRow("Status", "Tidak dapat dibaca pada Normal Access"))
                else zones.take(24).map { InfoRow(it.first, "${it.second.f1()} °C") }
            )
        )
    }

    private val cpuSampler = CpuUsageSampler()

    /** [sampler] null = hanya status core (tanpa menghitung penggunaan). */
    fun cpuSnapshot(sampler: CpuUsageSampler? = cpuSampler): CpuSnapshot {
        val n = cpuCount()
        val cores = (0 until n).map { i ->
            val base = "/sys/devices/system/cpu/cpu$i"
            val onlineFlag = Sys.readText("$base/online")
            val online = onlineFlag == null || onlineFlag == "1"
            CpuCore(
                index = i,
                online = online,
                curKhz = if (online) Sys.readLong("$base/cpufreq/scaling_cur_freq") else null,
                minKhz = Sys.readLong("$base/cpufreq/cpuinfo_min_freq"),
                maxKhz = Sys.readLong("$base/cpufreq/cpuinfo_max_freq") ?: Sys.readLong("$base/cpufreq/scaling_max_freq"),
                governor = Sys.readText("$base/cpufreq/scaling_governor")
            )
        }
        var usage: Float? = sampler?.sample()
        var estimate = false
        if (sampler != null && usage == null) {
            val ratios = cores.filter { it.online && it.curKhz != null && (it.maxKhz ?: 0L) > 0L }
                .map { it.curKhz!!.toFloat() / it.maxKhz!!.toFloat() }
            if (ratios.isNotEmpty()) {
                usage = (ratios.average().toFloat() * 100f).coerceIn(0f, 100f)
                estimate = true
            }
        }
        val temp = thermalZones()
            .filter { Regex("cpu|soc|tsens|cluster|big|little|mid", RegexOption.IGNORE_CASE).containsMatchIn(it.first) }
            .maxOfOrNull { it.second }
        return CpuSnapshot(usage, estimate, cores, temp)
    }

    fun thermalZones(): List<Pair<String, Float>> {
        val dirs = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return emptyList()
        return dirs.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 }.mapNotNull { d ->
            val type = Sys.readText("${d.path}/type") ?: return@mapNotNull null
            val raw = Sys.readLong("${d.path}/temp") ?: return@mapNotNull null
            val c = if (abs(raw) >= 1000) raw / 1000f else raw.toFloat()
            if (c < -20f || c > 150f) null else type to c
        }
    }

    // ---------------------------------------------------------------- GPU

    fun gpuSections(): List<InfoSection> {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val pm = ctx.packageManager
        val gl = queryGl()
        val vkFeature = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
        val vkText = if (vkFeature != null) {
            val v = vkFeature.version
            "Didukung (Vulkan ${v shr 22}.${(v shr 12) and 0x3ff})"
        } else "Tidak dilaporkan"
        val vkLevel = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }?.version
        val p = Sys.props()
        val util = gpuUtilization()
        return listOf(
            InfoSection(
                "GPU", listOf(
                    InfoRow("Renderer", gl.renderer ?: na),
                    InfoRow("Vendor", gl.vendor ?: na),
                    InfoRow("Versi OpenGL ES (konteks)", gl.version ?: na),
                    InfoRow("OpenGL ES (sistem)", am.deviceConfigurationInfo.glEsVersion ?: na),
                    InfoRow("Vulkan", vkText),
                    InfoRow("Vulkan level", vkLevel?.toString() ?: na),
                    InfoRow("Driver EGL", p["ro.hardware.egl"]?.ifBlank { null } ?: na),
                    InfoRow("Driver Vulkan", p["ro.hardware.vulkan"]?.ifBlank { null } ?: na),
                    InfoRow("Utilisasi GPU", util?.let { "${it.f1()} %" } ?: na)
                )
            )
        )
    }

    private data class GlInfo(val renderer: String?, val vendor: String?, val version: String?)

    private fun queryGl(): GlInfo {
        var display = EGL14.EGL_NO_DISPLAY
        var surface = EGL14.EGL_NO_SURFACE
        var eglCtx = EGL14.EGL_NO_CONTEXT
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return GlInfo(null, null, null)
            val cfgAttr = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(display, cfgAttr, 0, configs, 0, 1, num, 0)
            val cfg = configs[0] ?: return GlInfo(null, null, null)
            eglCtx = EGL14.eglCreateContext(
                display, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            surface = EGL14.eglCreatePbufferSurface(
                display, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            EGL14.eglMakeCurrent(display, surface, surface, eglCtx)
            GlInfo(
                GLES20.glGetString(GLES20.GL_RENDERER),
                GLES20.glGetString(GLES20.GL_VENDOR),
                GLES20.glGetString(GLES20.GL_VERSION)
            )
        } catch (_: Throwable) {
            GlInfo(null, null, null)
        } finally {
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (eglCtx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglCtx)
            } catch (_: Throwable) {
            }
        }
    }

    fun gpuUtilization(): Float? {
        Sys.readText("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.removeSuffix("%")?.trim()?.toFloatOrNull()?.let { return it }
        val busy = Sys.readText("/sys/class/kgsl/kgsl-3d0/gpubusy")?.split(Regex("\\s+"))
        if (busy != null && busy.size >= 2) {
            val a = busy[0].toLongOrNull()
            val b = busy[1].toLongOrNull()
            if (a != null && b != null && b > 0) return a * 100f / b
        }
        return null
    }

    // ---------------------------------------------------------------- RAM & Storage

    fun memory(): MemorySnapshot {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return MemorySnapshot(mi.totalMem, mi.availMem)
    }

    private fun meminfo(): Map<String, Long> {
        val out = linkedMapOf<String, Long>()
        Sys.readText("/proc/meminfo")?.lineSequence()?.forEach { l ->
            val k = l.substringBefore(':').trim()
            val v = l.substringAfter(':', "").trim().removeSuffix("kB").trim().toLongOrNull()
            if (k.isNotEmpty() && v != null) out[k] = v * 1024
        }
        return out
    }

    fun memorySections(): List<InfoSection> {
        val m = memory()
        val mi = meminfo()
        fun r(k: String) = mi[k]?.formatBytes() ?: na
        val am = ctx.getSystemService(ActivityManager::class.java)
        return listOf(
            InfoSection(
                "RAM", listOf(
                    InfoRow("Total RAM", m.totalBytes.formatBytes()),
                    InfoRow("Tersedia", m.availBytes.formatBytes()),
                    InfoRow("Terpakai", m.usedBytes.formatBytes()),
                    InfoRow("Persentase terpakai", "${m.percent.f1()} %"),
                    InfoRow("Cached", r("Cached")),
                    InfoRow("Buffers", r("Buffers")),
                    InfoRow("Swap total", r("SwapTotal")),
                    InfoRow("Swap bebas", r("SwapFree")),
                    InfoRow("Kelas memori aplikasi", "${am.memoryClass} MB (large: ${am.largeMemoryClass} MB)"),
                    InfoRow("Low RAM device", am.isLowRamDevice.toString())
                )
            )
        )
    }

    fun storage(): StorageSnapshot {
        val st = StatFs(Environment.getDataDirectory().path)
        return StorageSnapshot(st.totalBytes, st.availableBytes)
    }

    fun storageSections(): List<InfoSection> {
        val s = storage()
        val cache = dirSize(ctx.cacheDir) + (ctx.externalCacheDir?.let { dirSize(it) } ?: 0L)
        val parts = listOf(
            "/", "/system", "/system_ext", "/product", "/vendor", "/odm",
            "/data", "/cache", "/metadata", "/mnt/vendor/persist", "/storage/emulated/0"
        ).mapNotNull { path ->
            try {
                val st = StatFs(path)
                if (st.totalBytes <= 0) null
                else InfoRow(path, "${(st.totalBytes - st.availableBytes).formatBytes()} / ${st.totalBytes.formatBytes()}")
            } catch (_: Throwable) {
                null
            }
        }
        return listOf(
            InfoSection(
                "Penyimpanan internal (/data)", listOf(
                    InfoRow("Total", s.totalBytes.formatBytes()),
                    InfoRow("Tersedia", s.freeBytes.formatBytes()),
                    InfoRow("Terpakai", s.usedBytes.formatBytes()),
                    InfoRow("Persentase terpakai", "${s.percent.f1()} %"),
                    InfoRow("Cache aplikasi ini", cache.formatBytes())
                )
            ),
            InfoSection(
                "Partisi yang dapat diakses (terpakai / total)",
                parts.ifEmpty { listOf(InfoRow("Status", na)) }
            )
        )
    }

    private fun dirSize(d: File): Long {
        var total = 0L
        d.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    // ---------------------------------------------------------------- Battery

    fun battery(): BatterySnapshot {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val tempRaw = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val volt = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val statusInt = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING || statusInt == BatteryManager.BATTERY_STATUS_FULL
        val status = when (statusInt) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Mengisi daya"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Tidak mengisi"
            BatteryManager.BATTERY_STATUS_FULL -> "Penuh"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Tidak mengisi"
            else -> "Tidak diketahui"
        }
        val plugged = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Nirkabel"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
            else -> "Tidak terhubung"
        }
        val health = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Baik"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Terlalu panas"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Rusak"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Tegangan berlebih"
            BatteryManager.BATTERY_HEALTH_COLD -> "Terlalu dingin"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Gagal"
            else -> "Tidak diketahui"
        }
        // Arus: sebagian perangkat melaporkan µA, sebagian mA.
        val curRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = if (curRaw == Int.MIN_VALUE || curRaw == 0) null
        else if (abs(curRaw) > 20_000) curRaw / 1000 else curRaw
        val power = if (currentMa != null && volt != null) abs(currentMa) * volt / 1_000_000f else null
        val cycle = if (Build.VERSION.SDK_INT >= 34) {
            i?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }
        } else null

        var capacity: Int? = null
        var note: String? = null
        val full = Sys.readLong("/sys/class/power_supply/battery/charge_full")
        val design = Sys.readLong("/sys/class/power_supply/battery/charge_full_design")
        if (full != null && full > 0) {
            capacity = (full / 1000).toInt()
            note = if (design != null && design > 0) "Desain ${design / 1000} mAh (kesehatan ≈ ${(full * 100f / design).f1()} %)" else null
        } else {
            val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (counter > 0 && pct in 5..100) {
                capacity = (counter / 1000f / (pct / 100f)).roundToInt()
                note = "Estimasi dari charge counter"
            }
        }
        return BatterySnapshot(
            percent = pct,
            tempC = if (tempRaw != Int.MIN_VALUE) tempRaw / 10f else null,
            voltageMv = volt,
            status = status,
            plugged = plugged,
            technology = i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            health = health,
            currentMa = currentMa,
            powerW = power,
            cycleCount = cycle,
            capacityMah = capacity,
            capacityNote = note,
            isCharging = charging
        )
    }

    fun batterySections(): List<InfoSection> {
        val b = battery()
        return listOf(
            InfoSection(
                "Baterai", listOf(
                    InfoRow("Level", "${b.percent} %"),
                    InfoRow("Status", b.status),
                    InfoRow("Sumber daya", b.plugged),
                    InfoRow("Kesehatan", b.health),
                    InfoRow("Suhu", b.tempC?.let { "${it.f1()} °C" } ?: na),
                    InfoRow("Tegangan", b.voltageMv?.let { "$it mV" } ?: na),
                    InfoRow("Arus", b.currentMa?.let { "$it mA" } ?: na),
                    InfoRow("Estimasi daya", b.powerW?.let { "${it.f2()} W" } ?: na),
                    InfoRow("Teknologi", b.technology?.ifBlank { null } ?: na),
                    InfoRow("Cycle count", b.cycleCount?.toString() ?: na),
                    InfoRow("Kapasitas", b.capacityMah?.let { "$it mAh" } ?: na),
                    InfoRow("Catatan kapasitas", b.capacityNote ?: "-")
                )
            )
        )
    }

    // ---------------------------------------------------------------- Display

    @Suppress("DEPRECATION")
    fun displaySections(): List<InfoSection> {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = ctx.resources.displayMetrics
        val wm = ctx.getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        val inches = if (metrics.xdpi > 0 && metrics.ydpi > 0)
            sqrt((b.width() / metrics.xdpi).toDouble().let { it * it } + (b.height() / metrics.ydpi).toDouble().let { it * it })
        else null
        val modes = d?.supportedModes?.toList().orEmpty()
        val rates = modes.map { it.refreshRate.roundToInt() }.distinct().sortedDescending()
        val hdr = try {
            d?.hdrCapabilities?.supportedHdrTypes?.map {
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                    else -> "Tipe $it"
                }
            }?.joinToString(", ")?.ifBlank { null }
        } catch (_: Throwable) {
            null
        }
        val brightness = try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Throwable) {
            null
        }
        val adaptive = try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == 1
        } catch (_: Throwable) {
            null
        }
        return listOf(
            InfoSection(
                "Layar", listOf(
                    InfoRow("Resolusi", "${b.width()} × ${b.height()} px"),
                    InfoRow("Densitas", "${metrics.densityDpi} dpi (skala ${metrics.density})"),
                    InfoRow("xdpi / ydpi", "${metrics.xdpi.f1()} / ${metrics.ydpi.f1()}"),
                    InfoRow("Ukuran diagonal", inches?.let { "${it.f1()} inci" } ?: na),
                    InfoRow("Refresh rate saat ini", d?.refreshRate?.let { "${it.roundToInt()} Hz" } ?: na),
                    InfoRow("Refresh rate didukung", if (rates.isEmpty()) na else rates.joinToString(", ") { "$it Hz" }),
                    InfoRow("Mode layar", "${modes.size} mode"),
                    InfoRow("Kecerahan", brightness?.let { "$it / 255" } ?: na),
                    InfoRow("Kecerahan adaptif", adaptive?.let { if (it) "Aktif" else "Nonaktif" } ?: na),
                    InfoRow("HDR", hdr ?: "Tidak dilaporkan"),
                    InfoRow("Wide color gamut", ctx.resources.configuration.isScreenWideColorGamut.toString()),
                    InfoRow("Rotasi", d?.rotation?.let { "${it * 90}°" } ?: na)
                )
            )
        )
    }

    // ---------------------------------------------------------------- Network

    @Suppress("DEPRECATION")
    fun networkSections(): List<InfoSection> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val lp = net?.let { cm.getLinkProperties(it) }
        val transport = when {
            caps == null -> "Tidak terhubung"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Seluler"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Lainnya"
        }
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val wInfo = try {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) wifi?.connectionInfo else null
        } catch (_: Throwable) {
            null
        }
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        // SSID/BSSID sengaja tidak dikumpulkan (data sensitif).
        val main = listOf(
            InfoRow("Status koneksi", if (caps == null) "Tidak ada jaringan aktif" else "Terhubung"),
            InfoRow("Tipe jaringan", transport),
            InfoRow("Internet tervalidasi", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?.toString() ?: na),
            InfoRow("Bandwidth turun (perkiraan)", caps?.linkDownstreamBandwidthKbps?.let { "${it / 1000} Mbps" } ?: na),
            InfoRow("Bandwidth naik (perkiraan)", caps?.linkUpstreamBandwidthKbps?.let { "${it / 1000} Mbps" } ?: na),
            InfoRow("Alamat IP", lp?.linkAddresses?.joinToString(", ") { it.address.hostAddress ?: "" }?.ifBlank { null } ?: na),
            InfoRow("DNS", lp?.dnsServers?.joinToString(", ") { it.hostAddress ?: "" }?.ifBlank { null } ?: na),
            InfoRow("Interface", lp?.interfaceName ?: na),
            InfoRow("MTU", lp?.mtu?.takeIf { it > 0 }?.toString() ?: na)
        )
        val wifiRows = listOf(
            InfoRow("Wi-Fi aktif", wifi?.isWifiEnabled?.toString() ?: na),
            InfoRow("Kecepatan link", wInfo?.linkSpeed?.takeIf { it > 0 }?.let { "$it Mbps" } ?: na),
            InfoRow("Sinyal (RSSI)", wInfo?.rssi?.takeIf { it > -127 }?.let { rssi ->
                "$rssi dBm (level ${wifi?.calculateSignalLevel(rssi)}/${(wifi?.maxSignalLevel ?: 4)})"
            } ?: na),
            InfoRow("Frekuensi", wInfo?.frequency?.takeIf { it > 0 }?.let { "$it MHz" } ?: na)
        )
        val mobile = listOf(
            InfoRow("Operator jaringan", tm?.networkOperatorName?.ifBlank { null } ?: na),
            InfoRow("Operator SIM", tm?.simOperatorName?.ifBlank { null } ?: na),
            InfoRow("Roaming", tm?.isNetworkRoaming?.toString() ?: na)
        )
        return listOf(
            InfoSection("Koneksi", main),
            InfoSection("Wi-Fi", wifiRows),
            InfoSection("Jaringan seluler", mobile)
        )
    }

    /** Diagnostik jaringan sederhana: DNS, TCP connect, HTTP 204. */
    fun networkDiagnostics(): List<InfoRow> {
        val rows = mutableListOf<InfoRow>()
        fun <T> timed(block: () -> T): Pair<T?, Long> {
            val t0 = System.nanoTime()
            val r = try {
                block()
            } catch (_: Throwable) {
                null
            }
            return r to (System.nanoTime() - t0) / 1_000_000
        }
        val dns = timed { java.net.InetAddress.getByName("www.google.com").hostAddress }
        rows += InfoRow("Resolusi DNS (www.google.com)", if (dns.first != null) "OK · ${dns.second} ms" else "Gagal")
        val tcp = timed {
            Socket().use { s ->
                s.connect(InetSocketAddress("1.1.1.1", 443), 4000)
                true
            }
        }
        rows += InfoRow("Koneksi TCP (1.1.1.1:443)", if (tcp.first == true) "OK · ${tcp.second} ms" else "Gagal / timeout")
        val http = timed {
            val c = java.net.URL("https://www.google.com/generate_204").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            val code = c.responseCode
            c.disconnect()
            code
        }
        rows += InfoRow("HTTPS generate_204", if (http.first != null) "HTTP ${http.first} · ${http.second} ms" else "Gagal")
        return rows
    }
}

/** Menghitung penggunaan CPU dari /proc/stat. Mengembalikan null jika tidak dapat dibaca (dibatasi SELinux). */
class CpuUsageSampler {
    private var prevIdle = -1L
    private var prevTotal = -1L

    fun sample(): Float? {
        val line = try {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        } catch (_: Throwable) {
            null
        } ?: return null
        if (!line.startsWith("cpu ")) return null
        val p = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (p.size < 5) return null
        val idle = p[3] + p[4]
        val total = p.sum()
        val result = if (prevTotal >= 0 && total > prevTotal) {
            val dTotal = total - prevTotal
            val dIdle = idle - prevIdle
            ((dTotal - dIdle) * 100f / dTotal).coerceIn(0f, 100f)
        } else null
        prevIdle = idle
        prevTotal = total
        return result
    }
}
