package com.cobra.dev1new.domain

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Chooses only the first tab shown after the app is opened.  It never changes
 * an already selected tab, journey state, GPS recovery, or notifications.
 *
 * A date-bound KTX ticket transferred from dev-3 is authoritative here.
 * dev-3 already moves a Monday/Friday trip around Korean public holidays, so
 * a Tuesday commute or Thursday return is represented by its actual travel
 * date instead of duplicating a second holiday calendar in this app.
 */
object JourneyTabResolver {
    const val COMMUTE_TAB = 0
    const val RETURN_TAB = 1
    const val HOSPITAL_TAB = 2

    fun initialTab(snapshot: TravelSnapshot, today: LocalDate = LocalDate.now()): Int {
        snapshot.activeProgress()?.journeyId?.let(::tabFor)
            ?.let { return it }

        val ticketJourneysToday = listOf(JourneyId.COMMUTE, JourneyId.RETURN).filter { journeyId ->
            snapshot.ktxSchedules[journeyId]?.travelDate == today.toString()
        }
        if (ticketJourneysToday.size == 1) return tabFor(ticketJourneysToday.single())

        return when (today.dayOfWeek) {
            DayOfWeek.FRIDAY -> RETURN_TAB
            else -> COMMUTE_TAB
        }
    }

    private fun tabFor(journeyId: JourneyId): Int = when (journeyId) {
        JourneyId.COMMUTE -> COMMUTE_TAB
        JourneyId.RETURN -> RETURN_TAB
        else -> HOSPITAL_TAB
    }
}
