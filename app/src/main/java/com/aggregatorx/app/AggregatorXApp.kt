package com.aggregatorx.app // Ensure this matches your manifest/namespace

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkLayerFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AggregatorXApp : Application(), SingletonImageLoader.Factory {
    
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /**
     * Coil 3 Singleton ImageLoader configuration.
     * This provides a global ImageLoader that uses OkHttp for networking.
     */
    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                // If you get an "Unresolved reference" here, make sure 
                // "io.coil-kt.coil3:coil-network-okhttp" is in your build.gradle.kts
                add(OkHttpNetworkLayerFactory())
            }
            .crossfade(true)
            .build()
    }
    
    companion object {
        lateinit var instance: AggregatorXApp
            private set
    }
}
