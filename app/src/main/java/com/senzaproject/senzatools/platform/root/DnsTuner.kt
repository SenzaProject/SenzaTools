package com.senzaproject.senzatools.platform.root

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.senzaproject.senzatools.platform.AccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Pengatur Private DNS (DNS-over-TLS) Android. Nilai disimpan di Settings.Global (private_dns_mode dan
 * private_dns_specifier), sama persis dengan menu Settings > Private DNS, jadi berlaku di semua aplikasi.
 *
 * Sebelum diterapkan, hostname DIUJI langsung: koneksi TLS ke port 853 dan pencocokan sertifikat.
 * Bila tes gagal, pengaturan tidak diubah sehingga internet tidak putus karena DNS yang salah.
 */
class DnsTuner(private val ctx: Context, private val access: AccessManager) {

    data class Provider(val id: String, val name: String, val host: String, val note: String)
    data class State(val mode: String, val host: String?)
    data class Res(val ok: Boolean, val message: String)

    companion object {
        val PROVIDERS = listOf(
            Provider("adguard", "AdGuard DNS", "dns.adguard.com", "Blokir iklan dan pelacak"),
            Provider("cloudflare", "Cloudflare", "one.one.one.one", "Cepat, fokus privasi"),
            Provider("google", "Google Public DNS", "dns.google", "Stabil dan cepat"),
            Provider("quad9", "Quad9", "dns.quad9.net", "Blokir domain malware dan phishing"),
            Provider("cleanbrowsing", "CleanBrowsing", "security-filter-dns.cleanbrowsing.org", "Filter malware dan phishing")
        )

        private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+\$")

        fun isValidHost(h: String): Boolean = h.length <= 253 && HOST.matches(h)

        fun modeLabel(mode: String): String = when (mode) {
            "hostname" -> "Hostname khusus"
            "off" -> "Nonaktif"
            else -> "Otomatis"
        }
    }

    /** Baca pengaturan saat ini. Settings.Global boleh dibaca aplikasi mana pun, tanpa root. */
    fun state(): State {
        val cr = ctx.contentResolver
        val mode = try { Settings.Global.getString(cr, "private_dns_mode") } catch (_: Throwable) { null }
        val host = try { Settings.Global.getString(cr, "private_dns_specifier") } catch (_: Throwable) { null }
        return State(mode?.takeIf { it.isNotBlank() } ?: "opportunistic", host?.takeIf { it.isNotBlank() })
    }

    /** Tes nyata: TLS ke host:853 (dengan SNI) lalu cocokkan sertifikat dengan hostname. */
    suspend fun test(host: String): Res = withContext(Dispatchers.IO) {
        if (!isValidHost(host)) return@withContext Res(false, "Hostname tidak valid.")
        val t0 = SystemClock.elapsedRealtime()
        var raw: Socket? = null
        try {
            raw = Socket()
            raw.connect(InetSocketAddress(host, 853), 5000)
            raw.soTimeout = 5000
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 853, true) as SSLSocket
            ssl.startHandshake()
            val ok = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)
            val ms = SystemClock.elapsedRealtime() - t0
            if (ok) Res(true, "Tes berhasil: $host:853 terhubung dalam $ms ms dan sertifikat valid.")
            else Res(false, "Sertifikat server tidak cocok dengan $host.")
        } catch (e: Exception) {
            Res(false, "Tidak bisa terhubung ke $host:853 (${e.javaClass.simpleName}). Periksa internet; sebagian jaringan memblokir port 853.")
        } finally {
            try { raw?.close() } catch (_: Exception) {}
        }
    }

    /** Terapkan Private DNS ke [host] setelah tes lolos. Butuh root yang sudah diverifikasi. */
    suspend fun apply(host: String): Res = withContext(Dispatchers.IO) {
        if (!access.isRootVerified) return@withContext Res(false, "Butuh akses root yang sudah diverifikasi.")
        if (!isValidHost(host)) return@withContext Res(false, "Hostname tidak valid.")
        val t = test(host)
        if (!t.ok) return@withContext Res(false, "DNS tidak diterapkan karena tes gagal. ${t.message}")
        val r = access.runRootEdit(
            "settings put global private_dns_specifier $host && settings put global private_dns_mode hostname",
            "Private DNS $host"
        )
        val st = state()
        if (st.mode == "hostname" && st.host == host) Res(true, "Private DNS aktif: $host. ${t.message}")
        else Res(false, "Perintah selesai tapi pengaturan belum berubah (mode ${st.mode}). ${r.output.trim().take(120)}")
    }

    /** Kembali ke Otomatis (bawaan Android) atau matikan Private DNS. */
    suspend fun setMode(mode: String): Res = withContext(Dispatchers.IO) {
        if (!access.isRootVerified) return@withContext Res(false, "Butuh akses root yang sudah diverifikasi.")
        if (mode != "opportunistic" && mode != "off") return@withContext Res(false, "Mode tidak valid.")
        val r = access.runRootEdit("settings put global private_dns_mode $mode", "Private DNS mode $mode")
        val st = state()
        if (st.mode == mode) Res(true, "Private DNS: ${modeLabel(mode)}.")
        else Res(false, "Gagal mengubah mode. ${r.output.trim().take(120)}")
    }
}
