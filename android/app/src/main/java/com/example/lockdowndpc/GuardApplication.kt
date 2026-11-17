package com.example.lockdowndpc

import android.app.Application
import com.example.lockdowndpc.ui.AppLocales

class GuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocales.ensureDefault(this)
    }
}
