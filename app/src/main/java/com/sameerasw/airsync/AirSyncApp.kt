package com.sameerasw.airsync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.AirBridgeClient
import com.sameerasw.airsync.utils.WebSocketMessageHandler
import com.sameerasw.airsync.crash.CrashNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.sameerasw.airsync.utils.WebSocketUtil
import android.util.Log

class AirSyncApp : Application() {
    private var activityCount = 0
    private lateinit var bleConnectionManager: com.sameerasw.airsync.data.ble.BleConnectionManager

    companion object {
        private var instance: AirSyncApp? = null
        fun getContext(): Application? = instance
        fun isAppForeground(): Boolean = instance?.isForeground() ?: false
        fun getBleConnectionManager(): com.sameerasw.airsync.data.ble.BleConnectionManager? =
            instance?.bleConnectionManager
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        CrashNotificationHelper.createChannel(this)

        bleConnectionManager = com.sameerasw.airsync.data.ble.BleConnectionManager(this)
        bleConnectionManager.start()

        initAirBridge()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                activityCount++
            }

            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                activityCount--
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun isForeground(): Boolean = activityCount > 0

    private fun initAirBridge() {
        // Wire message handler: relay messages → existing WebSocket message pipeline
        AirBridgeClient.setMessageHandler { context, message ->
            WebSocketMessageHandler.handleIncomingMessage(context, message)
        }

        // Auto-connect if previously enabled
        CoroutineScope(Dispatchers.IO).launch {
            val ds = DataStoreManager.getInstance(this@AirSyncApp)
            val enabled = ds.getAirBridgeEnabled().first()
            if (enabled) {
                // Give LAN a 1-second head start if we appear to be on a private LAN.
                if (WebSocketUtil.isLanNegotiationAllowed(this@AirSyncApp)) {
                    Log.d("AirSyncApp", "Private LAN detected on startup, delaying Relay by 1000ms for fast-LAN.")
                    delay(1000)
                }
                AirBridgeClient.connect(this@AirSyncApp)
            }
        }
    }
}
