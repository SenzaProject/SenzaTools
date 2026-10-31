package com.senzaproject.senzatools

import android.app.Application
import com.senzaproject.senzatools.di.ServiceLocator
import com.senzaproject.senzatools.i18n.Lang

class SenzaApp : Application() {
    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
        services = ServiceLocator(this)
    }
}
