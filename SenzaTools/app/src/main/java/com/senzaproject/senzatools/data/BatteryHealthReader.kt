package com.senzaproject.senzatools.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.senzaproject.senzatools.platform.AccessManager
import com.senzaproject.senzatools.platform.root.SafeAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Membaca kesehatan baterai dari sumber nyata saja:
 *  - node sysfs power_supply (charge_full, charge_full_design, cycle_count, soh, ...), dibaca dengan skrip yang
 *    sama dengan yang bisa dijalankan di Termux, supaya angkanya bisa dicocokkan satu per satu;
 *  - dumpsys batterystats (kapasitas hasil pembelajaran sistem) bila root aktif;
 *  - BatteryManager STATE_OF_HEALTH dan EXTRA_CYCLE_COUNT (Android 14+).
 * Tidak ada angka yang diperkirakan dari charge counter; bila tidak ada sumber, hasilnya "tidak tersedia".
 */
class BatteryHealthReader(private val ctx: Context, private val access: AccessManager) {

    suspend fun read(asRoot: Boolean): BatteryHealthInfo = withContext(Dispatchers.IO) {
        val useRoot = asRoot && access.isRootVerified
        val out = access.run(SafeAction.ReadScript(BatteryHealth.SCRIPT, "Baca kesehatan baterai"), useRoot).output

        val bm = ctx.getSystemService(BatteryManager::class.java)
        // 10 = BATTERY_PROPERTY_STATE_OF_HEALTH (Android 14+). Perangkat yang tidak mendukung mengembalikan Int.MIN_VALUE atau 0.
        val apiSoh = if (Build.VERSION.SDK_INT >= 34 && bm != null) {
            try {
                bm.getIntProperty(10).takeIf { it != Int.MIN_VALUE && it > 0 }
            } catch (_: Throwable) {
                null
            }
        } else null
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val apiCycles = if (Build.VERSION.SDK_INT >= 34) {
            sticky?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }
        } else null

        BatteryHealth.compute(BatteryHealth.parse(out), apiSoh, apiCycles, useRoot)
    }
}
