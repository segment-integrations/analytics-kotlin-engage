package com.segment.analytics.destinations.mydestination.testapp

import android.app.Application
import com.segment.analytics.kotlin.android.Analytics
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.destinations.engage.TwilioEngage

class MainApplication : Application() {
    companion object {
        lateinit var analytics: Analytics
    }

    override fun onCreate() {
        super.onCreate()

        analytics = Analytics("M2e1bNw0GXE2tU78sAKZFzLOy5XdI8fj", applicationContext) {
            apiHost = "api.segment.build/v1"
            cdnHost = "cdn-settings.segment.build/v1"
            trackDeepLinks = true
            trackApplicationLifecycleEvents = true
            flushAt = 1
            flushInterval = 10
        }

        analytics.add(TwilioEngage(applicationContext))
    }
}