package com.example.coach_android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.coach_android.data.model.FocusData
import com.example.coach_android.util.TimeFormatter

class ServiceNotificationManager(
    private val context: Context,
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = CHANNEL_DESCRIPTION
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }

            notificationManager.createNotificationChannel(channel)
        }
    }

    fun createServiceNotification(
        focusData: FocusData? = null,
        isConnected: Boolean = false,
    ): Notification = buildNotification(focusData, isConnected)

    fun updateNotification(
        focusData: FocusData,
        isConnected: Boolean,
    ) {
        val notification = buildNotification(focusData, isConnected)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        focusData: FocusData?,
        isConnected: Boolean,
    ): Notification {
        val (title, content) = formatNotificationContent(focusData, isConnected)

        val iconRes =
            if (focusData?.isFocused == true) {
                R.drawable.ic_notification_c
            } else {
                R.drawable.ic_notification_c_crossed
            }

        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(iconRes)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun formatNotificationContent(
        focusData: FocusData?,
        isConnected: Boolean,
    ): Pair<String, String> {
        if (focusData == null) {
            val connectionStatus = if (isConnected) STATUS_ACTIVE else STATUS_INACTIVE
            return TITLE_DEFAULT to "$CONTENT_MONITORING • $connectionStatus"
        }

        val title = if (focusData.isFocused) STATUS_FOCUSING else STATUS_NOT_FOCUSING

        val status =
            when {
                focusData.isFocusing && focusData.focusTimeLeft > 0 ->
                    "Manual session: ${TimeFormatter.formatFocusTime(focusData.focusTimeLeft)} left"
                !focusData.isAgentLocked && (focusData.agentReleaseTimeLeft ?: 0) > 0 ->
                    "Agent lock returns in ${TimeFormatter.formatFocusTime(focusData.agentReleaseTimeLeft!!)}"
                focusData.isAgentLocked ->
                    "Agent locked"
                else ->
                    "$CONTENT_MONITORING • ${if (isConnected) STATUS_ACTIVE else STATUS_INACTIVE}"
            }
        val overrideCount = focusData.overridesToday
        val dailyStatus = "$overrideCount ${if (overrideCount == 1) "override" else "overrides"} today"

        return title to "$status • $dailyStatus"
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "focus_monitor_service"
        const val CHANNEL_NAME = "Focus Monitor Service"
        const val CHANNEL_DESCRIPTION = "Monitors apps and shows focus reminders"

        private const val STATUS_ACTIVE = "Active"
        private const val STATUS_INACTIVE = "Inactive"
        private const val STATUS_FOCUSING = "Focusing"
        private const val STATUS_NOT_FOCUSING = "Not focusing"
        private const val TITLE_DEFAULT = "Focus Monitor"
        private const val CONTENT_MONITORING = "Monitoring"
    }
}
