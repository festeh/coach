package com.example.coach_android.ui.stats

import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coach_android.data.db.EventDao
import com.example.coach_android.data.db.UsageDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class AppUsageEntry(
    val packageName: String,
    val appName: String,
    val totalTimeMs: Long,
)

data class BlockedAppEntry(
    val packageName: String,
    val appName: String,
    val count: Int,
)

data class StatsUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val focusSessions: Int = 0,
    val totalFocusTime: Int = 0,
    val blockedAppOpens: Int = 0,
    val appUsage: List<AppUsageEntry> = emptyList(),
    val blockedApps: List<BlockedAppEntry> = emptyList(),
    val isLoading: Boolean = true,
)

class StatsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val db = UsageDatabase.create(application)
    private val eventDao: EventDao = db.eventDao()
    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        loadStats()
    }

    fun previousDay() {
        _state.value = _state.value.copy(selectedDate = _state.value.selectedDate.minusDays(1))
        loadStats()
    }

    fun nextDay() {
        val next = _state.value.selectedDate.plusDays(1)
        if (!next.isAfter(LocalDate.now())) {
            _state.value = _state.value.copy(selectedDate = next)
            loadStats()
        }
    }

    fun goToToday() {
        _state.value = _state.value.copy(selectedDate = LocalDate.now())
        loadStats()
    }

    private fun loadStats() {
        val date = _state.value.selectedDate
        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toEpochSecond()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toEpochSecond()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sessions = eventDao.countFocusSessions(dayStart, dayEnd)
                val totalTime = eventDao.totalFocusTime(dayStart, dayEnd)
                val blocked = eventDao.countBlockedAppOpens(dayStart, dayEnd)

                val blockedApps =
                    eventDao.blockedAppsForDay(dayStart, dayEnd).map { entry ->
                        BlockedAppEntry(
                            packageName = entry.packageName,
                            appName = resolveAppName(entry.packageName),
                            count = entry.count,
                        )
                    }

                val appUsage = loadAppUsage(dayStart * 1000, dayEnd * 1000)

                _state.value =
                    _state.value.copy(
                        focusSessions = sessions,
                        totalFocusTime = totalTime,
                        blockedAppOpens = blocked,
                        appUsage = appUsage,
                        blockedApps = blockedApps,
                        isLoading = false,
                    )
            } catch (_: Exception) {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    private fun loadAppUsage(
        startTimeMs: Long,
        endTimeMs: Long,
    ): List<AppUsageEntry> {
        val usm =
            getApplication<Application>()
                .getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyList()

        return usm
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTimeMs, endTimeMs)
            .filter { it.totalTimeInForeground > 0 }
            // The system keeps several rollup buckets per package (splits at
            // bucket boundaries and reboots), so one day can return the same
            // app more than once. Merge them or apps show up twice.
            .groupBy { it.packageName }
            .map { (pkg, stats) ->
                AppUsageEntry(
                    packageName = pkg,
                    appName = resolveAppName(pkg),
                    totalTimeMs = stats.sumOf { it.totalTimeInForeground },
                )
            }.sortedByDescending { it.totalTimeMs }
    }

    private fun resolveAppName(packageName: String): String =
        try {
            val pm = getApplication<Application>().packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            info.loadLabel(pm).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
}
