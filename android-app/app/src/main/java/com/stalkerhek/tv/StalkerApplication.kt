package com.stalkerhek.tv

import android.app.Application
import android.content.Intent
import com.stalkerhek.tv.engine.EngineController

class StalkerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startService(Intent(this, EngineService::class.java))
    }

    override fun onTerminate() {
        EngineController.shutdown()
        super.onTerminate()
    }
}
