package com.iromashka

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import io.sentry.android.core.SentryAndroid
import io.sentry.SentryLevel

class IromashkaApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()

        SentryAndroid.init(this) { options ->
            options.dsn = "https://placeholder@sentry.io/placeholder"
            options.environment = if (BuildConfig.DEBUG) "development" else "production"
            options.release = "iromashka@${BuildConfig.VERSION_NAME}"
            options.tracesSampleRate = 0.1
            options.isEnableAutoSessionTracking = true
            options.setDiagnosticLevel(SentryLevel.ERROR)
        }
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
