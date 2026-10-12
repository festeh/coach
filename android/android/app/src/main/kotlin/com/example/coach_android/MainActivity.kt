package com.example.coach_android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import com.example.coach_android.ui.navigation.AppNavigation
import com.example.coach_android.ui.theme.CoachTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val TAG = "MainActivity"

        // The forced wall: a blocked app opened while the agent lock was on.
        // It lands here, on the chat tab, so the bottom bar stays in reach —
        // not in a bare chat activity with no way to the rest of the app.
        const val EXTRA_OPEN_CHAT = "EXTRA_OPEN_CHAT"
        const val EXTRA_FORCED = "EXTRA_FORCED"
    }

    // Bumped on every launch that asks for the chat tab; AppNavigation watches
    // it. A counter, not a flag, so a second wall re-navigates even when the
    // first request is still the current one.
    private val chatRequests = mutableIntStateOf(0)

    // A forced wall must not let back fall through to the blocked app right
    // underneath — that would bounce straight into another block. Send the
    // user home instead, once per forced entry.
    private val forcedBack =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                isEnabled = false
                goHome()
            }
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
        onBackPressedDispatcher.addCallback(this, forcedBack)
        handleLaunchIntent(intent)

        ensureNotificationsAllowed()

        // Start the foreground service
        startFocusMonitorService()

        setContent {
            CoachTheme {
                AppNavigation(chatRequestId = chatRequests.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true) {
            chatRequests.intValue++
        }
        if (intent?.getBooleanExtra(EXTRA_FORCED, false) == true) {
            forcedBack.isEnabled = true
        }
    }

    private fun goHome() {
        val homeIntent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        try {
            startActivity(homeIntent)
        } catch (_: Exception) {
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
