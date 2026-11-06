package com.cobra.dev1new.data

import com.cobra.dev1new.domain.JourneyId
import com.cobra.dev1new.domain.KtxSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class KtxTicketStoreTest {
    private fun ticket(
        journey: JourneyId,
        travel: String,
        activate: String,
        train: String,
    ) = ExternalKtxTicket(
        journeyId = journey,
        travelDate = LocalDate.parse(travel),
        activateOn = LocalDate.parse(activate),
        departureTime = if (journey == JourneyId.COMMUTE) "07:04" else "18:49",
        arrivalTime = if (journey == JourneyId.COMMUTE) "07:54" else "19:35",
        trainIdentifier = train,
        platform = if (journey == JourneyId.COMMUTE) "12" else "11",
        car = "7",
        seat = "4A",
    )

    @Test
    fun next_commute_ticket_is_not_activated_before_the_tuesday_handoff() {
        val manual = KtxSchedule(platform = "12A")
        val imported = ticket(JourneyId.COMMUTE, "2026-11-09", "2026-11-03", "KTX 123")

        val before = KtxTicketStore.mergeActiveSchedules(
            mapOf(JourneyId.COMMUTE to manual), listOf(imported), LocalDate.parse("2026-11-02")
        )
        assertEquals(manual, before[JourneyId.COMMUTE])

        val after = KtxTicketStore.mergeActiveSchedules(
            mapOf(JourneyId.COMMUTE to manual), listOf(imported), LocalDate.parse("2026-11-03")
        )[JourneyId.COMMUTE]!!
        assertEquals("2026-11-09", after.travelDate)
        assertEquals("KTX 123", after.trainIdentifier)
        assertEquals("12", after.platform)
    }

    @Test
    fun next_return_ticket_is_not_activated_before_the_saturday_handoff() {
        val imported = ticket(JourneyId.RETURN, "2026-11-13", "2026-11-07", "KTX 456")
        val before = KtxTicketStore.mergeActiveSchedules(
            emptyMap(), listOf(imported), LocalDate.parse("2026-11-06")
        )
        assertTrue(before[JourneyId.RETURN] == null)

        val after = KtxTicketStore.mergeActiveSchedules(
            emptyMap(), listOf(imported), LocalDate.parse("2026-11-07")
        )[JourneyId.RETURN]!!
        assertEquals("2026-11-13", after.travelDate)
        assertEquals("18:49", after.departureTime)
    }
}
