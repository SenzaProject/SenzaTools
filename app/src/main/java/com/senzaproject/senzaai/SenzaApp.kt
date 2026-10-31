package com.senzaproject.senzaai

import android.app.Application
import com.senzaproject.senzaai.di.ServiceLocator

class SenzaApp : Application() {
    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        services = ServiceLocator(this)
    }
}
