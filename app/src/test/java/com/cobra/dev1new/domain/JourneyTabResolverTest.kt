package com.cobra.dev1new.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyTabResolverTest {
    @Test
    fun monday_defaults_to_commute_and_friday_defaults_to_return() {
        assertEquals(JourneyTabResolver.COMMUTE_TAB, JourneyTabResolver.initialTab(
            TravelSnapshot(), LocalDate.of(2026, 11, 16)
        ))
        assertEquals(JourneyTabResolver.RETURN_TAB, JourneyTabResolver.initialTab(
            TravelSnapshot(), LocalDate.of(2026, 11, 20)
        ))
    }

    @Test
    fun holiday_shifted_ticket_date_selects_commute_or_return_on_its_actual_day() {
        val shiftedCommute = TravelSnapshot(ktxSchedules = mapOf(
            JourneyId.COMMUTE to KtxSchedule(travelDate = "2026-10-06")
        ))
        val shiftedReturn = TravelSnapshot(ktxSchedules = mapOf(
            JourneyId.RETURN to KtxSchedule(travelDate = "2026-10-08")
        ))

        assertEquals(JourneyTabResolver.COMMUTE_TAB, JourneyTabResolver.initialTab(
            shiftedCommute, LocalDate.of(2026, 10, 6)
        ))
        assertEquals(JourneyTabResolver.RETURN_TAB, JourneyTabResolver.initialTab(
            shiftedReturn, LocalDate.of(2026, 10, 8)
        ))
    }

    @Test
    fun active_journey_overrides_today_ticket_so_live_trip_is_not_hidden() {
        val snapshot = TravelSnapshot(
            activeJourneyId = JourneyId.RETURN,
            journeys = mapOf(JourneyId.RETURN to LegProgress(JourneyId.RETURN, 2, LegPhase.PLANNED)),
            ktxSchedules = mapOf(JourneyId.COMMUTE to KtxSchedule(travelDate = "2026-11-16"))
        )

        assertEquals(JourneyTabResolver.RETURN_TAB, JourneyTabResolver.initialTab(
            snapshot, LocalDate.of(2026, 11, 16)
        ))
    }
}
