package com.example.coach_android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

data class TemptingAppEntry(
    @androidx.room.ColumnInfo(name = "package_name") val packageName: String,
    val count: Int,
)

data class TemptationDayEntry(
    val day: String,
    val count: Int,
)

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity)

    @Query(
        """
        SELECT date(timestamp, 'unixepoch', 'localtime') AS day, COUNT(*) AS count
        FROM events
        WHERE event_type = 'temptation'
        AND timestamp >= :startTimestamp AND timestamp < :endTimestamp
        GROUP BY day
        ORDER BY day ASC
    """,
    )
    suspend fun temptationsByDay(
        startTimestamp: Long,
        endTimestamp: Long,
    ): List<TemptationDayEntry>

    @Query(
        """
        SELECT package_name, COUNT(*) AS count FROM events
        WHERE event_type = 'temptation' AND package_name IS NOT NULL
        AND timestamp >= :startTimestamp AND timestamp < :endTimestamp
        GROUP BY package_name
        ORDER BY count DESC
    """,
    )
    suspend fun temptingAppsForPeriod(
        startTimestamp: Long,
        endTimestamp: Long,
    ): List<TemptingAppEntry>
}
