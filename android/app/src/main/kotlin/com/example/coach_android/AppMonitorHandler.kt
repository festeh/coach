package com.example.coach_android

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.coach_android.util.Permissions

class AppMonitorHandler(
    private val context: Context,
) {
    companion object {
        const val TAG = "AppMonitorHandler"
        const val MONITORING_INTERVAL = 2000L // 2 seconds
        const val USAGE_STATS_INTERVAL = 5000L // 5 seconds for usage stats check
    }

    private val handler = Handler(Looper.getMainLooper())
    private var monitoringRunnable: Runnable? = null
    private var isMonitoring = false
    private var lastForegroundApp: String? = null
    private var previousForegroundApp: String? = null
    private var lastEventTimestamp: Long = 0

    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager?

    fun reconcilePermission() {
        if (Permissions.hasUsageStats(context)) {
            startMonitoring()
        } else {
            stopMonitoring()
        }
    }

    private fun startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "Already monitoring")
            return
        }

        if (!Permissions.hasUsageStats(context)) {
            Log.w(TAG, "Usage stats permission not granted, cannot start monitoring")
            return
        }

        Log.d(TAG, "Starting app monitoring")
        isMonitoring = true

        monitoringRunnable =
            object : Runnable {
                override fun run() {
                    try {
                        checkForegroundApp()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in monitoring loop", e)
                    }

                    if (isMonitoring) {
                        handler.postDelayed(this, MONITORING_INTERVAL)
                    }
                }
            }

        handler.post(monitoringRunnable!!)
    }

    fun stopMonitoring() {
        if (!isMonitoring) {
            Log.d(TAG, "Not monitoring")
            return
        }

        Log.d(TAG, "Stopping app monitoring")
        isMonitoring = false

        monitoringRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }
        monitoringRunnable = null
        lastForegroundApp = null
    }

    private fun checkForegroundApp() {
        if (usageStatsManager == null) return

        val time = System.currentTimeMillis()
        val usageEvents = usageStatsManager.queryEvents(time - USAGE_STATS_INTERVAL, time)

        var newForegroundDetected = false
        var currentAppBackgrounded = false

        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)
            if (event.timeStamp <= lastEventTimestamp) continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    lastEventTimestamp = event.timeStamp
                    newForegroundDetected = true
                    val packageName = event.packageName
                    if (packageName != lastForegroundApp) {
                        Log.d(TAG, "Detected foreground app change: $lastForegroundApp -> $packageName")
                        previousForegroundApp = lastForegroundApp
                        lastForegroundApp = packageName
                        FocusMonitorService.getInstance()?.notifyAppDetected(packageName)
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    lastEventTimestamp = event.timeStamp
                    if (event.packageName == lastForegroundApp) {
                        currentAppBackgrounded = true
                    }
                }
            }
        }

        // Launcher/recents went to background but no new foreground detected —
        // the previous app likely resumed without generating an event
        if (currentAppBackgrounded && !newForegroundDetected && previousForegroundApp != null) {
            Log.d(TAG, "Inferred return to previous app: $previousForegroundApp")
            lastForegroundApp = previousForegroundApp
            FocusMonitorService.getInstance()?.notifyAppDetected(previousForegroundApp!!)
        }

        lastForegroundApp?.let {
            FocusMonitorService.getInstance()?.getMonitorLogic()?.ensureOverlay(it)
        }
    }
}
