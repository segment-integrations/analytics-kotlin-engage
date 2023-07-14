package com.segment.analytics.kotlin.destinations.engage

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.segment.analytics.kotlin.android.Analytics
import com.segment.analytics.kotlin.android.plugins.AndroidLifecycle
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.set
import com.segment.analytics.kotlin.core.utilities.toJsonElement
import com.segment.analytics.kotlin.core.utilities.updateJsonObject
import kotlinx.serialization.json.*


class TwilioEngage(
    private val context: Context,
    private val statusCallback: StatusCallback? = null,
    tapActionButtonsCallback: TapActionButtonsCallback? = null
): EventPlugin, AndroidLifecycle {

    init {
        RemoteNotifications.tapActionButtonsCallback = tapActionButtonsCallback
    }

    override lateinit var analytics: Analytics

    override val type: Plugin.Type = Plugin.Type.Enrichment

    companion object {
        const val SUBSCRIPTION_TYPE = "ANDROID_PUSH"
        const val CONTEXT_KEY = "messaging_subscriptions"
    }

    enum class Status (val value : String){
        Subscribed("SUBSCRIBED"),
        DidNotSubscribe("DID_NOT_SUBSCRIBE"),
        // TODO: values of Unsubscribed and Disabled are swapped, because backend currently does not recognize DISABLED. swap it back when backend supports DISABLED
        Unsubscribed("DISABLED"),
        Disabled("UNSUBSCRIBED");

        companion object {
            fun from(str: String?): Status? {
                return values().firstOrNull { it.value == str }
            }
        }
    }

    internal enum class Events (val value : String) {
        Tapped("Push Notification Opened"),
        Received("Push Notification Delivered"),
        Registered("Registered for Push Notifications"),
        Unregistered ("Unable to register for Push Notifications"),
        Changed("Push Notifications Subscription Change"),
        Declined ("Push Notifications Subscription Declined");

        companion object {
            fun from(str: String?): Events? {
                return values().firstOrNull { it.value == str }
            }
        }
    }

    private val sharedPreferences =
        context.getSharedPreferences("com.twilio.engage", Context.MODE_PRIVATE)

    var status: Status
        get() {
            val value = sharedPreferences.getString("Status", Status.DidNotSubscribe.value)
            val status = Status.from(value)
            return status ?: Status.Disabled
        }
        set(value) {
            if (status != value) {
                statusCallback?.invoke(status, value)
            }
            sharedPreferences.edit().putString("Status", value.value).apply()
        }

    var deviceToken: String? = null

    var referrer: JsonObject? = null

    override fun setup(analytics: Analytics) {
        super.setup(analytics)

        // save the writeKey to setup a different Analytics instance in firebase service
        // so we can report notification delivery even the app is closed
        RemoteNotifications.analyticsWriteKey = analytics.configuration.writeKey
    }

    override fun reset() {
        super.reset()
    }

    private fun updateStatus() {
        val newStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                PackageManager.PERMISSION_GRANTED -> {
                    Status.Subscribed
                }
                PackageManager.PERMISSION_DENIED -> {
                    Status.Disabled
                }
                else -> {
                    Status.DidNotSubscribe
                }
            }
        } else {
            if(NotificationManagerCompat.from(context).areNotificationsEnabled()) Status.Subscribed else Status.Disabled
        }

        if (newStatus != status) {
            Log.d("TwilioEngageTest", "Push Status Changed, old=$status, new=$newStatus")
            onStatusChanged(newStatus)
            status = newStatus

            analytics.track(Events.Changed.value)
        }
        else if (status == Status.Subscribed && !RemoteNotifications.containsMessageObserver(::receivedNotification)) {
            // if the status does not change, but it is subscribed and token isn't assigned
            // then that means the app is re-launched from a registered state. we need to
            // retrieve the token for this case
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    deviceToken = token
                    RemoteNotifications.registerMessageObserver(::receivedNotification)
                    Log.d("TwilioEngageTest", "Subscribe, token=$token")
                }
        }
    }

    override fun identify(payload: IdentifyEvent): BaseEvent? {
        return attachSubscriptionData(payload)
    }

    override fun track(payload: TrackEvent): BaseEvent? {
        Events.from(payload.event) ?: return payload
        return attachSubscriptionData(payload)
    }

    override fun execute(event: BaseEvent): BaseEvent {
        super.execute(event)
        referrer?.let {
            event.context = updateJsonObject(event.context) { properties ->
                it.forEach { key, value ->
                    properties[key] = value
                }
            }
        }

        return event
    }

    private fun attachSubscriptionData(event: BaseEvent): BaseEvent {
        event.context = updateJsonObject(event.context) {
            it[CONTEXT_KEY] = buildJsonArray {
                add (buildJsonObject {
                    put("key", deviceToken)
                    put("type", SUBSCRIPTION_TYPE)
                    put("status", status.value)
                })
            }
            if (it["device"] == null) {
                it["device"] = buildJsonObject {
                    put("token", deviceToken)
                }
            }
            else {
                it["device"] = updateJsonObject(it["device"] as JsonObject) { device ->
                    device["token"] = deviceToken
                }
            }
        }

        return event
    }

    private fun trackNotification(properties: JsonElement, fromLaunch: Boolean) {
        // no need to track delivery here, we do it in firebase service
        // analytics.track(Events.Received.value, properties)

        if (fromLaunch) {
            analytics.track(Events.Tapped.value, properties)
            Log.d("TwilioEngageTest", "Push Notification Tapped (launch=true)")
        }
    }

    private fun onStatusChanged(status: Status) {
        if (status == Status.Disabled) {
            declinedNotifications()
        } else {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    registeredForNotifications(token)
                }
                .addOnFailureListener { error ->
                    failedToRegisterForNotification(error)
                }
        }
    }

    fun receivedNotification(message: JsonElement) {
        trackNotification(message, false)
    }

    fun declinedNotifications() {
        status = Status.Disabled

        RemoteNotifications.unregisterMessageObserver(::receivedNotification)

        analytics.identify()
        analytics.track(Events.Declined.value)
        Log.d("TwilioEngageTest", "Push Notifications were declined.")
    }

    fun registeredForNotifications(token: String) {
        deviceToken = token
        status = Status.Subscribed

        RemoteNotifications.registerMessageObserver(::receivedNotification)

        analytics.identify()
        analytics.track(Events.Registered.value)
        Log.d("TwilioEngageTest", "Registered for Push Notifications (token=$token)")
    }

    fun failedToRegisterForNotification(error: Exception) {
        status = Status.Unsubscribed

        RemoteNotifications.unregisterMessageObserver(::receivedNotification)

        analytics.identify()
        analytics.track(Events.Unregistered.value, buildJsonObject {
            put("error", error.message)
        })
        Log.d("TwilioEngageTest", "Unable to register for Push Notifications (error=$error)")
    }

    override fun onActivityCreated(activity: Activity?, savedInstanceState: Bundle?) {
        activity?.intent?.extras?.let {bundle ->
            if (bundle.containsKey("push_notification")) {
                bundle.remove("push_notification")
                val payload = buildJsonObject {
                    bundle.keySet().forEach { key ->
                        put(key, bundle.getString(key))
                    }
                }

                referrer = buildJsonObject {
                    put("referrer", Companion::class.java.canonicalName)
                    activity.intent?.data?.let {
                        put("deepLink", it.toString())
                    }
                }

                Log.d("TwilioEngageTest", "onActivityCreated: $payload")
                trackNotification(payload, true)
            }
        }
    }

    override fun onActivityResumed(activity: Activity?) {
        updateStatus()
    }

    override fun onActivityStarted(activity: Activity?) {
        clearBadgeCount()
    }

    private fun clearBadgeCount() {
        sharedPreferences.edit().putInt("BadgeCount", 0).apply()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager?.cancelAll()
        }
    }

}

