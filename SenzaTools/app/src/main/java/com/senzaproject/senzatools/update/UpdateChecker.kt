package com.senzaproject.senzatools.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.senzaproject.senzatools.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Build GitHub Actions terbaru yang sukses di branch utama. */
data class LatestBuild(val runNumber: Int, val url: String, val title: String?)

sealed class UpdateState {
    /** Belum diperiksa. */
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    /** Build lokal (bukan dari CI): tidak ada pemeriksaan update. */
    object DevBuild : UpdateState()
    /** Ada build yang lebih baru. Aplikasi harus diperbarui sebelum dipakai. */
    data class Required(val latest: LatestBuild) : UpdateState()
    /** Pemeriksaan gagal (offline, rate limit, dll.). Tidak memblokir aplikasi. */
    data class Unknown(val reason: String) : UpdateState()
}

/** Aturan keputusan, dipisah supaya mudah diuji. */
object UpdateRules {
    /** Build lokal (nomor 0) tidak pernah dipaksa update. */
    fun isRequired(currentBuild: Int, latestBuild: Int?): Boolean =
        currentBuild > 0 && latestBuild != null && latestBuild > currentBuild

    /** Hanya buka tautan ke github.com; selain itu pakai halaman Actions repo. */
    fun safeUrl(url: String?, fallback: String): String =
        if (url != null && url.startsWith("https://github.com/")) url else fallback
}

/**
 * Pembaruan seperti OTA, memakai GitHub Actions sebagai sumbernya.
 *
 * Tiap build CI menanamkan nomor run Actions ([BuildConfig.BUILD_NUMBER]). Saat aplikasi dibuka, run sukses
 * terbaru di branch utama diambil lewat API publik GitHub. Bila nomornya lebih besar dari nomor build yang terpasang,
 * aplikasi menampilkan layar "Pembaruan wajib" yang mengarah ke halaman run tersebut (tempat APK diunggah).
 *
 * - Hasil terakhir disimpan, jadi memutus internet setelah tahu ada update tidak membuka blokirnya.
 * - Bila pemeriksaan gagal dan belum pernah ada hasil, aplikasi tetap bisa dipakai (jangan sampai offline = tidak bisa dibuka).
 * - Build lokal (BUILD_NUMBER 0) dilewati.
 */
class UpdateChecker(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("senza_update", Context.MODE_PRIVATE)

    var state: UpdateState by mutableStateOf(initial())
        private set

    private fun cached(): LatestBuild? {
        val n = prefs.getInt("latest_run", 0)
        if (n <= 0) return null
        return LatestBuild(n, prefs.getString("latest_url", null) ?: BuildConfig.UPDATE_PAGE, prefs.getString("latest_title", null))
    }

    private fun initial(): UpdateState {
        if (BuildConfig.BUILD_NUMBER <= 0) return UpdateState.DevBuild
        val c = cached()
        return if (c != null && UpdateRules.isRequired(BuildConfig.BUILD_NUMBER, c.runNumber)) UpdateState.Required(c) else UpdateState.Idle
    }

    /** Periksa update. Aman dipanggil berulang. */
    suspend fun check() {
        if (BuildConfig.BUILD_NUMBER <= 0) {
            state = UpdateState.DevBuild
            return
        }
        if (state !is UpdateState.Required) state = UpdateState.Checking
        val result = withContext(Dispatchers.IO) { runCatching { fetch() } }
        result.onSuccess { latest ->
            prefs.edit().putInt("latest_run", latest.runNumber).putString("latest_url", latest.url).putString("latest_title", latest.title).apply()
            state = if (UpdateRules.isRequired(BuildConfig.BUILD_NUMBER, latest.runNumber)) UpdateState.Required(latest) else UpdateState.UpToDate
        }.onFailure { e ->
            // Jangan melepas blokir hanya karena gagal terhubung.
            if (state !is UpdateState.Required) state = UpdateState.Unknown(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fetch(): LatestBuild {
        val c = URL(BuildConfig.UPDATE_API).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 7000
            c.readTimeout = 7000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "SenzaTools-Updater")
            val code = c.responseCode
            if (code != 200) throw IOException("GitHub membalas HTTP $code")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            val run = JSONObject(text).optJSONArray("workflow_runs")?.optJSONObject(0)
                ?: throw IOException("Belum ada build sukses di GitHub Actions")
            val n = run.optInt("run_number", -1)
            if (n <= 0) throw IOException("Nomor build tidak terbaca")
            val title = run.optString("display_title", "").ifBlank { null }
            return LatestBuild(n, UpdateRules.safeUrl(run.optString("html_url", null), BuildConfig.UPDATE_PAGE), title)
        } finally {
            c.disconnect()
        }
    }
}
