package com.example.coach_android.util

object TimeFormatter {
    fun formatFocusTime(totalSeconds: Int): String {
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    fun todayString(): String {
        val now = java.time.LocalDate.now()
        return "%04d-%02d-%02d".format(now.year, now.monthValue, now.dayOfMonth)
    }
}
