package com.cobra.dev1new.data

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubwayCsvTimetableParserTest {
    private val fixture = """
        요일별,역명,구분,1001,1003
        평일(하),A역,도착,07:59:30,08:29:30
        평일(하),A역,출발,08:00:00,08:30:00
        평일(하),B역,도착,08:10:00,08:40:00
        평일(하),B역,출발,08:10:30,08:40:30
        평일(하),C역,도착,08:20:00,08:50:00
        평일(하),C역,출발,08:20:30,08:50:30
        토요일(하),A역,출발,09:00:00,09:30:00
    """.trimIndent()

    @Test
    fun selectionPinsTheClosestWholeCsvTrainAndItsStationTimes() {
        val reference = LocalDateTime.of(2026, 10, 5, 8, 1)
        val selected = SubwayCsvTimetableParser.selectTrain(fixture, "A역", listOf("A역", "B역", "C역"), reference)

        assertEquals("1001", selected.trainNumber)
        assertEquals(LocalDateTime.of(2026, 10, 5, 8, 0), selected.originDeparture)
        assertEquals(listOf("A역", "B역", "C역"), selected.events.map { it.name })
        assertEquals(LocalDateTime.of(2026, 10, 5, 8, 10), selected.events[1].arrival)
        assertEquals(LocalDateTime.of(2026, 10, 5, 8, 20, 30), selected.events[2].departure)
    }

    @Test
    fun upcomingBoardingDisplayExcludesDeparturesAlreadyPassedAndReturnsOnlyTwo() {
        val now = LocalDateTime.of(2026, 10, 5, 8, 1)
        val result = SubwayCsvTimetableParser.upcoming(fixture, "A", now, limit = 2)

        assertEquals(listOf(LocalDateTime.of(2026, 10, 5, 8, 30)), result)
    }

    @Test
    fun saturdayUsesSaturdayRowsAndLateNightSelectedTrainRollsToNextDate() {
        val saturday = LocalDateTime.of(2026, 10, 10, 9, 1)
        val saturdayTimes = SubwayCsvTimetableParser.upcoming(fixture, "A", saturday, limit = 2)
        assertEquals(listOf(LocalDateTime.of(2026, 10, 10, 9, 30)), saturdayTimes)

        val lateCsv = """
            요일별,역명,구분,9001
            평일(상),A,도착,23:59:00
            평일(상),A,출발,00:02:00
            평일(상),B,도착,00:12:00
            평일(상),B,출발,00:12:30
        """.trimIndent()
        val late = SubwayCsvTimetableParser.selectTrain(
            lateCsv, "A", listOf("A", "B"), LocalDateTime.of(2026, 10, 5, 23, 58)
        )
        assertEquals(LocalDateTime.of(2026, 10, 6, 0, 2), late.originDeparture)
        assertEquals(LocalDateTime.of(2026, 10, 6, 0, 12), late.events[1].arrival)
        assertTrue(late.events[1].departure.isAfter(late.events[1].arrival))
    }
}
