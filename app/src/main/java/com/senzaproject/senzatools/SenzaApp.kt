package com.senzaproject.senzatools

import android.app.Application
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.i18n.Lang
import com.senzaproject.senzatools.shizuku.ShizukuManager

class SenzaApp : Application() {
    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
        services = ServiceLocator(this)
        // Daftarkan listener Shizuku sedini mungkin — ini yang membuat SenzaTools akhirnya
        // muncul di daftar aplikasi Shizuku Manager (sebelumnya cuma deteksi "terpasang", tidak
        // pernah benar-benar memanggil API-nya).
        ShizukuManager.init()
    }
}
