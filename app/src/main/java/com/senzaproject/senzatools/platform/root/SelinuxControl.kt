package com.senzaproject.senzatools.platform.root

import com.senzaproject.senzatools.platform.AccessManager
import com.senzaproject.senzatools.utils.Sys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Status dan mode SELinux. Mengganti ke Permissive bersifat sementara (kembali Enforcing setelah restart)
 * dan hanya lewat tombol khusus dengan konfirmasi, bukan lewat shell bebas. Hasil selalu dibaca ulang
 * untuk memastikan perubahan benar-benar terjadi (sebagian kernel memaksa Enforcing).
 */
class SelinuxControl(private val access: AccessManager) {

    data class Res(val ok: Boolean, val message: String)

    companion object {
        /** Terima "1"/"0" (sysfs) atau "Enforcing"/"Permissive"/"Disabled" (getenforce). */
        fun parseMode(text: String?): String? {
            val v = text?.trim()?.lowercase() ?: return null
            return when {
                v == "1" || v.startsWith("enforcing") -> "Enforcing"
                v == "0" || v.startsWith("permissive") -> "Permissive"
                v.startsWith("disabled") -> "Disabled"
                else -> null
            }
        }
    }

    /** Baca mode saat ini: sysfs, lalu getenforce biasa, lalu getenforce lewat root bila tersedia. */
    suspend fun status(): String? = withContext(Dispatchers.IO) {
        parseMode(Sys.readText("/sys/fs/selinux/enforce"))
            ?: parseMode(Sys.execute(listOf("sh", "-c", "getenforce"), 3_000).output)
            ?: if (access.isRootVerified) parseMode(Sys.execute(listOf("su", "-c", "getenforce"), 5_000).output) else null
    }

    suspend fun setPermissive(permissive: Boolean): Res = withContext(Dispatchers.IO) {
        if (!access.isRootVerified) return@withContext Res(false, "Butuh akses root yang sudah diverifikasi.")
        val want = if (permissive) "Permissive" else "Enforcing"
        val r = access.runRootEdit("setenforce ${if (permissive) 0 else 1}", "SELinux -> $want")
        val now = status()
        if (now == want) Res(true, "SELinux sekarang $want." + if (permissive) " Kembali Enforcing setelah restart." else "")
        else Res(false, "Mode belum berubah (sekarang ${now ?: "tidak terbaca"}). Kernel mungkin memaksa Enforcing. ${r.output.trim().take(120)}")
    }
}
