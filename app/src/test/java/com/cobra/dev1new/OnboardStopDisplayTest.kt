package com.cobra.dev1new

import com.cobra.dev1new.domain.JourneyId
import com.cobra.dev1new.domain.RouteCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardStopDisplayTest {
    @Test
    fun busUsesNextUnpassedStopAsCurrentLocation() {
        val bus = RouteCatalog.journey(JourneyId.COMMUTE).legs.first()

        val justBoarded = requireNotNull(onboardStopDisplay(bus, -1))
        assertEquals(0, justBoarded.currentIndex)
        assertEquals(1, justBoarded.nextIndex)
        assertEquals(bus.stops.size, justBoarded.remainingStops)

        val afterThreeConfirmedStops = requireNotNull(onboardStopDisplay(bus, 2))
        assertEquals(3, afterThreeConfirmedStops.currentIndex)
        assertEquals(4, afterThreeConfirmedStops.nextIndex)
    }

    @Test
    fun subwayUsesStoredStationAsCurrentLocation() {
        val subway = RouteCatalog.journey(JourneyId.COMMUTE).legs[2]

        val display = requireNotNull(onboardStopDisplay(subway, 2))
        assertEquals(2, display.currentIndex)
        assertEquals(3, display.nextIndex)
        assertEquals(subway.stops.size - 2, display.remainingStops)
    }
}
