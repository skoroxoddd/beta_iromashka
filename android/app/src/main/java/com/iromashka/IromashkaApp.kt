package com.iromashka

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache

class IromashkaApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Sentry disabled until real DSN is configured
        // SentryAndroid.init(this) { options -> ... }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .build()
    }
}
