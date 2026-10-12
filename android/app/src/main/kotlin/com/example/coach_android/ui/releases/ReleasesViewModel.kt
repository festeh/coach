package com.example.coach_android.ui.releases

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coach_android.FocusMonitorService
import com.example.coach_android.data.coach.ReleaseDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// One release as the screen shows it: its local time, and a word for how the
// lock came off.
data class ReleaseRow(
    val release: ReleaseDto,
    val at: ZonedDateTime,
    val label: String,
)

data class ReleaseDay(
    val date: LocalDate,
    val label: String,
    val rows: List<ReleaseRow>,
)

data class ReleasesUiState(
    val releases: List<ReleaseDto> = emptyList(),
    // The cursor to the next, older page; null once the journal's start is shown.
    val nextBefore: Long? = null,
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val error: String? = null,
)

class ReleasesViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ReleasesUiState())
    val state: StateFlow<ReleasesUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    // Every visit starts from the newest page, so a release made a minute ago
    // is on top.
    fun onStart() = load(before = null)

    fun loadOlder() {
        val current = _state.value
        val before = current.nextBefore ?: return
        if (current.loading || current.error != null) return
        load(before)
    }

    // After a failure: the first page again if it never arrived, else the page
    // that failed.
    fun retry() = load(if (_state.value.loadedOnce) _state.value.nextBefore else null)

    private fun load(before: Long?) {
        loadJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        loadJob =
            viewModelScope.launch {
                val repository = FocusMonitorService.getInstance()?.getUnlockStats()
                val page = repository?.fetchReleases(before)
                _state.value =
                    if (page == null) {
                        _state.value.copy(
                            loading = false,
                            error = if (repository == null) "Coach service is not running" else "Couldn't reach Coach",
                        )
                    } else {
                        _state.value.copy(
                            releases = if (before == null) page.decisions else _state.value.releases + page.decisions,
                            nextBefore = page.nextBefore,
                            loading = false,
                            loadedOnce = true,
                        )
                    }
            }
    }
}

// What kind of release a row was, in a word for its badge.
fun releaseLabel(release: ReleaseDto): String =
    when (release.kind) {
        "grant" ->
            when (release.source) {
                // Before the coach asked, the judge granted.
                "agent" -> "granted"
                "fallback" -> "fallback"
                else -> "OK"
            }
        "override" -> "override"
        "short_unblock" -> "peek"
        "denial" -> "denied"
        else -> release.kind
    }

private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")

// Newest-first rows folded into local days, in the order they came. A day cut
// by a page boundary continues when the next page is appended. Rows with an
// unreadable timestamp are left out rather than filed under a wrong day.
fun groupReleasesByDay(
    releases: List<ReleaseDto>,
    zone: ZoneId,
    today: LocalDate,
): List<ReleaseDay> {
    val days = mutableListOf<ReleaseDay>()
    for (release in releases) {
        val at =
            try {
                OffsetDateTime.parse(release.created).atZoneSameInstant(zone)
            } catch (_: Exception) {
                continue
            }
        val row = ReleaseRow(release, at, releaseLabel(release))
        val date = at.toLocalDate()
        val last = days.lastOrNull()
        if (last?.date == date) {
            days[days.lastIndex] = last.copy(rows = last.rows + row)
        } else {
            val label =
                when (date) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> date.format(dayFormat)
                }
            days += ReleaseDay(date, label, listOf(row))
        }
    }
    return days
}
