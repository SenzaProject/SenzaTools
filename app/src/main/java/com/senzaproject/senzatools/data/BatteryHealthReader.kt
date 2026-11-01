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

        val info = BatteryHealth.compute(BatteryHealth.parse(out), apiSoh, apiCycles, useRoot)
        if (info.primary != null) return@withContext info

        // Tidak ada sumber langsung: pakai PERKIRAAN bila pengguna memasukkan kapasitas desain baterainya.
        val design = savedDesignMah()
        val counter = if (bm != null) try {
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it != Long.MIN_VALUE && it > 0 }
        } catch (_: Throwable) {
            null
        } else null
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 1..100 }
        val est = BatteryHealth.estimate(counter, level, design)
        when {
            est != null -> info.copy(
                primary = est, missing = null,
                designMah = design,
                notes = info.notes + "Angka ini PERKIRAAN: perangkat ini tidak menyediakan kapasitas desain/SOH di sysfs. Isi kapasitas desain sesuai spesifikasi baterai, dan ukur saat level 50–100 % agar lebih akurat."
            )
            design != null -> info.copy(
                notes = info.notes + "Perkiraan belum bisa dihitung: charge counter tidak terbaca, level di bawah 20 %, atau hasil tidak masuk akal (kapasitas desain salah?)."
            )
            else -> info
        }
    }

    private val prefs get() = ctx.getSharedPreferences("senza_battery", Context.MODE_PRIVATE)

    /** Kapasitas desain (mAh) yang dimasukkan pengguna untuk perkiraan, atau null. */
    fun savedDesignMah(): Float? = prefs.getFloat("design_mah", 0f).takeIf { it > 0f }

    fun saveDesignMah(v: Float?) {
        prefs.edit().apply { if (v == null || v <= 0f) remove("design_mah") else putFloat("design_mah", v) }.apply()
    }
}
