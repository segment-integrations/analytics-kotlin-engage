package com.segment.analytics.kotlin.destinations.engage

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.segment.analytics.kotlin.core.*
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.set
import com.segment.analytics.kotlin.core.utilities.toJsonElement
import com.segment.analytics.kotlin.core.utilities.updateJsonObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Plugin that enables Twilio Engage push notification subscriptions and customizations
 * @property context Context of the application
 * @property statusCallback Callback that reports subscription status changes
 * @param notificationCustomizationCallback Callback that provides notification builder and customizations
 */
class TwilioEngage(
    private val context: Context,
    private val statusCallback: StatusCallback? = null,
    notificationCustomizationCallback: NotificationCustomizationCallback? = null
): EventPlugin, AndroidLifecycle {

    init {
        RemoteNotifications.notificationCustomizationCallback = notificationCustomizationCallback
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
        Unsubscribed("UNSUBSCRIBED");

        companion object {
            fun from(str: String?): Status? {
                return values().firstOrNull { it.value == str }
            }
        }
    }

    internal enum class Events (val value : String) {
        Tapped("Notification Opened"),
        Received("Notification Delivered"),
        Registered("Registered for Notifications"),
        Unregistered ("Unable to Register for Notifications"),
        Changed("Notifications Subscription Change"),
        Declined ("Notifications Subscription Declined");

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
            return status ?: Status.Unsubscribed
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
        // we have to save the following info for purpose of testing against stage env.
        RemoteNotifications.analyticsApiHost = analytics.configuration.apiHost
        RemoteNotifications.analyticsCdnHost = analytics.configuration.cdnHost
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
                    Status.Unsubscribed
                }
                else -> {
                    Status.DidNotSubscribe
                }
            }
        } else {
            if(NotificationManagerCompat.from(context).areNotificationsEnabled()) Status.Subscribed else Status.Unsubscribed
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
        if (status == Status.Unsubscribed) {
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
        status = Status.Unsubscribed

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

    internal var notificationCustomizationCallback: NotificationCustomizationCallback? = null

    internal var analyticsWriteKey: String? = null

    internal var analyticsApiHost: String? = null

    internal var analyticsCdnHost: String? = null

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
                apiHost = RemoteNotifications.analyticsApiHost ?: Constants.DEFAULT_API_HOST
                cdnHost = RemoteNotifications.analyticsCdnHost ?: Constants.DEFAULT_CDN_HOST
                // we want to track notification delivery right away
                flushAt = 1
                // no need to periodical flushing, since this analytics instance is only used for
                // tracking notification delivery event
                flushInterval = 0
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

        // create notification
        val nm = applicationContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("222", "my_channel", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(applicationContext, "222")
            .setContentTitle(customizations.title)
            .setSmallIcon(applicationInfo.icon)
            .setAutoCancel(true)
            .setContentText(customizations.body)
            .setPriority(customizations.priority)
            .setNumber(badgeCount)
            .setSound(customizations.sound)

        // customizations
        attachTapAction(builder, customizations)
        attachTapActionButtons(builder, customizations)
        RemoteNotifications.notificationCustomizationCallback?.invoke(builder, customizations)

        // show the notification
        nm.notify(123456789, builder.build())
    }

    /**
     * attach top level tap action (action performed when user hits the notification)
     */
    private fun attachTapAction(builder: NotificationCompat.Builder, customizations: Customizations) {
        val pi = getPredefinedActionIntent(customizations.tapAction, customizations.link, customizations.remoteMessage)
        builder.setContentIntent(pi)
            .setDeleteIntent(pi)
    }

    /**
     * attach action buttons to notification and filter out predefined actions buttons
     */
    private fun attachTapActionButtons(builder: NotificationCompat.Builder, customizations: Customizations) {
        val customActionButtons = mutableListOf<TapActionButton>()

        customizations.tapActionButtons?.forEach {
            val uri = if (it.link != null) Uri.parse(it.link) else null
            val action = getPredefinedAction(it.text, it.onTap, uri, customizations.remoteMessage)
            builder.addAction(action)

            //  if it's not a predefined action, we need to keep it for callback
            if (action == null) {
                customActionButtons.add(it)
            }
        }

        // overwrite `tapActionButtons` with unhandled action buttons
        // so developer can handle them in the callback
        customizations.tapActionButtons = customActionButtons
    }

    /**
     * returns a `NotificationCompat.Action`, which defines the action button's appearance and action
     */
    private fun getPredefinedAction(text: String?, onTap: String?, link: Uri?, remoteMessage: RemoteMessage): NotificationCompat.Action? {
        val intent = getPredefinedActionIntent(onTap, link, remoteMessage)
        return when (onTap) {
            "open_app" -> {
                // passing `0` to set icon to null
                NotificationCompat.Action(0, text, intent)
            }
            "open_url" -> {
                NotificationCompat.Action(0, text, intent)
            }
            "deep_link" -> {
                NotificationCompat.Action(0, text, intent)
            }
            else -> null
        }
    }

    /**
     * returns a `PendingIntent` that defines the action of a tap. can be used for the following:
     *      1. top level tap action (action that performed when the push is tapped)
     *      2. action button's action (action that performed when the action button is tapped)
     */
    private fun getPredefinedActionIntent(onTap: String?, link: Uri?, remoteMessage: RemoteMessage): PendingIntent? {
        val intent = when (onTap) {
            "open_app" -> {
                // get the intent of the default activity
                packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            }
            "open_url" -> {
                Intent(Intent.ACTION_VIEW).apply {
                    data = link
                }
            }
            "deep_link" -> {
                // get the intent of the default activity
                packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.apply {
                    putExtra("push_notification", true)
                    remoteMessage.data.forEach { (key, value) ->
                        putExtra(key, value)
                    }
                    data = link
                }
            }
            else -> return null
        }

        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(applicationContext, 101, intent, flag)
    }

    /**
     * Class that holds `RemoteMessage` from FCM and decode them to properties for easier access
     */
    class Customizations(val remoteMessage: RemoteMessage) {
        val title by lazy {
            remoteMessage.data["twi_title"] ?: "Twilio Engage"
        }

        val body by lazy {
            remoteMessage.data["twi_body"] ?: "New message from Twilio Engage"
        }

        val link by lazy {
            if(remoteMessage.data["link"] != null) Uri.parse(remoteMessage.data["link"]) else null
        }

        val badgeAmount by lazy {
            remoteMessage.data["badgeAmount"]?.toIntOrNull() ?: 0
        }

        val badgeStrategy by lazy {
            remoteMessage.data["badgeStrategy"]
        }

        val tapAction by lazy {
            remoteMessage.data["twi_action"]
        }

        val sound by lazy {
            if(remoteMessage.data["twi_sound"] != null) Uri.parse(remoteMessage.data["twi_sound"]) else null
        }

        val media by lazy {
            remoteMessage.data["media"]?.let {
                Json.decodeFromString<List<String>>(it)
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

        var tapActionButtons = remoteMessage.data["tapActionButtons"]?.let {
            Json.decodeFromString<List<TapActionButton>>(it)
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

    /**
     * Class that decodes `tapActionButton` object for easier access to its properties
     */
    @Serializable
    data class TapActionButton(val id: String, val text: String, val onTap: String, val link: String? = null)
}

typealias NotificationCustomizationCallback = (builder: NotificationCompat.Builder, customization: EngageFirebaseMessagingService.Customizations) -> Unit
typealias StatusCallback = (previous: TwilioEngage.Status, current: TwilioEngage.Status) -> Unit