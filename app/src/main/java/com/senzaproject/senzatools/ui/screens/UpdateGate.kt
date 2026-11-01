package com.senzaproject.senzatools.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.senzaproject.senzatools.BuildConfig
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.ui.components.Body
import com.senzaproject.senzatools.ui.components.Caption
import com.senzaproject.senzatools.ui.components.GlassBackground
import com.senzaproject.senzatools.ui.components.GlassButton
import com.senzaproject.senzatools.ui.components.GlassCard
import com.senzaproject.senzatools.ui.components.Spinner
import com.senzaproject.senzatools.ui.components.Title
import com.senzaproject.senzatools.update.LatestBuild
import com.senzaproject.senzatools.update.UpdateRules
import com.senzaproject.senzatools.update.UpdateState
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Buka halaman unduhan update di browser. Hanya tautan github.com yang dibuka. */
fun openUpdatePage(ctx: Context, url: String?) {
    val safe = UpdateRules.safeUrl(url, BuildConfig.UPDATE_PAGE)
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/**
 * Membungkus seluruh aplikasi. Bila ada build yang lebih baru, hanya layar "Pembaruan wajib" yang tampil:
 * aplikasi tidak bisa dipakai sebelum diperbarui. Tombol kembali menutup aplikasi.
 */
@Composable
fun UpdateGate(s: ServiceLocator, content: @Composable () -> Unit) {
    LaunchedEffect(Unit) { s.updates.check() }
    val st = s.updates.state
    if (st is UpdateState.Required) UpdateRequiredScreen(s, st.latest) else content()
}

@Composable
private fun UpdateRequiredScreen(s: ServiceLocator, latest: LatestBuild) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }

    BackHandler { activity?.finishAffinity() }

    GlassBackground {
        Box(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(strong = true) {
                Title("Pembaruan wajib")
                Body("Senza Tools versi ini sudah usang (build #${BuildConfig.BUILD_NUMBER}). Versi terbaru: build #${latest.runNumber}. Perbarui dulu supaya aplikasi berjalan dengan benar; selama belum diperbarui aplikasi tidak bisa dipakai.")
                latest.title?.let { Caption("Isi pembaruan: $it") }
                Body("Unduh APK terbaru dari halaman GitHub Actions (artifact SenzaTools-release-apk), lalu pasang di atas aplikasi ini.", dim = true)
                Caption("Mengunduh artifact GitHub Actions membutuhkan login GitHub di browser.")
                GlassButton("Unduh pembaruan", { openUpdatePage(ctx, latest.url) }, Modifier.fillMaxWidth(), primary = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(if (checking) "Memeriksa…" else "Periksa lagi", {
                        scope.launch {
                            checking = true
                            s.updates.check()
                            checking = false
                        }
                    }, Modifier.weight(1f), enabled = !checking)
                    GlassButton("Keluar", { activity?.finishAffinity() }, Modifier.weight(1f), danger = true)
                }
                if (checking) Spinner("Memeriksa versi terbaru…")
            }
        }
    }
}
