package com.cobra.dev1new.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class JourneyReducerTest {
    private fun applied(result: TransitionResult): TravelSnapshot {
        assertTrue("Expected an applied transition, got $result", result is TransitionResult.Applied)
        return (result as TransitionResult.Applied).snapshot
    }

    @Test
    fun commuteAutomaticallyChainsAroundOneManualSubwayBoarding() {
        var state = applied(JourneyReducer.startInitialPlan(TravelSnapshot(), JourneyId.COMMUTE, 1_000L))
        assertEquals(LegPhase.PLANNED, state.activeProgress()?.phase)
        assertEquals("commute_514", RouteCatalog.journey(JourneyId.COMMUTE).legs[state.activeProgress()!!.legIndex].id)

        state = applied(JourneyReducer.autoBoard(state, 2_000L))
        state = applied(JourneyReducer.autoAlight(state, 3_000L))
        assertEquals(1, state.activeProgress()?.legIndex)
        assertEquals(LegPhase.PLANNED, state.activeProgress()?.phase)

        state = applied(JourneyReducer.autoBoard(state, 4_000L))
        state = applied(JourneyReducer.autoAlight(state, 5_000L))
        assertEquals(2, state.activeProgress()?.legIndex)
        val forbiddenAutoBoard = JourneyReducer.autoBoard(state, 6_000L)
        assertTrue(forbiddenAutoBoard is TransitionResult.Rejected)

        val stationCount = RouteCatalog.journey(JourneyId.COMMUTE).legs[2].stops.size
        val trip = SubwayTripSelection(
            serviceDate = "2026-10-04",
            trainKey = "dongdaegu-to-ansim",
            departureEpochMillis = 10_000L,
            arrivalsEpochMillis = (0 until stationCount).map { 10_000L + it * 60_000L },
            departuresEpochMillis = (0 until stationCount).map { 10_000L + it * 60_000L + 20_000L }
        )
        state = applied(JourneyReducer.manualBoardSubway(state, trip, 7_000L))
        assertEquals(trip, state.activeProgress()?.selectedSubwayTrip)
        state = applied(JourneyReducer.autoAlight(state, 8_000L))
        assertEquals(3, state.activeProgress()?.legIndex)
        assertEquals(LegPhase.PLANNED, state.activeProgress()?.phase)
        assertEquals("commute_donggu4_1", RouteCatalog.journey(JourneyId.COMMUTE).legs[3].id)
    }

    @Test
    fun hospitalDirectionsRequireSeparateManualPlansAndCompleteIndependently() {
        var state = applied(JourneyReducer.startInitialPlan(TravelSnapshot(), JourneyId.HOSPITAL_OUTBOUND, 1_000L))
        assertEquals(JourneyId.HOSPITAL_OUTBOUND, state.activeJourneyId)
        assertEquals(LegPhase.PLANNED, state.activeProgress()?.phase)
        state = applied(JourneyReducer.autoBoard(state, 2_000L))
        state = applied(JourneyReducer.autoAlight(state, 3_000L))

        assertEquals(null, state.activeJourneyId)
        assertEquals(LegPhase.COMPLETE, state.journeys[JourneyId.HOSPITAL_OUTBOUND]?.phase)
        assertEquals(null, state.journeys[JourneyId.HOSPITAL_RETURN])

        state = applied(JourneyReducer.startInitialPlan(state, JourneyId.HOSPITAL_RETURN, 4_000L))
        assertEquals(JourneyId.HOSPITAL_RETURN, state.activeJourneyId)
        assertEquals(LegPhase.PLANNED, state.activeProgress()?.phase)
        state = applied(JourneyReducer.autoBoard(state, 5_000L))
        state = applied(JourneyReducer.autoAlight(state, 6_000L))

        assertEquals(null, state.activeJourneyId)
        assertEquals(LegPhase.COMPLETE, state.journeys[JourneyId.HOSPITAL_OUTBOUND]?.phase)
        assertEquals(LegPhase.COMPLETE, state.journeys[JourneyId.HOSPITAL_RETURN]?.phase)
    }

    @Test
    fun cancellingPlanLeavesNoActiveJourneyAndCanStartAgain() {
        var state = applied(JourneyReducer.startInitialPlan(TravelSnapshot(), JourneyId.RETURN, 1_000L))
        state = applied(JourneyReducer.cancelPlan(state, 2_000L))
        assertEquals(null, state.activeJourneyId)
        assertEquals(LegPhase.READY, state.journeys[JourneyId.RETURN]?.phase)

        state = applied(JourneyReducer.startInitialPlan(state, JourneyId.RETURN, 3_000L))
        assertEquals(LegPhase.PLANNED, state.activeProgress()?.phase)
    }

    @Test
    fun commuteStartedYesterdayResetsButTripCrossingWithinSameDateDoesNot() {
        val zone = ZoneId.systemDefault()
        val startedYesterday = LocalDateTime.of(2026, 10, 4, 23, 55).atZone(zone).toInstant().toEpochMilli()
        val afterMidnight = LocalDateTime.of(2026, 10, 5, 0, 3).atZone(zone).toInstant().toEpochMilli()
        val todayStart = LocalDateTime.of(2026, 10, 5, 23, 58).atZone(zone).toInstant().toEpochMilli()
        val nextDay = LocalDateTime.of(2026, 10, 6, 0, 2).atZone(zone).toInstant().toEpochMilli()
        val oldPlan = applied(JourneyReducer.startInitialPlan(TravelSnapshot(), JourneyId.COMMUTE, startedYesterday))

        assertTrue(JourneyReducer.resetForNewDay(oldPlan, afterMidnight) is TransitionResult.Applied)
        assertTrue(JourneyReducer.resetForNewDay(
            applied(JourneyReducer.startInitialPlan(TravelSnapshot(), JourneyId.RETURN, todayStart)), nextDay
        ) is TransitionResult.Applied)
        assertTrue(JourneyReducer.resetForNewDay(oldPlan, startedYesterday + 60_000L) is TransitionResult.Rejected)
    }
}
