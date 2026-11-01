package com.senzaproject.senzatools.platform.root

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.senzaproject.senzatools.utils.Sys
import kotlin.concurrent.thread

/**
 * Cadangan persistensi tanpa module: setelah boot, jalankan skrip penerap Senza Tools (bind-mount hasil edit
 * dan resetprop) lewat root. Tidak melakukan apa pun bila pengguna belum pernah menerapkan edit (skrip belum ada).
 * Bekerja dengan root manager apa pun yang sudah mengizinkan aplikasi ini; tidak ada dialog yang dimunculkan.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val pending = goAsync()
        thread(name = "senza-boot-apply") {
            try {
                Sys.execute(
                    listOf(
                        "su", "-c",
                        "for b in /data/adb/senza_edit /data/local/senza_edit; do [ -f \"\$b/senza_apply.sh\" ] && sh \"\$b/senza_apply.sh\"; done; true"
                    ),
                    20_000
                )
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }
}
