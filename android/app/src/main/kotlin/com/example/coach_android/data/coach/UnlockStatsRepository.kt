package com.example.coach_android.data.coach

import com.example.coach_android.data.preferences.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

// Serves the coach's unlock ledger to the UI: the cache first so the header can
// draw immediately, then the server's answer, which wins.
class UnlockStatsRepository(
    private val service: UnlockStatsService,
    private val prefs: PreferencesManager,
) {
    private val _stats = MutableStateFlow(freshCache())
    val stats: StateFlow<List<UnlockDayDto>> = _stats.asStateFlow()

    suspend fun refresh(days: Int = TREND_DAYS) {
        val fetched = service.fetch(days) ?: return
        prefs.saveUnlockStats(fetched)
        _stats.value = fetched
    }

    // A longer window for the detail view. Not cached and not folded into stats:
    // the card's week is what gets persisted, and a month of history would make
    // its sparkline mean something else.
    suspend fun fetchWindow(days: Int): List<UnlockDayDto>? = service.fetch(days)

    // One page of the release journal for the Releases screen; never cached,
    // since the list is read once per visit and pages further back on demand.
    suspend fun fetchReleases(before: Long?): ReleasePageDto? = service.fetchReleases(before, RELEASE_PAGE_SIZE)

    // A cache whose last day isn't today is describing a day that has ended, and
    // "3 unlocks today" would be a lie. Drop it and wait for the fetch.
    private fun freshCache(): List<UnlockDayDto> {
        val cached = prefs.loadUnlockStats()
        val today = LocalDate.now().toString()
        return if (cached.lastOrNull()?.day == today) cached else emptyList()
    }

    companion object {
        const val TREND_DAYS = 7
        const val RELEASE_PAGE_SIZE = 50
    }
}
