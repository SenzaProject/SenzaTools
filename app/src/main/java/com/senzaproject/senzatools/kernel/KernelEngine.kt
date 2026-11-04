package com.senzaproject.senzatools.kernel

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.senzaproject.senzatools.platform.AccessManager
import com.senzaproject.senzatools.platform.root.SafeAction
import com.senzaproject.senzatools.utils.Sys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class KernelScan(val device: DeviceProfile, val states: List<NodeState>) {
    val summary: KernelRules.Summary get() = KernelRules.summarize(states)
    fun state(id: String): NodeState? = states.firstOrNull { it.node.id == id }
}

data class ApplyReport(val results: List<WriteResult>, val preflightError: String? = null) {
    val okCount: Int get() = results.count { it is WriteResult.Ok }
    val failed: List<WriteResult.Failed> get() = results.filterIsInstance<WriteResult.Failed>()
}

data class BootStatus(val enabled: Boolean, val pending: Boolean, val valueCount: Int, val lastStatus: String)

/**
 * Mesin root Kernel Manager. Aturan keamanan:
 *  1. Hanya node dari [KernelCatalog] dan hanya path di dalam node itu (whitelist) yang boleh ditulis.
 *  2. Nilai divalidasi per tipe + aturan lintas-node SEBELUM satu pun penulisan; gagal validasi = tidak ada yang ditulis.
 *  3. Perintah dirakit oleh [KernelRules] dari konstanta + nilai ber-karakter aman; tidak ada teks bebas ke shell.
 *  4. Setiap penulisan dibaca ulang; bila kernel menyimpan nilai lain/menolak, nilai lama dipulihkan otomatis.
 *  5. Nilai asli (stock) dan nilai sebelum penerapan (undo) dicadangkan; satu operasi berjalan pada satu waktu.
 *  6. Node HIGH-risk tidak pernah ikut diterapkan saat boot; boot guard menonaktifkan penerapan bila boot tidak stabil.
 */
class KernelEngine(private val ctx: Context, private val access: AccessManager, val store: KernelStore) {

    private val mutex = Mutex()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val rootOk: Boolean get() = access.isRootVerified

    // ------------------------------------------------------------------ Pemindaian

    suspend fun deviceProfile(): DeviceProfile = withContext(Dispatchers.IO) {
        val platform = Sys.prop("ro.board.platform")
        val soc = KernelRules.detectSoc(Sys.prop("ro.soc.manufacturer"), platform, Sys.prop("ro.hardware"))
        val kernel = KernelVersion.parse(Sys.readText("/proc/version"))
        val selinux = com.senzaproject.senzatools.platform.root.SelinuxMode.parse(Sys.readText("/sys/fs/selinux/enforce"))
            ?: Sys.stream(listOf("sh", "-c", "getenforce"), 3000).takeIf { it.ok }
                ?.let { com.senzaproject.senzatools.platform.root.SelinuxMode.parse(it.lines.joinToString("\n")) }
            ?: "Tidak diketahui"
        val ramKb = Sys.readText("/proc/meminfo")?.lineSequence()?.firstOrNull { it.startsWith("MemTotal:") }
            ?.filter { it.isDigit() }?.toLongOrNull()
        DeviceProfile(Build.VERSION.SDK_INT, kernel, soc, platform, selinux, ramKb?.div(1024))
    }

    suspend fun scan(): KernelScan = withContext(Dispatchers.IO) {
        val dev = deviceProfile()
        store.ensureStockKey(Build.FINGERPRINT + "|" + (dev.kernel?.raw ?: "?"))
        val out = access.run(SafeAction.ReadScript(KernelRules.readScript(KernelCatalog.all), "Baca node kernel"), rootOk).output
        KernelScan(dev, KernelRules.parseRead(KernelCatalog.all, out, dev))
    }

    suspend fun thermalZones(): List<Pair<String, Double>> = withContext(Dispatchers.IO) {
        KernelRules.parseThermal(access.run(SafeAction.ReadScript(KernelRules.THERMAL_SCRIPT, "Baca suhu zona termal"), rootOk).output)
    }

    // ------------------------------------------------------------------ Penulisan

