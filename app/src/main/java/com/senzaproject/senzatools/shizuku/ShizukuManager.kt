package com.senzaproject.senzatools.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/**
 * Integrasi Shizuku SUNGGUHAN — sebelum ini, SenzaTools cuma mendeteksi apakah aplikasi Shizuku
 * terpasang (lewat <queries> di manifest), tanpa pernah benar-benar memanggil API-nya. Itu sebabnya
 * SenzaTools tidak pernah muncul di daftar aplikasi Shizuku Manager: Shizuku cuma mendaftarkan
 * aplikasi yang benar-benar memanggil Shizuku.pingBinder()/listener-nya, bukan yang cuma
 * terdeteksi lewat package query.
 *
 * Alur yang diimplementasikan di sini mengikuti pola resmi dari demo rikka/Shizuku-API (publik,
 * dipakai ratusan aplikasi lain) — bukan reverse-engineering seperti protokol BROM/DA:
 *  1. Daftarkan listener binder (received/dead) sekali saat aplikasi start.
 *  2. Kalau binder ada tapi izin belum diberikan, panggil requestPermission() dan tunggu hasilnya
 *     lewat listener (dialog izin muncul dari pihak Shizuku, bukan dialog buatan sendiri).
 *  3. Kalau izin sudah ada, bind ke [ShizukuUserService] lewat Shizuku.bindUserService — proses
 *     itu berjalan dengan UID shell/root (bukan UID aplikasi ini), dan di situlah perintah shell
 *     benar-benar dieksekusi.
 *
 * CATATAN JUJUR: versi library (13.1.5) dan bentuk API di atas mengikuti dokumentasi publik resmi
 * Shizuku-API yang stabil selama ini. Yang BELUM saya bisa pastikan tanpa uji langsung: perilaku
 * persis di versi Shizuku tertentu yang dipakai pengguna (ADB-mode vs root-mode) — tapi kedua mode
 * itu memang dirancang transparan lewat API publik yang sama, jadi risikonya jauh lebih rendah
 * dibanding asumsi protokol BROM/DA.
 */
object ShizukuManager {

    private const val REQUEST_CODE = 9100

    enum class Status { UNAVAILABLE, NEEDS_PERMISSION, READY, DENIED }

    private val _status = MutableStateFlow(Status.UNAVAILABLE)
    val status: StateFlow<Status> = _status

    private var service: IShizukuUserService? = null
    private var listenersRegistered = false
    private var pendingPermissionResult: CompletableDeferred<Boolean>? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName("com.senzaproject.senzatools", ShizukuUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shizuku_service")
            .debuggable(false)
            .version(1)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        _status.value = Status.UNAVAILABLE
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        _status.value = if (granted) Status.READY else Status.DENIED
        pendingPermissionResult?.complete(granted)
        pendingPermissionResult = null
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IShizukuUserService.Stub.asInterface(binder)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    /** Panggil sekali saat aplikasi start (mis. dari SenzaApp.onCreate). Aman dipanggil berkali-kali. */
    fun init() {
        if (listenersRegistered) return
        listenersRegistered = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshStatus()
    }

    private fun refreshStatus() {
        if (!Shizuku.pingBinder()) {
            _status.value = Status.UNAVAILABLE
            return
        }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (granted) {
            _status.value = Status.READY
            if (service == null) bindService()
        } else {
            _status.value = Status.NEEDS_PERMISSION
        }
    }

    private fun bindService() {
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (_: Throwable) {
            // Gagal bind tidak dianggap fatal — status tetap READY (izin ada), tapi exec() akan
            // melaporkan error spesifik kalau service belum tersambung saat dipanggil.
        }
    }

    /** Minta izin Shizuku. Hasilnya datang lewat listener (dialog izin dari Shizuku, bukan dialog kita). */
    suspend fun requestPermission(): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            _status.value = Status.READY
            bindService()
            return true
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingPermissionResult = deferred
        Shizuku.requestPermission(REQUEST_CODE)
        return deferred.await()
    }

    sealed class ExecResult {
        data class Ok(val output: String, val exitCode: Int) : ExecResult()
        data class Error(val message: String) : ExecResult()
    }

    /** Jalankan perintah shell sungguhan lewat Shizuku (UID shell atau root, tergantung mode Shizuku pengguna). */
    fun exec(command: String): ExecResult {
        val svc = service ?: return ExecResult.Error(
            "Belum tersambung ke service Shizuku. Pastikan status Shizuku \"READY\" dan coba lagi sebentar " +
                "(bind service berjalan asinkron setelah izin diberikan)."
        )
        return try {
            val exitCodeOut = IntArray(1)
            val output = svc.execShell(command, exitCodeOut)
            ExecResult.Ok(output, exitCodeOut[0])
        } catch (t: Throwable) {
            service = null // binder kemungkinan mati, paksa bind ulang di pemanggilan berikutnya
            ExecResult.Error("Koneksi ke service Shizuku terputus saat eksekusi: ${t.message}")
        }
    }

    fun uidLabel(): String? = try {
        when (service?.uid) {
            null -> null
            0 -> "root (UID 0)"
            2000 -> "shell/ADB (UID 2000)"
            else -> "UID ${service?.uid}"
        }
    } catch (_: Throwable) {
        null
    }
}