object RemoteNotifications {
    private val messageObservers: MutableSet<(JsonElement) -> Unit> = mutableSetOf()

    internal var tapActionButtonsCallback: TapActionButtonsCallback? = null

    internal var analyticsWriteKey: String? = null

    fun publishMessage(message: JsonElement) {

        Log.d("TwilioEngageTest", "publishMessage: $message")
        for (listener in messageObservers) {
            listener(message)
        }
    }

    fun registerMessageObserver(observer: (JsonElement) -> Unit) = messageObservers.add(observer)

    fun unregisterMessageObserver(observer: (JsonElement) -> Unit) = messageObservers.remove(observer)

    fun containsMessageObserver(observer: (JsonElement) -> Unit) = messageObservers.contains(observer)
}

class EngageFirebaseMessagingService : FirebaseMessagingService() {

    private val analytics by lazy {
        RemoteNotifications.analyticsWriteKey?.let { writeKey ->
            Analytics(writeKey, applicationContext) {
                apiHost = "api.segment.build/v1"
                cdnHost = "cdn-settings.segment.build/v1"
                trackDeepLinks = true
                trackApplicationLifecycleEvents = true
                flushAt = 1
                flushInterval = 10
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.d("TwilioEngageTest", "onNewToken (token=$token)")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("onMessageReceived", "onMessageReceived")

        // track payload
        val payload = remoteMessage.data.toJsonElement()
        analytics?.track(TwilioEngage.Events.Received.value, payload)
        RemoteNotifications.publishMessage(payload)
        val customizations = Customizations(remoteMessage)

        // compute badge count
        val sharedPreferences =
            applicationContext.getSharedPreferences("com.twilio.engage", Context.MODE_PRIVATE)
        val badgeCount = customizations.computeBadgeCount(sharedPreferences.getInt("BadgeCount", 0))
        sharedPreferences.edit().putInt("BadgeCount", badgeCount).apply()

        // get the intent of the default activity
        val pm: PackageManager = applicationContext.packageManager
        val intent = pm.getLaunchIntentForPackage(applicationContext.packageName)?.apply {
            putExtra("push_notification", true)
            remoteMessage.data.forEach { (key, value) ->
                putExtra(key, value)
            }
            data = customizations.deepLink
        }

        // create notification
        val pi = PendingIntent.getActivity(applicationContext, 101, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = applicationContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("222", "my_channel", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(applicationContext, "222")
            .setSmallIcon(androidx.core.R.drawable.notification_icon_background)
            .setContentTitle(customizations.title)
            .setAutoCancel(true)
            .setContentText(customizations.body)
            .setContentIntent(pi)
            .setDeleteIntent(pi)
            .setPriority(customizations.priority)
            .setNumber(badgeCount)
            .setSound(customizations.sound)

        customizations.tapActionButtons?.let {
            RemoteNotifications.tapActionButtonsCallback?.invoke(builder, it)
        }
        nm.notify(123456789, builder.build())
    }

    class Customizations(private val remoteMessage: RemoteMessage) {
        val title by lazy {
            remoteMessage.data["twi_title"] ?: "Twilio Engage"
        }

        val body by lazy {
            remoteMessage.data["twi_body"] ?: "New message from Twilio Engage"
        }

        val deepLink by lazy {
            if(remoteMessage.data["deepLink"] != null) Uri.parse(remoteMessage.data["deepLink"]) else null
        }

        val badgeAmount by lazy {
            remoteMessage.data["badgeAmount"]?.toIntOrNull() ?: 0
        }

        val badgeStrategy by lazy {
            remoteMessage.data["badgeStrategy"]
        }

        val tapAction by lazy {
            remoteMessage.data["click_action"]
        }

        val sound by lazy {
            if(remoteMessage.data["twi_sound"] != null) Uri.parse(remoteMessage.data["sound"]) else null
        }

        val media by lazy {
            remoteMessage.data["media"]?.let {
                Json.parseToJsonElement(it) as? JsonArray
            }
        }

        val priority by lazy {
            if (remoteMessage.data["priority"] == "low") NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH
        }

        val ttl by lazy {
            remoteMessage.data["timeToLive"]?.toIntOrNull() ?: -1
        }

        val deliveryCallbackUrl by lazy {
            remoteMessage.data["deliveryCallbackUrl"]
        }

        val tapActionButtons by lazy {
            remoteMessage.data["tapActionButtons"]?.let {
                Json.parseToJsonElement(it) as? JsonArray
            }
        }

        fun computeBadgeCount(currentCount: Int): Int {
            val count = when(badgeStrategy) {
                "inc" -> currentCount + badgeAmount
                "dec" -> currentCount - badgeAmount
                "set" -> badgeAmount
                else -> 1
            }

            return if (count > 0) count else 0
        }
    }
}

typealias TapActionButtonsCallback = (builder: NotificationCompat.Builder, tapActionButtons: JsonArray) -> Unit
typealias StatusCallback = (previous: TwilioEngage.Status, current: TwilioEngage.Status) -> Unit