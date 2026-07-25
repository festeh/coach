package com.example.coach_android

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.example.coach_android.data.agentchat.AgentChatService
import com.example.coach_android.data.coach.UnlockStatsRepository
import com.example.coach_android.di.AppContainer
import com.example.coach_android.service.MonitorLogic
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class FocusMonitorService : Service() {
    private val binder = FocusMonitorBinder()
    private lateinit var notificationManager: ServiceNotificationManager
    private lateinit var appMonitor: AppMonitorHandler

    private var monitorLogic: MonitorLogic? = null
    private var agentChatService: AgentChatService? = null
    private var unlockStats: UnlockStatsRepository? = null
    private var overlayManager: OverlayManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val isRunning = AtomicBoolean(false)

    companion object {
        const val TAG = "FocusMonitorService"
        const val ACTION_START_SERVICE = "START_FOCUS_MONITOR"
        const val ACTION_STOP_SERVICE = "STOP_FOCUS_MONITOR"

        private var serviceInstance: FocusMonitorService? = null

        fun getInstance(): FocusMonitorService? = serviceInstance
    }

    inner class FocusMonitorBinder : Binder() {
        fun getService(): FocusMonitorService = this@FocusMonitorService
    }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this

        Log.d(TAG, "Service onCreate")

        notificationManager = ServiceNotificationManager(this)
        appMonitor = AppMonitorHandler(this)

        notificationManager.createNotificationChannel()

        initializeMonitorLogic()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.d(TAG, "Service onStartCommand with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_SERVICE, null -> {
                startForegroundService()
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundService()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")

        stopForegroundService()
        serviceScope.cancel()
        monitorLogic?.dispose()
        monitorLogic = null
        agentChatService?.dispose()
        agentChatService = null
        serviceInstance = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed - scheduling service restart")

        val restartIntent =
            Intent(applicationContext, FocusMonitorService::class.java).apply {
                action = ACTION_START_SERVICE
            }

        val pendingIntent =
            PendingIntent.getService(
                applicationContext,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent,
        )
    }

    private fun startForegroundService() {
        if (isRunning.get()) {
            Log.d(TAG, "Service already running")
            return
        }

        Log.d(TAG, "Starting foreground service")

        val focusData = monitorLogic?.focusData?.value
        val isConnected = monitorLogic?.getWebSocketConnectionStatus()?.get("isConnected") as? Boolean ?: false
        val notification = notificationManager.createServiceNotification(focusData, isConnected)
        startForeground(ServiceNotificationManager.NOTIFICATION_ID, notification)

        isRunning.set(true)
        appMonitor.reconcilePermission()

        Log.d(TAG, "Service started")
    }

    private fun stopForegroundService() {
        if (!isRunning.get()) {
            Log.d(TAG, "Service not running")
            return
        }

        Log.d(TAG, "Stopping foreground service")

        isRunning.set(false)
        appMonitor.stopMonitoring()

        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    // --- MonitorLogic initialization ---

    private fun initializeMonitorLogic() {
        try {
            Log.d(TAG, "Initializing MonitorLogic...")

            // Get or create AppContainer
            val app = application
            val webSocketUrl = getWebSocketUrl()
            val agentsUrl = getAgentsUrl()
            val container = AppContainer(app, webSocketUrl, agentsUrl, BuildConfig.COACH_API_TOKEN)

            val logic = container.monitorLogic
            monitorLogic = logic
            agentChatService = container.agentChatService
            unlockStats =
                UnlockStatsRepository(
                    service = container.unlockStatsService,
                    prefs = container.preferencesManager,
                )
            logic.applicationContext = applicationContext

            // Wire overlay manager directly (no Activity dependency)
            val overlay = OverlayManager(applicationContext)
            overlay.onChallengeCompleted = { ruleId -> logic.onChallengeCompleted(ruleId) }
            overlayManager = overlay
            logic.overlayManager = overlay

            logic.initialize()

            // Collect flows from MonitorLogic
            serviceScope.launch {
                logic.focusData.collect { data ->
                    if (!isRunning.get()) return@collect
                    notificationManager.updateNotification(
                        data,
                        logic.getWebSocketConnectionStatus()["isConnected"] as? Boolean ?: false,
                    )
                }
            }

            Log.d(TAG, "MonitorLogic initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MonitorLogic", e)
        }
    }

    private fun getWebSocketUrl(): String = BuildConfig.WEBSOCKET_URL

    private fun getAgentsUrl(): String = BuildConfig.AGENTS_URL

    // --- Called by AppMonitorHandler ---

    fun notifyAppDetected(packageName: String) {
        monitorLogic?.onAppChanged(packageName)
    }

    fun getMonitorLogic(): MonitorLogic? = monitorLogic

    fun getAgentChatService(): AgentChatService? = agentChatService

    fun getUnlockStats(): UnlockStatsRepository? = unlockStats

    fun reconcileMonitoringPermission() {
        if (isRunning.get()) {
            appMonitor.reconcilePermission()
        }
    }
}
