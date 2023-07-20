package com.segment.analytics.destinations.mydestination.testapp

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import com.segment.analytics.kotlin.android.Analytics
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.destinations.engage.TwilioEngage

class MainApplication : Application() {
    companion object {
        lateinit var analytics: Analytics
    }

    override fun onCreate() {
        super.onCreate()

        analytics = Analytics("<write key>", applicationContext) {
            apiHost = "api.segment.build/v1"
            cdnHost = "cdn-settings.segment.build/v1"
            trackDeepLinks = true
            trackApplicationLifecycleEvents = true
            flushAt = 1
            flushInterval = 10
        }

        analytics.add(TwilioEngage(applicationContext, notificationCustomizationCallback = { builder, customization ->
            customization.tapActionButtons?.forEach { button ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(button.link)
                }
                builder.addAction(0, button.text, PendingIntent.getActivity(applicationContext, 101, intent, PendingIntent.FLAG_UPDATE_CURRENT))
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.github.com")
            }
            val pi = PendingIntent.getActivity(applicationContext, 101, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            builder.setContentIntent(pi)
            builder.setDeleteIntent(pi)
        }))
    }
}