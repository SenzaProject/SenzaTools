package com.senzaproject.senzatools.platform

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.senzaproject.senzatools.data.OperationLog
import com.senzaproject.senzatools.domain.model.AccessMode
import com.senzaproject.senzatools.domain.model.AccessState
import com.senzaproject.senzatools.domain.model.RootEnvironment
import com.senzaproject.senzatools.platform.root.CommandGuard
import com.senzaproject.senzatools.platform.root.RootPower
import com.senzaproject.senzatools.platform.root.SafeAction
import com.senzaproject.senzatools.utils.ShellResult
import com.senzaproject.senzatools.utils.Sys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Senza Access Manager: mengelola tiga tingkat akses (Normal, ADB/Shizuku, Root).
 * - Root diminta SEKALI otomatis saat aplikasi dibuka ([autoRequestRootOnce]). Bila sudah pernah disetujui di
 *   root manager, permintaan itu berjalan senyap dan semua fitur langsung aktif tanpa menekan apa pun.
 * - Bila ditolak, tidak ditanyakan terus-menerus; pengguna bisa menekan "Minta izin root" kapan saja.
 * - Semua eksekusi root/shell dicatat di [OperationLog].
 */
class AccessManager(private val ctx: Context, private val log: OperationLog) {

    private val rootManagers = linkedMapOf(
        "com.topjohnwu.magisk" to "Magisk",
        "io.github.vvb2060.magisk" to "Magisk (varian)",
        "me.weishu.kernelsu" to "KernelSU",
        "com.rifsxd.ksunext" to "KernelSU Next",
        "me.bmax.apatch" to "APatch"
    )
    private val shizukuPkg = "moe.shizuku.privileged.api"

    // State Compose: layar yang membaca isRootVerified otomatis tergambar ulang begitu root disetujui.
    private var rootVerified by mutableStateOf(false)

    /** True selama permintaan root sedang berjalan (menunggu persetujuan root manager). */
    var rootChecking by mutableStateOf(false)
        private set

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var autoTried = false

    private val _state = MutableStateFlow(detect())
    val state: StateFlow<AccessState> = _state.asStateFlow()

    val isRootVerified: Boolean get() = rootVerified

