package com.senzaproject.senzatools.platform.root

import com.senzaproject.senzatools.platform.AccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Status dan mode SELinux. Mengganti ke Permissive bersifat sementara (kembali Enforcing setelah restart)
 * dan hanya lewat tombol khusus dengan konfirmasi, bukan lewat shell bebas. Hasil selalu dibaca ulang
 * untuk memastikan perubahan benar-benar terjadi (sebagian kernel memaksa Enforcing).
 *
 * Logika ada di [SelinuxEngine] (murni, diuji di JVM). Mode dibaca dari SEMUA sumber yang tersedia dan dipilih
 * dengan urutan tetap, sehingga hasilnya tidak bergantung pada kapan root selesai diverifikasi.
 */
class SelinuxControl(access: AccessManager) {

    private val engine = SelinuxEngine(AccessShell(access))

    companion object {
        /** Terima "1"/"0" (sysfs) atau "Enforcing"/"Permissive"/"Disabled" (getenforce). */
        fun parseMode(text: String?): String? = SelinuxMode.parse(text)
    }

    /** Mode saat ini beserta sumber dan ada-tidaknya konflik antarsumber. */
    suspend fun statusDetail(): ModeStatus = withContext(Dispatchers.IO) { engine.status() }

    suspend fun status(): String? = statusDetail().mode

    suspend fun setPermissive(permissive: Boolean): SelinuxEngine.Res = withContext(Dispatchers.IO) { engine.setPermissive(permissive) }

    /** Pindai denial dari dmesg dan logcat sekaligus, tanpa hitung ganda. */
    suspend fun scanDenials(): AvcScan = withContext(Dispatchers.IO) { engine.scanDenials() }

    /** Teks avc mentah dari "dmesg" atau "logcat" untuk layar Analyzer. */
    suspend fun captureAvcText(source: String): Pair<String?, AvcSourceNote> = withContext(Dispatchers.IO) { engine.captureAvcText(source) }
}
