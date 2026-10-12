package com.example.coach_android.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query

data class RuleCounters(
    @ColumnInfo(name = "open_count") val openCount: Int,
    @ColumnInfo(name = "trigger_count") val triggerCount: Int,
)

@Dao
interface RuleCounterDao {
    @Query(
        """
        INSERT INTO rule_counters (rule_id, date, open_count, trigger_count)
        VALUES (:ruleId, :date, 1, 0)
        ON CONFLICT(rule_id, date) DO UPDATE SET open_count = open_count + 1
    """,
    )
    suspend fun incrementOpenCount(
        ruleId: String,
        date: String,
    )

    @Query("SELECT open_count FROM rule_counters WHERE rule_id = :ruleId AND date = :date")
    suspend fun getOpenCount(
        ruleId: String,
        date: String,
    ): Int?

    @Query("UPDATE rule_counters SET trigger_count = trigger_count + 1 WHERE rule_id = :ruleId AND date = :date")
    suspend fun incrementTriggerCount(
        ruleId: String,
        date: String,
    )

    @Query("SELECT open_count, trigger_count FROM rule_counters WHERE rule_id = :ruleId AND date = :date")
    suspend fun getCounters(
        ruleId: String,
        date: String,
    ): RuleCounters?

    @Query("DELETE FROM rule_counters WHERE rule_id = :ruleId AND date = :date")
    suspend fun resetCounters(
        ruleId: String,
        date: String,
    )

    @Query("DELETE FROM rule_counters WHERE date < :cutoffDate")
    suspend fun cleanupOldCounters(cutoffDate: String): Int
}
