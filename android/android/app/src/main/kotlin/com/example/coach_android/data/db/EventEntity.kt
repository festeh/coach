package com.example.coach_android.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [
        Index("timestamp"),
        Index("event_type"),
    ],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "package_name") val packageName: String? = null,
    @ColumnInfo(name = "during_focus", defaultValue = "0") val duringFocus: Int = 0,
    val duration: Int? = null,
    val metadata: String? = null,
)

@Entity(
    tableName = "rule_counters",
    primaryKeys = ["rule_id", "date"],
)
data class RuleCounterEntity(
    @ColumnInfo(name = "rule_id") val ruleId: String,
    val date: String,
    @ColumnInfo(name = "open_count", defaultValue = "0") val openCount: Int = 0,
    @ColumnInfo(name = "trigger_count", defaultValue = "0") val triggerCount: Int = 0,
)