    private suspend fun writeOne(st: NodeState, value: String): WriteResult {
        val path = (st.support as? Support.Ready)?.path ?: return WriteResult.Failed(st.node.id, "node tidak bisa ditulis", false)
        val cmd = try { KernelRules.writeScript(st.node, path, value) } catch (e: IllegalArgumentException) {
            return WriteResult.Failed(st.node.id, e.message ?: "nilai tidak valid", false)
        }
        val r = access.runRootEdit(cmd, "kernel ${st.node.id}=$value", 15_000)
        return when (val p = KernelRules.parseWrite(r.output)) {
            is KernelRules.ParsedWrite.Err -> WriteResult.Failed(st.node.id, p.reason, false)
            is KernelRules.ParsedWrite.Res -> {
                val oldN = KernelRules.normalize(st.node, p.old)
                if (KernelRules.sameValue(st.node, value, p.new)) {
                    store.recordStock(path, oldN)
                    WriteResult.Ok(st.node.id, oldN, KernelRules.normalize(st.node, p.new))
                } else {
                    // Kernel/HAL menimpa nilai: pulihkan nilai lama agar tidak setengah-terapkan.
                    val back = access.runRootEdit(KernelRules.writeScript(st.node, path, oldN), "pulihkan ${st.node.id}", 15_000)
                    val restored = (KernelRules.parseWrite(back.output) as? KernelRules.ParsedWrite.Res)
                        ?.let { KernelRules.sameValue(st.node, oldN, it.new) } == true
                    WriteResult.Failed(st.node.id, "kernel menyimpan “${KernelRules.normalize(st.node, p.new)}”, bukan “$value”", restored)
                }
            }
        }
    }

