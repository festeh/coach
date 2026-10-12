package com.example.coach_android.util

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

object Permissions {
    // Usage Access is a special-access permission: it never passes through the
    // runtime permission system, so the only way to read it back is AppOps.
    // Monitoring is gated on this, so the service and the debug page have to
    // agree on the answer.
    fun hasUsageStats(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode =
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
