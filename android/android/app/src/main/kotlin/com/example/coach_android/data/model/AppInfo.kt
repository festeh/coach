package com.example.coach_android.data.model

import android.content.pm.PackageManager

data class AppInfo(
    val name: String,
    val packageName: String,
) {
    companion object {
        fun loadInstalled(pm: PackageManager): List<AppInfo> =
            pm
                .getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .mapNotNull { info ->
                    try {
                        AppInfo(
                            name = info.loadLabel(pm).toString(),
                            packageName = info.packageName,
                        )
                    } catch (_: Exception) {
                        null
                    }
                }.sortedBy { it.name.lowercase() }
    }
}
