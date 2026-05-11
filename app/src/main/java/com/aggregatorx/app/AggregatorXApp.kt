package com.aggregatorx.app

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AggregatorXApp : Application() {
    
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Enables multidex for devices running on API levels prior to 21
        MultiDex.install(this)
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
    
    companion object {
        lateinit var instance: AggregatorXApp
            private set
    }
}
