package com.example.coach_android.ui.debug

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import com.example.coach_android.FocusMonitorService
import com.example.coach_android.util.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DebugUiState(
    val hasUsageStats: Boolean = false,
    val hasNotifications: Boolean = false,
    val hasOverlay: Boolean = false,
    val hasBatteryExclusion: Boolean = false,
    val isServiceRunning: Boolean = false,
    val wsStatus: Map<String, Any?> = emptyMap(),
)

class DebugViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(DebugUiState())
    val state: StateFlow<DebugUiState> = _state.asStateFlow()

    fun refresh() {
        val app = getApplication<Application>()
        val service = FocusMonitorService.getInstance()

        _state.value =
            DebugUiState(
                hasUsageStats = Permissions.hasUsageStats(app),
                // The lived truth, not the raw permission: this also reads false
                // when notifications are blocked for the app in Settings, which
                // mutes the service notification just the same.
                hasNotifications = NotificationManagerCompat.from(app).areNotificationsEnabled(),
                hasOverlay = Settings.canDrawOverlays(app),
                hasBatteryExclusion = checkBatteryExclusion(app),
                isServiceRunning = service != null,
                wsStatus = service?.getMonitorLogic()?.getWebSocketConnectionStatus() ?: emptyMap(),
            )
    }

    private fun checkBatteryExclusion(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestNotifications() {
        val app = getApplication<Application>()
        val intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        app.startActivity(intent)
    }

    fun requestUsageStats() {
        val intent =
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        getApplication<Application>().startActivity(intent)
    }

    fun requestOverlay() {
        val app = getApplication<Application>()
        val intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        app.startActivity(intent)
    }

    @android.annotation.SuppressLint("BatteryLife")
    fun requestBatteryExclusion() {
        val app = getApplication<Application>()
        val intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${app.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        app.startActivity(intent)
    }

    fun startService() {
        val app = getApplication<Application>()
        val intent =
            Intent(app, FocusMonitorService::class.java).apply {
                action = FocusMonitorService.ACTION_START_SERVICE
            }
        app.startForegroundService(intent)
    }

    fun stopService() {
        val app = getApplication<Application>()
        val intent =
            Intent(app, FocusMonitorService::class.java).apply {
                action = FocusMonitorService.ACTION_STOP_SERVICE
            }
        app.startService(intent)
    }

    fun refreshFocusState() {
        FocusMonitorService.getInstance()?.getMonitorLogic()?.refreshFocusState()
    }
}
