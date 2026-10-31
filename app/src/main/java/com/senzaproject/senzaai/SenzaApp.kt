package com.senzaproject.senzaai

import android.app.Application
import com.senzaproject.senzaai.di.ServiceLocator
import com.senzaproject.senzaai.i18n.Lang

class SenzaApp : Application() {
    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
        services = ServiceLocator(this)
    }
}
