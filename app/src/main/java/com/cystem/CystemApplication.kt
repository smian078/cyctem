package com.cystem

import android.app.Application
import com.cystem.core.di.AppContainer

class CystemApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
