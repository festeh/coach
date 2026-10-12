package com.example.coach_android.ui.stats

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coach_android.FocusMonitorService
import com.example.coach_android.data.coach.UnlockDayDto
import com.example.coach_android.data.coach.UnlockStatsRepository
import com.example.coach_android.data.db.EventDao
import com.example.coach_android.data.db.UsageDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

enum class StatsRange(
    val days: Int,
    val label: String,
) {
    WEEK(7, "7 days"),
    MONTH(30, "30 days"),
    QUARTER(90, "90 days"),
}

data class InteractionDay(
    val date: LocalDate,
    val temptations: Int = 0,
    val unlocks: Int = 0,
    val unlockedSeconds: Int = 0,
)

data class TemptingApp(
    val packageName: String,
    val appName: String,
    val count: Int,
)

data class StatsUiState(
    val range: StatsRange = StatsRange.WEEK,
    val overridesToday: Int = 0,
    val days: List<InteractionDay> = emptyList(),
    val temptingApps: List<TemptingApp> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
) {
    val totalTemptations: Int get() = days.sumOf { it.temptations }
    val totalUnlocks: Int get() = days.sumOf { it.unlocks }
    val totalUnlockedSeconds: Int get() = days.sumOf { it.unlockedSeconds }
}

class StatsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val eventDao: EventDao = UsageDatabase.create(application).eventDao()
    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    private var focusJob: Job? = null
    private var refreshJob: Job? = null

    fun onStart() {
        observeLiveOverrideCount()
        refresh()
    }

    fun onStop() {
        focusJob?.cancel()
        focusJob = null
        refreshJob?.cancel()
        refreshJob = null
    }

    fun selectRange(range: StatsRange) {
        if (_state.value.range == range) return
        _state.value = _state.value.copy(range = range)
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        val range = _state.value.range
        _state.value =
            _state.value.copy(
                isLoading = _state.value.days.isEmpty(),
                isRefreshing = _state.value.days.isNotEmpty(),
                error = null,
            )

        refreshJob =
            viewModelScope.launch {
                try {
                    val today = LocalDate.now()
                    val firstDay = today.minusDays(range.days.toLong() - 1)
                    val zone = ZoneId.systemDefault()
                    val start = firstDay.atStartOfDay(zone).toEpochSecond()
                    val end = today.plusDays(1).atStartOfDay(zone).toEpochSecond()

                    val result =
                        coroutineScope {
                            val local =
                                async(Dispatchers.IO) {
                                    eventDao.temptationsByDay(start, end) to
                                        eventDao.temptingAppsForPeriod(start, end)
                                }
                            val remote =
                                async {
                                    val repository = awaitUnlockStatsRepository()
                                    repository?.fetchWindow(range.days)
                                        ?: repository?.stats?.value.orEmpty()
                                }
                            local.await() to remote.await()
                        }

                    if (_state.value.range != range) return@launch

                    val (local, unlockRows) = result
                    val (temptationRows, appRows) = local
                    val temptationsByDate =
                        temptationRows.associate { row ->
                            LocalDate.parse(row.day) to row.count
                        }
                    val unlocksByDate = unlockRows.toUnlockMap()
                    val days =
                        (0 until range.days).map { offset ->
                            val date = firstDay.plusDays(offset.toLong())
                            val unlock = unlocksByDate[date]
                            InteractionDay(
                                date = date,
                                temptations = temptationsByDate[date] ?: 0,
                                unlocks = unlock?.unlocks ?: 0,
                                unlockedSeconds = unlock?.unlockedSeconds ?: 0,
                            )
                        }
                    val apps =
                        appRows.take(MAX_APPS).map { row ->
                            TemptingApp(
                                packageName = row.packageName,
                                appName = resolveAppName(row.packageName),
                                count = row.count,
                            )
                        }

                    _state.value =
                        _state.value.copy(
                            days = days,
                            temptingApps = apps,
                            isLoading = false,
                            isRefreshing = false,
                            error = null,
                        )
                } catch (e: Exception) {
                    _state.value =
                        _state.value.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = e.message ?: "Could not load activity",
                        )
                }
            }
    }

    // The service is started just before Compose is attached, so its singleton
    // may trail the first frame by a fraction of a second. Wait for it instead
    // of pinning the day's override count to zero for this whole tab visit.
    private fun observeLiveOverrideCount() {
        if (focusJob?.isActive == true) return
        focusJob =
            viewModelScope.launch {
                while (isActive) {
                    val focusData =
                        FocusMonitorService
                            .getInstance()
                            ?.getMonitorLogic()
                            ?.focusData
                    if (focusData == null) {
                        delay(SERVICE_RETRY_MS)
                        continue
                    }
                    focusData.collect { data ->
                        _state.value = _state.value.copy(overridesToday = data.overridesToday)
                    }
                    delay(SERVICE_RETRY_MS)
                }
            }
    }

    private suspend fun awaitUnlockStatsRepository(): UnlockStatsRepository? {
        repeat(SERVICE_RETRY_COUNT) {
            val repository = FocusMonitorService.getInstance()?.getUnlockStats()
            if (repository != null) return repository
            delay(SERVICE_RETRY_MS)
        }
        return null
    }

    private fun List<UnlockDayDto>.toUnlockMap(): Map<LocalDate, UnlockDayDto> =
        mapNotNull { row ->
            runCatching { LocalDate.parse(row.day) }.getOrNull()?.let { it to row }
        }.toMap()

    private fun resolveAppName(packageName: String): String =
        try {
            val pm = getApplication<Application>().packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            info.loadLabel(pm).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }

    private companion object {
        const val MAX_APPS = 8
        const val SERVICE_RETRY_COUNT = 20
        const val SERVICE_RETRY_MS = 250L
    }
}
