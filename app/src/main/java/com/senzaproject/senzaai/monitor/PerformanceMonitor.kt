package com.senzaproject.senzaai.monitor

import android.content.Context
import android.os.SystemClock
import com.senzaproject.senzaai.data.CpuUsageSampler
import com.senzaproject.senzaai.data.DeviceRepository
import com.senzaproject.senzaai.platform.AccessManager
import com.senzaproject.senzaai.platform.root.SafeAction
import com.senzaproject.senzaai.utils.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

data class PerfSample(
    val time: Long,
    val cpu: Float?,
    val cpuEstimate: Boolean,
    val ramPercent: Float,
    val storagePercent: Float,
    val batteryTemp: Float?,
    val deviceTemp: Float?,
    val batteryPercent: Int,
    val load1: Float?
)

data class ProcInfo(val pid: String, val user: String, val rssKb: Long, val name: String)

/** Monitor performa: hanya berjalan selama dikumpulkan dari UI (tidak ada layanan latar belakang → hemat baterai). */
class PerformanceMonitor(private val device: DeviceRepository, private val access: AccessManager) {

    fun samples(intervalMs: Long = 2000): Flow<PerfSample> = flow {
        val sampler = CpuUsageSampler()
        sampler.sample() // inisialisasi baseline
        while (true) {
            val cpu = device.cpuSnapshot(null)
            val usage = sampler.sample()
            var est = false
            var u = usage
            if (u == null) {
                val ratios = cpu.cores.filter { it.online && it.curKhz != null && (it.maxKhz ?: 0L) > 0L }.map { it.curKhz!!.toFloat() / it.maxKhz!! }
                if (ratios.isNotEmpty()) {
                    u = (ratios.average().toFloat() * 100f).coerceIn(0f, 100f)
                    est = true
                }
            }
            val mem = device.memory()
            val sto = device.storage()
            val bat = device.battery()
            val dev = device.thermalZones().maxOfOrNull { it.second }
            val load = File("/proc/loadavg").takeIf { it.canRead() }?.runCatching { readText().split(" ")[0].toFloat() }?.getOrNull()
            emit(PerfSample(System.currentTimeMillis(), u, est, mem.percent, sto.percent, bat.tempC, dev, bat.percent, load))
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    fun uptimeMs(): Long = SystemClock.elapsedRealtime()
    fun uptimeAwakeMs(): Long = SystemClock.uptimeMillis()

    /** Daftar proses. Normal Access hanya melihat proses aplikasi sendiri; root dapat melihat semua. */
    suspend fun processes(asRoot: Boolean): Pair<List<ProcInfo>, String> {
        val r = access.run(SafeAction.ProcessList, asRoot)
        val list = r.output.lineSequence().drop(1).mapNotNull { l ->
            val t = l.trim().split(Regex("\\s+"), 4)
            if (t.size < 4) null else ProcInfo(t[0], t[1], t[2].toLongOrNull() ?: 0L, t[3])
        }.toList()
        val note = if (asRoot && access.isRootVerified) "Semua proses (root)."
        else "Normal Access: hanya proses yang terlihat oleh aplikasi ini (Android membatasi proses aplikasi lain)."
        return list.sortedByDescending { it.rssKb } to note
    }
}

/** Riwayat sesi monitoring (disimpan sebagai CSV di folder app, maks 5000 sampel). */
class HistoryStore(ctx: Context) {
    private val file = File(ctx.filesDir, "perf_history.csv")
    private val header = "time,cpu,ram,storage,batt_temp,dev_temp,batt_pct,load1"

    @Synchronized
    fun append(batch: List<PerfSample>) {
        if (batch.isEmpty()) return
        try {
            if (!file.exists()) file.writeText(header + "\n")
            file.appendText(batch.joinToString("\n", postfix = "\n") {
                "${it.time},${it.cpu ?: ""},${it.ramPercent},${it.storagePercent},${it.batteryTemp ?: ""},${it.deviceTemp ?: ""},${it.batteryPercent},${it.load1 ?: ""}"
            })
            val lines = file.readLines()
            if (lines.size > 5001) file.writeText((listOf(lines.first()) + lines.takeLast(5000)).joinToString("\n") + "\n")
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun load(): List<PerfSample> = try {
        if (!file.exists()) emptyList() else file.readLines().drop(1).mapNotNull { l ->
            val p = l.split(',')
            if (p.size < 8) null else PerfSample(
                time = p[0].toLongOrNull() ?: return@mapNotNull null,
                cpu = p[1].toFloatOrNull(), cpuEstimate = false,
                ramPercent = p[2].toFloatOrNull() ?: 0f, storagePercent = p[3].toFloatOrNull() ?: 0f,
                batteryTemp = p[4].toFloatOrNull(), deviceTemp = p[5].toFloatOrNull(),
                batteryPercent = p[6].toIntOrNull() ?: 0, load1 = p[7].toFloatOrNull()
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    fun exportFile(): File? = if (file.exists()) file else null

    fun statsText(samples: List<PerfSample>): String {
        if (samples.isEmpty()) return "Belum ada riwayat."
        fun stat(name: String, v: List<Float>) = if (v.isEmpty()) "$name: -" else
            "$name: min ${"%.1f".format(v.min())} · rata-rata ${"%.1f".format(v.average())} · maks ${"%.1f".format(v.max())}"
        return buildString {
            appendLine("Periode: ${samples.first().time.formatTime()} s/d ${samples.last().time.formatTime()} (${samples.size} sampel)")
            appendLine(stat("CPU %", samples.mapNotNull { it.cpu }))
            appendLine(stat("RAM %", samples.map { it.ramPercent }))
            appendLine(stat("Suhu baterai °C", samples.mapNotNull { it.batteryTemp }))
            appendLine(stat("Suhu perangkat °C", samples.mapNotNull { it.deviceTemp }))
        }
    }
}
