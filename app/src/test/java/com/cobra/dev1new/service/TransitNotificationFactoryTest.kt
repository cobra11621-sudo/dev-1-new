package com.cobra.dev1new.service

import com.cobra.dev1new.domain.JourneyId
import com.cobra.dev1new.domain.KtxSchedule
import com.cobra.dev1new.domain.LegPhase
import com.cobra.dev1new.domain.LegProgress
import com.cobra.dev1new.domain.RouteCatalog
import com.cobra.dev1new.domain.TravelSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class TransitNotificationFactoryTest {
    private fun onboard(journeyId: JourneyId, legIndex: Int, stopIndex: Int) = TravelSnapshot(
        activeJourneyId = journeyId,
        journeys = mapOf(
            journeyId to LegProgress(
                journeyId = journeyId,
                legIndex = legIndex,
                phase = LegPhase.ONBOARD,
                stopIndex = stopIndex
            )
        ),
        ktxSchedules = mapOf(
            journeyId to KtxSchedule(
                departureTime = "07:04",
                arrivalTime = "08:01",
                trainIdentifier = "005"
            )
        )
    )

    @Test
    fun ktx_terminal_does_not_repeat_the_destination_for_commute_or_return() {
        val commuteLeg = RouteCatalog.journey(JourneyId.COMMUTE).legs[1]
        val returningLeg = RouteCatalog.journey(JourneyId.RETURN).legs[2]

        val commute = TransitNotificationFactory.text(
            onboard(JourneyId.COMMUTE, 1, commuteLeg.stops.lastIndex), true, ""
        )
        val returning = TransitNotificationFactory.text(
            onboard(JourneyId.RETURN, 2, returningLeg.stops.lastIndex), true, ""
        )

        assertEquals("0개/동대구역", commute.body)
        assertEquals("0개/대전역", returning.body)
    }

    @Test
    fun bus_onboard_now_bar_text_keeps_its_existing_current_and_next_stop_contract() {
        val text = TransitNotificationFactory.text(
            onboard(JourneyId.COMMUTE, legIndex = 0, stopIndex = -1), true, ""
        )

        assertEquals("514(대전역)", text.title)
        assertEquals("11개/한밭초등학교/탄방중학교", text.shortCriticalText)
        assertEquals(text.shortCriticalText, text.body)
    }

    @Test
    fun subway_terminal_keeps_the_door_side_only_in_the_now_bar_line() {
        val commuteLeg = RouteCatalog.journey(JourneyId.COMMUTE).legs[2]
        val returningLeg = RouteCatalog.journey(JourneyId.RETURN).legs[1]

        val commute = TransitNotificationFactory.text(
            onboard(JourneyId.COMMUTE, 2, commuteLeg.stops.lastIndex), true, ""
        )
        val returning = TransitNotificationFactory.text(
            onboard(JourneyId.RETURN, 1, returningLeg.stops.lastIndex), true, ""
        )

        assertEquals("1개/안심역/왼쪽", commute.shortCriticalText)
        assertEquals("1개/안심역", commute.body)
        assertEquals("1개/동대구역/오른쪽", returning.shortCriticalText)
        assertEquals("1개/동대구역", returning.body)
    }

    @Test
    fun commute_subway_planned_text_uses_the_verified_three_four_transfer_position() {
        val snapshot = TravelSnapshot(
            activeJourneyId = JourneyId.COMMUTE,
            journeys = mapOf(
                JourneyId.COMMUTE to LegProgress(
                    journeyId = JourneyId.COMMUTE,
                    legIndex = 2,
                    phase = LegPhase.PLANNED
                )
            )
        )

        val text = TransitNotificationFactory.text(snapshot, true, "18:07(0분)/18:18(11분)")

        assertEquals("18:07(0분)/18:18(11분)/3-4", text.body)
    }

    @Test
    fun return_subway_planned_text_uses_the_verified_six_four_transfer_position() {
        val snapshot = TravelSnapshot(
            activeJourneyId = JourneyId.RETURN,
            journeys = mapOf(
                JourneyId.RETURN to LegProgress(
                    journeyId = JourneyId.RETURN,
                    legIndex = 1,
                    phase = LegPhase.PLANNED
                )
            )
        )

        val text = TransitNotificationFactory.text(snapshot, true, "18:07(0분)/18:18(11분)")

        assertEquals("18:07(0분)/18:18(11분)/6-4", text.shortCriticalText)
        assertEquals(text.shortCriticalText, text.body)
    }

    @Test
    fun planned_ktx_now_bar_title_includes_train_departure_and_arrival() {
        val snapshot = TravelSnapshot(
            activeJourneyId = JourneyId.COMMUTE,
            journeys = mapOf(
                JourneyId.COMMUTE to LegProgress(
                    journeyId = JourneyId.COMMUTE,
                    legIndex = 1,
                    phase = LegPhase.PLANNED
                )
            ),
            ktxSchedules = mapOf(
                JourneyId.COMMUTE to KtxSchedule(
                    departureTime = "07:04",
                    arrivalTime = "08:01",
                    trainIdentifier = "005",
                    platform = "11",
                    car = "8",
                    seat = "3A"
                )
            )
        )

        val text = TransitNotificationFactory.text(snapshot, true, "", 1_800_000_000_000L)

        assertEquals("005, 07:04, 08:01", text.title)
        assertEquals("11/8/3A", text.shortCriticalText)
        assertEquals("11번홈 · 8호차 · 3A좌석", text.body)
    }
}
