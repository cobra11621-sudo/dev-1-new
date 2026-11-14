package com.cobra.dev1new.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KtxScheduleResolverTest {
    @Test
    fun nearestServiceClockCanResolveAcrossMidnight() {
        val zone = ZoneId.systemDefault()
        val reference = LocalDateTime.of(2026, 10, 4, 23, 50).atZone(zone).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 10, 5, 0, 10).atZone(zone).toInstant().toEpochMilli()

        assertEquals(expected, KtxScheduleResolver.resolveNearest("00:10", reference))
    }

    @Test
    fun invalidClockIsRejectedAndTrainNumberIsNormalized() {
        assertNull(KtxScheduleResolver.resolveNearest("25:90", 1_800_000_000_000L))
        assertEquals("054", KtxScheduleResolver.notificationTrainIdentifier("KTX-산천 054"))
    }

    @Test
    fun imported_travel_date_is_used_instead_of_the_reference_day() {
        val zone = ZoneId.systemDefault()
        val schedule = KtxSchedule(
            departureTime = "07:31",
            arrivalTime = "08:24",
            travelDate = "2026-11-16"
        )
        val reference = LocalDateTime.of(2026, 11, 14, 18, 0).atZone(zone).toInstant().toEpochMilli()
        val expectedDeparture = LocalDateTime.of(LocalDate.of(2026, 11, 16), java.time.LocalTime.of(7, 31))
            .atZone(zone).toInstant().toEpochMilli()

        assertEquals(expectedDeparture, KtxScheduleResolver.departureEpochMillis(schedule, reference))
    }

    @Test
    fun invalid_nonblank_travel_date_does_not_fall_back_to_another_day() {
        val schedule = KtxSchedule(departureTime = "07:31", travelDate = "not-a-date")

        assertNull(KtxScheduleResolver.departureEpochMillis(schedule, 1_800_000_000_000L))
    }
}
