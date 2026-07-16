package com.aggregatorx.app

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.aggregatorx.app.engine.scraper.HeadlessBrowserHelper
import com.aggregatorx.app.engine.util.EngineUtils
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class AggregatorXApp : Application(), SingletonImageLoader.Factory {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        HeadlessBrowserHelper.configure(this)
    }

    /**
     * Provide a Coil3 ImageLoader backed by a properly configured OkHttpClient.
     * This ensures thumbnail requests carry the correct User-Agent and follow
     * redirects, preventing 403s on provider sites that check the UA header.
     */
    override fun newImageLoader(context: Context): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", EngineUtils.DEFAULT_USER_AGENT)
                        .header("Accept", "image/webp,image/avif,image/*,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()
                )
            }
            .build()

        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: AggregatorXApp
            private set
    }
}
