package com.example.coach_android.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFormatter {
    private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

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

    // "Today", "Yesterday", or a short date like "Mon, Oct 12".
    fun dayLabel(
        date: LocalDate,
        today: LocalDate,
    ): String =
        when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(dayFormat)
        }

    fun clockTime(
        at: Instant,
        zone: ZoneId,
    ): String = at.atZone(zone).format(clockFormat)

    fun todayString(): String {
        val now = java.time.LocalDate.now()
        return "%04d-%02d-%02d".format(now.year, now.monthValue, now.dayOfMonth)
    }
}
