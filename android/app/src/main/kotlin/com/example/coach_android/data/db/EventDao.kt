package com.example.coach_android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

data class BlockedAppEntry(
    @androidx.room.ColumnInfo(name = "package_name") val packageName: String,
    val count: Int,
)

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity)

    @Query(
        """
        SELECT COUNT(*) FROM events
        WHERE event_type = 'focus_started'
        AND timestamp >= :startTimestamp AND timestamp < :endTimestamp
    """,
    )
    suspend fun countFocusSessions(
        startTimestamp: Long,
        endTimestamp: Long,
    ): Int

    @Query(
        """
        SELECT COALESCE(SUM(duration), 0) FROM events
        WHERE event_type = 'focus_ended'
        AND timestamp >= :startTimestamp AND timestamp < :endTimestamp
    """,
    )
    suspend fun totalFocusTime(
        startTimestamp: Long,
        endTimestamp: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM events
        WHERE event_type = 'app_opened' AND during_focus = 1
        AND timestamp >= :startTimestamp AND timestamp < :endTimestamp
    """,
    )
    suspend fun countBlockedAppOpens(
        startTimestamp: Long,
        endTimestamp: Long,
    ): Int

    @Query(
        """
        SELECT package_name, COUNT(*) as count FROM events
        WHERE event_type = 'app_opened' AND during_focus = 1
        AND timestamp >= :startTimestamp AND timestamp < :endTimestamp
        GROUP BY package_name
        ORDER BY count DESC
    """,
    )
    suspend fun blockedAppsForDay(
        startTimestamp: Long,
        endTimestamp: Long,
    ): List<BlockedAppEntry>
}
