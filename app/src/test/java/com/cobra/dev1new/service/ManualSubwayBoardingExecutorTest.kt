package com.cobra.dev1new.service

import com.cobra.dev1new.domain.JourneyId
import com.cobra.dev1new.domain.JourneyReducer
import com.cobra.dev1new.domain.LegPhase
import com.cobra.dev1new.domain.LegProgress
import com.cobra.dev1new.domain.RouteCatalog
import com.cobra.dev1new.domain.SubwayTripSelection
import com.cobra.dev1new.domain.TravelSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSubwayBoardingExecutorTest {
    private val tappedAt = 7_000L
    private val trip = SubwayTripSelection(
        serviceDate = "2026-11-09",
        trainKey = "DOWN:test",
        departureEpochMillis = 10_000L,
        arrivalsEpochMillis = RouteCatalog.journey(JourneyId.COMMUTE).legs[2].stops.indices.map { 10_000L + it * 60_000L },
        departuresEpochMillis = RouteCatalog.journey(JourneyId.COMMUTE).legs[2].stops.indices.map { 20_000L + it * 60_000L }
    )

    @Test
    fun notification_command_selects_a_train_and_applies_the_same_subway_reducer_transition() = runBlocking {
        val before = TravelSnapshot(
            activeJourneyId = JourneyId.COMMUTE,
            journeys = mapOf(JourneyId.COMMUTE to LegProgress(JourneyId.COMMUTE, 2, LegPhase.PLANNED))
        )
        var selectedForJourney: JourneyId? = null
        var appliedSnapshot: TravelSnapshot? = null

        val result = ManualSubwayBoardingExecutor.execute(
            before,
            tappedAt,
            selectNearestTrip = { journeyId, requestedAt ->
                selectedForJourney = journeyId
                assertEquals(tappedAt, requestedAt)
                trip
            },
            applyBoarding = { selected, requestedAt ->
                val transition = JourneyReducer.manualBoardSubway(before, selected, requestedAt)
                appliedSnapshot = when (transition) {
                    is com.cobra.dev1new.domain.TransitionResult.Applied -> transition.snapshot
                    is com.cobra.dev1new.domain.TransitionResult.Rejected -> null
                }
                transition
            }
        )

        assertEquals(JourneyId.COMMUTE, selectedForJourney)
        assertEquals(ManualSubwayBoardingExecutor.Result.Applied(trip), result)
        assertEquals(LegPhase.ONBOARD, appliedSnapshot?.activeProgress()?.phase)
        assertEquals(trip, appliedSnapshot?.activeProgress()?.selectedSubwayTrip)
    }

    @Test
    fun notification_command_does_not_select_or_mutate_when_the_active_leg_is_not_planned_subway() = runBlocking {
        val plannedKtx = TravelSnapshot(
            activeJourneyId = JourneyId.COMMUTE,
            journeys = mapOf(JourneyId.COMMUTE to LegProgress(JourneyId.COMMUTE, 1, LegPhase.PLANNED))
        )
        var invoked = false

        val result = ManualSubwayBoardingExecutor.execute(
            plannedKtx,
            tappedAt,
            selectNearestTrip = { _, _ -> invoked = true; trip },
            applyBoarding = { _, _ -> invoked = true; error("must not run") }
        )

        assertEquals(ManualSubwayBoardingExecutor.Result.Ignored, result)
        assertTrue(!invoked)
    }
}