    /**
     * Terapkan perubahan. Validasi dulu seluruhnya (atomik); baru menulis. Nilai sebelumnya disimpan sebagai undo.
     * @param validate false hanya untuk memulihkan nilai asli/snapshot yang mungkin di luar rentang katalog (tetap lewat whitelist & karakter aman).
     */
    suspend fun apply(scan: KernelScan, changes: List<Change>, validate: Boolean = true): ApplyReport = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!rootOk) return@withContext ApplyReport(emptyList(), "Akses root belum diverifikasi.")
            if (changes.isEmpty()) return@withContext ApplyReport(emptyList())
            val results = mutableListOf<WriteResult>()
            val usable = mutableListOf<Pair<NodeState, String>>()
            for (c in KernelRules.orderChanges(changes)) {
                val st = scan.state(c.nodeId)
                when {
                    st == null -> results += WriteResult.Failed(c.nodeId, "node tidak dikenal", false)
                    !st.support.usable -> results += WriteResult.Failed(c.nodeId, "node tidak tersedia/terkunci di perangkat ini", false)
                    else -> {
                        val err = if (validate) KernelRules.validateValue(st.node, c.value, st.options) else null
                        if (err != null) return@withContext ApplyReport(emptyList(), "${st.node.title}: $err")
                        usable += st to c.value
                    }
                }
            }
            if (validate) {
                val effective = scan.states.mapNotNull { s -> s.value?.let { s.node.id to it } }.toMap() + usable.associate { it.first.node.id to it.second }
                KernelRules.crossCheck(effective)?.let { return@withContext ApplyReport(emptyList(), it) }
            }
            store.setUndo(usable.mapNotNull { (s, _) -> s.value?.let { s.node.id to it } }.toMap())
            for ((st, v) in usable) results += writeOne(st, v)
            store.addTouched(results.filterIsInstance<WriteResult.Ok>().map { it.nodeId })
            ApplyReport(results)
        }
    }

    // ------------------------------------------------------------------ Cadangan & pemulihan

    /** Nilai sekarang semua node Ready (untuk snapshot). */
    fun currentValues(scan: KernelScan): Map<String, String> =
        scan.states.filter { it.support.usable }.mapNotNull { s -> s.value?.let { s.node.id to it } }.toMap()

    /** Perubahan yang mengembalikan nilai asli; hanya node yang nilainya berbeda dari stock. */
    fun stockChanges(scan: KernelScan): List<Change> {
        val stock = store.stock()
        return scan.states.mapNotNull { s ->
            val path = (s.support as? Support.Ready)?.path ?: return@mapNotNull null
            val orig = stock[path] ?: return@mapNotNull null
            if (orig != s.value) Change(s.node.id, orig) else null
        }
    }

    fun undoChanges(scan: KernelScan): List<Change> =
        store.undo().mapNotNull { (id, v) -> scan.state(id)?.takeIf { it.support.usable && it.value != v }?.let { Change(id, v) } }

    fun snapshotChanges(scan: KernelScan, name: String): List<Change> =
        store.snapshots()[name].orEmpty().mapNotNull { (id, v) -> scan.state(id)?.takeIf { it.support.usable && it.value != v }?.let { Change(id, v) } }

    // ------------------------------------------------------------------ Boot

    private suspend fun bootRun(cmd: String, label: String) = access.runRootEdit(cmd, label, 15_000)

    suspend fun bootStatus(): BootStatus = withContext(Dispatchers.IO) {
        val d = KernelRules.BOOT_DIR
        val script = "[ -f $d/enabled ] && echo E=1; [ -f $d/pending ] && echo P=1; " +
            "[ -f $d/values.conf ] && echo C=\$(grep -c . $d/values.conf); [ -f $d/last_status ] && echo S=\$(head -c 200 $d/last_status)"
        val out = access.run(SafeAction.ReadScript(script, "Baca status boot kernel"), rootOk).output
        fun f(k: String) = out.lineSequence().firstOrNull { it.startsWith("$k=") }?.substringAfter("=")?.trim()
        BootStatus(f("E") == "1", f("P") == "1", f("C")?.toIntOrNull() ?: 0, f("S").orEmpty())
    }

    /** Simpan nilai SAAT INI node yang pernah diubah (tanpa HIGH-risk) sebagai konfigurasi boot dan aktifkan. */
    suspend fun enableBoot(scan: KernelScan): Result<Int> = withContext(Dispatchers.IO) {
        if (!rootOk) return@withContext Result.failure(IllegalStateException("Akses root belum diverifikasi."))
        val touched = store.touched()
        val entries = scan.states.filter { it.node.id in touched && it.support.usable && it.value != null }.map { it.node to it.value!! }
        val conf = KernelRules.bootConf(
            entries,
            pathOf = { n -> (scan.state(n.id)?.support as? Support.Ready)?.path },
            optionsOf = { n -> scan.state(n.id)?.options.orEmpty() }
        )
        val count = conf.lines().count { it.isNotBlank() }
        if (count == 0) return@withContext Result.failure(IllegalStateException("Belum ada pengaturan aman yang diubah lewat aplikasi ini."))
        val d = KernelRules.BOOT_DIR
        val cmd = "mkdir -p $d && chmod 700 $d && " +
            "cat > $d/boot.sh <<'SENZA_EOF'\n${KernelRules.bootScript()}SENZA_EOF\n" +
            "cat > $d/values.conf <<'SENZA_EOF'\n${conf}SENZA_EOF\n" +
            "chmod 700 $d/boot.sh && rm -f $d/pending && : > $d/enabled && echo \"siap: $count nilai\" > $d/last_status"
        val r = bootRun(cmd, "pasang penerapan kernel saat boot")
        if (r.ok) Result.success(count) else Result.failure(IllegalStateException("Gagal menulis konfigurasi boot (exit=${r.exitCode})."))
    }

    suspend fun disableBoot(): Boolean = withContext(Dispatchers.IO) {
        val d = KernelRules.BOOT_DIR
        bootRun("rm -f $d/enabled $d/pending $d/values.conf $d/boot.sh $d/last_status; true", "hapus penerapan kernel saat boot").ok
    }

    /** Hapus penanda "pending" setelah perangkat terbukti stabil (uptime > 150 dtk). */
    suspend fun confirmStable() = withContext(Dispatchers.IO) {
        if (SystemClock.elapsedRealtime() < 150_000 || !rootOk) return@withContext
        bootRun("[ -f ${KernelRules.BOOT_DIR}/pending ] && rm -f ${KernelRules.BOOT_DIR}/pending; true", "konfirmasi boot stabil")
        Unit
    }

    /** Dipanggil dari MainActivity: menunggu root terverifikasi (maks ±45 dtk) lalu mengonfirmasi stabil. */
    fun confirmStableLater() {
        bg.launch {
            repeat(45) { if (rootOk) return@repeat else delay(1000) }
            if (rootOk) confirmStable()
        }
    }
}
