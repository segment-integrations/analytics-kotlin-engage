
# Twilio Engage Destination

- [Twilio Engage Destination](#twilio-engage-destination)
  - [Getting Started](#getting-started)
  - [Subscription](#subscription)
  - [Customizations](#customizations)
  - [Predefined Actions](#predefined-actions)
  - [Customized Actions](#customized-actions)
    - [Customized TapAction](#customized-tapaction)
    - [Customized TapActionButtons](#customized-tapactionbuttons)
  - [License](#license)

This plugin enables Segment's Analytics SDK to do push notification management with Twilio Engage.

## Getting Started

To get started:
1. follow the instruction of Analytics Kotlin [here](https://segment.com/docs/connections/sources/catalog/libraries/mobile/kotlin-android/#getting-started) 
to integrate Segment's Analytics SDK to your app. 
2. add the following to your gradle dependencies:

```groovy
    implementation 'com.segment.analytics.kotlin.destinations:engage:<LATEST_VERSION>'
```

3. add the following service to your AndroidManifest.xml `application` tag

```xml
    <service
        android:name="com.segment.analytics.kotlin.destinations.engage.EngageFirebaseMessagingService"
        android:exported="true">
        <intent-filter>
          <action android:name="com.google.firebase.INSTANCE_ID_EVENT"/>
          <action android:name="com.google.firebase.MESSAGING_EVENT" />
        </intent-filter>
    </service>
```

4. add this plugin to your Analytics instance:
```kotlin
    analytics.add(TwilioEngage(applicationContext))
```

## Subscription

Once the plugin is setup, it automatically tracks and updates push notification subscriptions 
according to device's notification permissions. To listen to the subscription status changes, 
provide a `StatusCallback` when initialize the plugin as following:
```kotlin
    TwilioEngage(applicationContext, statusCallback = { previous, current ->  
        // handle status changes            
    })
```

On Android, three different status are tracked: `Subscribed`, `DidNotSubscribe`, `Unsubscribed`. 
* `Subscribed` is reported whenever app user toggles their device settings to allow push notification
* `DidNotSubscribe` is reported in fresh start where no status has ever been reported
* `Unsubscribed` is reported whenever user toggles their device settings to disable push notification and when the SDK fails to obtain a token from FCM

## Customizations

To customize the push notification, provide a `NotificationCustomizationCallback` when initialize 
the plugin as following:
```kotlin
    TwilioEngage(applicationContext, notificationCustomizationCallback = { builder, customization ->
        // customize your notification here
    })
```

In the callback:
* `builder` gives you the full control of build the notification
* `customization` provides you dynamic customization data from the Engage Console

Following is a list of options that `customization` provides:
|Option Name|Description|
|---|---|
|title|The title of the notification|
|body|The body of the notification|
|media|Media urls to send with the notification.|
|badgeAmount|The badge count which is used in combination with badge strategy to determine the final badge.|
|badgeStrategy|Sets the badge count strategy in the notification.|
|sound|Sound to be played|
|priority|High priority uses ring tones and vibrations, low does not|
|ttl|When 0, notification is tried once. Otherwise, its tried until success or ttl (time to live) expires|
|link|A link to route to (deep or url) when the notification is tapped|
|tapAction|action to perform when the notification is tapped|
|tapActionButtons|buttons that shows on notification|

The plugin provides a default implementation for all of the above options, for example, badge counts, 
priority, sounds, etc. But you can make further customization by using the callback.

## Predefined Actions

There are 3 predefined actions that the plugin handles by default. When the value of `tapAction`  
or `tapActionButtons.onTap` is:
* `open_app`: the app opens to the main view when the notification/button is on tapped.
* `open_url`: the default browser opens a webview to the provided `link`
* `deep_link`: the application routes to the provided `link`

## Customized Actions

`tapAction` and `tapActionButtons.onTap` can also be customized using the `NotificationCustomizationCallback`

### Customized TapAction

Customize `tapAction` changes the behavior when user taps the notification. Note that if you have
custom action that is not one of the Predefined Actions, you must handle that action using the following
code below, or tapping on the notification will not have any effect.

```kotlin
    TwilioEngage(applicationContext, notificationCustomizationCallback = { builder, customization ->
        // setup your custom intent and pending intent
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://www.github.com")
        }
        val pi = PendingIntent.getActivity(applicationContext, 101, intent, PendingIntent.FLAG_UPDATE_CURRENT)
    
        // overwrite the default intent
        builder.setContentIntent(pi)
        builder.setDeleteIntent(pi)
    })
```

### Customized TapActionButtons

There are two situations that a `tapActionButton` is not rendered:
* too many `tapActionButtons` are provided. The maximum of `tapActionButtons` supported by Twilio Engage is `3`. 
* custom action that is not one of the Predefined Actions is not handled in `NotificationCustomizationCallback`.

Thus, make sure you are not passing more than 3 buttons in the payload and manually handle custom
action using the code below if it is not a Predefined Action.

```kotlin
    TwilioEngage(applicationContext, notificationCustomizationCallback = { builder, customization ->
        // go through every custom tapActionButtons
        customization.tapActionButtons?.forEach { button ->
            // create an intent for this button
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(button.link)
            }
            // add the button to notification
            builder.addAction(0, button.text, PendingIntent.getActivity(applicationContext, 101, intent, PendingIntent.FLAG_UPDATE_CURRENT))
        }
    })
```


## License
```
MIT License

Copyright (c) 2021 Segment

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
