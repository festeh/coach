package com.example.coach_android.ui.releases

import com.example.coach_android.data.coach.ReleaseDto
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReleaseDaysTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 10, 12)

    private fun release(
        id: Long,
        created: String,
        kind: String = "grant",
        source: String = "chat",
    ) = ReleaseDto(id = id, kind = kind, source = source, created = created)

    @Test
    fun rowsFoldIntoLocalDaysNewestFirst() {
        val days =
            groupReleasesByDay(
                listOf(
                    release(4, "2026-10-12T12:00:00Z"),
                    release(3, "2026-10-12T07:30:00Z"),
                    // 23:30 UTC is already the 12th in Berlin.
                    release(2, "2026-10-11T23:30:00Z"),
                    release(1, "2026-10-08T10:00:00Z"),
                ),
                zone,
                today,
            )

        assertEquals(listOf(today, LocalDate.of(2026, 10, 8)), days.map { it.date })
        assertEquals(listOf(4L, 3L, 2L), days[0].rows.map { it.release.id })
        assertEquals("Today", days[0].label)
        assertEquals("Thu, Oct 8", days[1].label)
    }

    @Test
    fun yesterdayIsNamed() {
        val days = groupReleasesByDay(listOf(release(1, "2026-10-11T10:00:00Z")), zone, today)

        assertEquals("Yesterday", days.single().label)
    }

    @Test
    fun unreadableTimestampsAreLeftOut() {
        val days =
            groupReleasesByDay(
                listOf(release(2, "not a time"), release(1, "2026-10-12T10:00:00Z")),
                zone,
                today,
            )

        assertEquals(listOf(1L), days.single().rows.map { it.release.id })
    }

    @Test
    fun labelsSayHowTheLockCameOff() {
        assertEquals("OK", releaseLabel(release(1, "", source = "chat")))
        assertEquals("fallback", releaseLabel(release(1, "", source = "fallback")))
        assertEquals("granted", releaseLabel(release(1, "", source = "agent")))
        assertEquals("override", releaseLabel(release(1, "", kind = "override", source = "agent")))
        assertEquals("peek", releaseLabel(release(1, "", kind = "short_unblock", source = "agent")))
        assertEquals("denied", releaseLabel(release(1, "", kind = "denial", source = "agent")))
    }
}
