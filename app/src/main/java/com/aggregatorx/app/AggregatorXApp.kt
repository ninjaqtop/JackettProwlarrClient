package com.aggregatorx.app

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp3.OkHttp3NetworkFetcher
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AggregatorXApp : Application(), SingletonImageLoader.Factory {
    
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Enables multidex for devices running on API levels prior to 21
        MultiDex.install(this)
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /**
     * Coil 3 Singleton ImageLoader configuration.
     * Integrates OkHttp via the coil-network-okhttp dependency.
     */
    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttp3NetworkFetcher.Factory())
            }
            .crossfade(true)
            .build()
    }
    
    companion object {
        lateinit var instance: AggregatorXApp
            private set
    }
}