    private fun installed(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    private fun detect(): AccessState {
        val managers = rootManagers.filter { installed(it.key) }.values.toList()
        val su = Sys.sh("command -v su", 3000).output.trim().lineSequence().firstOrNull()?.takeIf { it.startsWith("/") }
        val shizuku = installed(shizukuPkg)
        val details = buildList {
            add("Root manager terdeteksi: ${if (managers.isEmpty()) "tidak ada" else managers.joinToString()}")
            add("Binary su: ${su ?: "tidak ditemukan di PATH aplikasi"}")
            add("Verifikasi akses root: ${if (rootVerified) "berhasil (uid=0)" else "belum dilakukan"}")
            add("Catatan: dukungan KernelSU/Magisk/APatch bergantung pada versi dan konfigurasi perangkat.")
        }
        val active = if (rootVerified) AccessMode.ROOT else AccessMode.NORMAL
        val caps = mutableListOf(
            "Informasi perangkat dari API Android standar",
            "Membaca sysfs/procfs yang diizinkan SELinux",
            "Analisis file yang kamu pilih (build.prop, APK, ZIP ROM, XML, init.rc)"
        )
        val limits = mutableListOf(
            "Logcat hanya memuat log aplikasi ini",
            "Partisi blok, dmesg, dan proses sistem tidak dapat dibaca",
            "Suhu, utilisasi GPU, dan statistik CPU bergantung pada perangkat"
        )
        if (rootVerified) {
            caps += "Root: baca file sistem, logcat penuh, dmesg, proses, modul kernel (read-only)"
            limits += "Hanya aksi terdefinisi atau perintah yang kamu ketik dan konfirmasi"
        }
        if (shizuku) {
            limits += "Shizuku terdeteksi terpasang, tetapi eksekusi lewat Shizuku belum disertakan di versi ini"
        }
        return AccessState(active, RootEnvironment(managers, su, rootVerified, details), shizuku, caps, limits)
    }

    fun refresh() {
        _state.value = detect()
    }

    /**
     * Dipanggil sekali saat aplikasi dibuka. Kalau root sudah pernah diizinkan di root manager, ini berjalan
     * tanpa dialog. Kalau belum, root manager menampilkan permintaan satu kali.
     */
    fun autoRequestRootOnce() {
        if (autoTried) return
        autoTried = true
        bgScope.launch { verifyRoot() }
    }

    /** Memverifikasi root (su -c id). Memunculkan prompt dari root manager bila izin belum diberikan. */
    suspend fun verifyRoot(): ShellResult = withContext(Dispatchers.IO) {
        rootChecking = true
        try {
            val r = Sys.execute(listOf("su", "-c", "id"), 30_000)
            rootVerified = r.ok && r.output.contains("uid=0")
            log.add("ROOT", "verifikasi akses (su -c id)", if (rootVerified) "berhasil" else "gagal/ditolak")
            _state.value = detect()
            r
        } finally {
            rootChecking = false
        }
    }

    fun revokeRootSession() {
        rootVerified = false
        log.add("ROOT", "sesi root di aplikasi dinonaktifkan", "ok")
        _state.value = detect()
    }

    /** Jalankan aksi terdefinisi (read-only). Root bila [asRoot] dan sudah diverifikasi, selain itu Normal. */
    suspend fun run(action: SafeAction, asRoot: Boolean): ShellResult = withContext(Dispatchers.IO) {
        val useRoot = asRoot && rootVerified
        val r = if (useRoot) Sys.execute(listOf("su", "-c", action.command), 30_000)
        else Sys.execute(listOf("sh", "-c", action.command), 30_000)
        log.add(if (useRoot) "ROOT" else "NORMAL", "${action.title}: ${action.command}", if (r.ok) "ok" else "exit=${r.exitCode}")
        r
    }

    /**
     * Perintah yang diketik pengguna sendiri. Pemanggil WAJIB sudah menampilkan konfirmasi.
     * Ditolak bila cocok daftar blokir.
     */
    suspend fun runUserCommand(command: String, asRoot: Boolean): ShellResult = withContext(Dispatchers.IO) {
        val reason = CommandGuard.check(command)
        if (reason != null) {
            log.add(if (asRoot) "ROOT" else "NORMAL", "DITOLAK: $command", reason)
            return@withContext ShellResult(-1, "Perintah diblokir: $reason")
        }
        val useRoot = asRoot && rootVerified
        if (asRoot && !rootVerified) return@withContext ShellResult(-1, "Akses root belum diverifikasi.")
        val r = if (useRoot) Sys.execute(listOf("su", "-c", command), 30_000)
        else Sys.execute(listOf("sh", "-c", command), 30_000)
        log.add(if (useRoot) "ROOT" else "NORMAL", "perintah pengguna: $command", if (r.ok) "ok" else "exit=${r.exitCode}")
        r
    }

    /**
     * Perintah root yang disusun aplikasi sendiri (bukan teks bebas) untuk fitur yang menulis berkas sistem (mis. Fake Device).
     * Path sudah divalidasi PathGuard oleh pemanggil. Dicatat di log.
     */
    suspend fun runRootEdit(command: String, label: String, timeoutMs: Long = 30_000): ShellResult = withContext(Dispatchers.IO) {
        if (!rootVerified) return@withContext ShellResult(-1, "Akses root belum diverifikasi.")
        val r = Sys.execute(listOf("su", "-c", command), timeoutMs)
        log.add("ROOT", "$label: ${command.take(200)}", if (r.ok) "ok" else "exit=${r.exitCode}")
        r
    }

    /**
     * Perintah root yang disusun aplikasi sendiri, dibaca baris demi baris ([Sys.stream]). Blocking: panggil dari thread IO.
     * Tidak ada pemotongan kepala keluaran dan tidak ada stderr yang tercampur; hasil menyatakan sendiri apakah lengkap.
     */
    fun runRootStream(
        command: String,
        label: String,
        timeoutMs: Long = 30_000,
        tail: Int = Int.MAX_VALUE,
        keep: (String) -> Boolean = { true }
    ): com.senzaproject.senzatools.utils.StreamResult {
        if (!rootVerified) return com.senzaproject.senzatools.utils.StreamResult(-1, emptyList(), 0, "Akses root belum diverifikasi.", false, false)
        val r = Sys.stream(listOf("su", "-c", command), timeoutMs, tail, keep)
        log.add("ROOT", "$label: ${command.take(200)}", if (r.ok) "ok" else "exit=${r.exitCode}")
        return r
    }

    /**
     * Restart perangkat lewat rantai beberapa metode ([RootPower]). Kembali segera setelah perintah dikirim;
     * bila perangkat benar-benar restart, proses aplikasi mati sebelum pemanggil sempat melakukan apa pun.
     */
    suspend fun rebootDevice(reason: String? = null): ShellResult {
        val cmd = try {
            RootPower.command(reason)
        } catch (e: IllegalArgumentException) {
            return ShellResult(-1, e.message ?: "Alasan restart tidak valid")
        }
        return runRootEdit(cmd, "restart${reason?.let { " ($it)" } ?: ""}", 8_000)
    }

    /** Cek file mana yang ada (untuk pintasan Edit cepat). Satu eksekusi, satu entri log. */
    suspend fun existingFiles(paths: List<String>, asRoot: Boolean): List<String> = withContext(Dispatchers.IO) {
        val valid = paths.map { com.senzaproject.senzatools.platform.root.PathGuard.validate(it) }
        val cmd = "for f in " + valid.joinToString(" ") { "'$it'" } + "; do [ -f \"\$f\" ] && echo \"\$f\"; done"
        val useRoot = asRoot && rootVerified
        val r = if (useRoot) Sys.execute(listOf("su", "-c", cmd), 15_000) else Sys.execute(listOf("sh", "-c", cmd), 15_000)
        log.add(if (useRoot) "ROOT" else "NORMAL", "pindai pintasan edit (${valid.size} path)", if (r.ok || r.output.isNotBlank()) "ok" else "exit=${r.exitCode}")
        r.output.lines().map { it.trim() }.filter { it in valid }
    }

    /** Seperti [existingFiles] tetapi untuk direktori (mengikuti symlink). */
    suspend fun existingDirs(paths: List<String>, asRoot: Boolean): List<String> = withContext(Dispatchers.IO) {
        val valid = paths.map { com.senzaproject.senzatools.platform.root.PathGuard.validate(it) }
        val cmd = "for f in " + valid.joinToString(" ") { "'$it'" } + "; do [ -d \"\$f\" ] && echo \"\$f\"; done"
        val useRoot = asRoot && rootVerified
        val r = if (useRoot) Sys.execute(listOf("su", "-c", cmd), 15_000) else Sys.execute(listOf("sh", "-c", cmd), 15_000)
        log.add(if (useRoot) "ROOT" else "NORMAL", "pindai partisi (${valid.size} path)", if (r.ok || r.output.isNotBlank()) "ok" else "exit=${r.exitCode}")
        r.output.lines().map { it.trim() }.filter { it in valid }
    }
}
