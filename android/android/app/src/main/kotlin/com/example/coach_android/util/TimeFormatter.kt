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

    // A ticking countdown needs its seconds. formatFocusTime rounds them away,
    // so a 72-second cooldown would sit on "1m" for a full minute.
    fun formatCountdown(totalSeconds: Int): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        return if (seconds < 60) "${seconds}s" else "%d:%02d".format(seconds / 60, seconds % 60)
    }

    fun todayString(): String {
        val now = java.time.LocalDate.now()
        return "%04d-%02d-%02d".format(now.year, now.monthValue, now.dayOfMonth)
    }
}
