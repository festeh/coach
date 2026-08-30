package com.example.coach_android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.coach_android.ui.navigation.AppNavigation
import com.example.coach_android.ui.theme.CoachTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val TAG = "MainActivity"
    }

    // On Android 13+ notifications are opt-in, and a side-loaded install never
    // sees an install-time prompt. Without the grant the system silently drops
    // the foreground service's status notification — the service still runs,
    // there is just no icon saying so.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Log.w(TAG, "Notifications stay hidden until POST_NOTIFICATIONS is granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        enableEdgeToEdge()

        ensureNotificationsAllowed()

        // Start the foreground service
        startFocusMonitorService()

        setContent {
            CoachTheme {
                AppNavigation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Special-access permissions are changed in Android Settings, outside
        // our Activity. Reconcile whenever the user returns so monitoring starts
        // immediately after Usage Access is granted (and stops if it is revoked).
        FocusMonitorService.getInstance()?.reconcileMonitoringPermission()
    }

    private fun ensureNotificationsAllowed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startFocusMonitorService() {
        Log.d(TAG, "Starting FocusMonitorService")
        val intent =
            Intent(this, FocusMonitorService::class.java).apply {
                action = FocusMonitorService.ACTION_START_SERVICE
            }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service", e)
        }
    }
}
