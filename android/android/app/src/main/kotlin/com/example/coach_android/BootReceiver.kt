package com.example.coach_android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.d(TAG, "Boot completed or package replaced - starting FocusMonitorService")

            val serviceIntent =
                Intent(context, FocusMonitorService::class.java).apply {
                    action = FocusMonitorService.ACTION_START_SERVICE
                }
            context.startForegroundService(serviceIntent)
        }
    }
}
