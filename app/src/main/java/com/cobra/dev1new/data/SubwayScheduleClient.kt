package com.cobra.dev1new.data

import android.content.Context
import com.cobra.dev1new.domain.JourneyId
import com.cobra.dev1new.domain.RouteCatalog
import com.cobra.dev1new.domain.SubwayTripSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SubwayScheduleException(message: String) : Exception(message)

data class UpcomingSubwayTrain(
    val departureAtEpochMillis: Long,
    val destinationLabel: String?
) {
    fun label(nowEpochMillis: Long): String {
        val departure = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(departureAtEpochMillis), ZoneId.systemDefault()
        )
        val seconds = Duration.between(
            java.time.Instant.ofEpochMilli(nowEpochMillis),
            java.time.Instant.ofEpochMilli(departureAtEpochMillis)
        ).seconds.coerceAtLeast(0)
        val hhmm = departure.format(DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA))
        return "$hhmm(${(seconds + 59) / 60}분)"
    }
}

/**
 * Reads the same public Daegu Metro CSV timetables as Dev-1. A provider timetable
 * has no train IDs; the full CSV is required to pin one train and its station times.
 */
class SubwayScheduleClient(private val context: Context) {
    suspend fun upcoming(journeyId: JourneyId, nowEpochMillis: Long): List<UpcomingSubwayTrain> =
        withContext(Dispatchers.IO) {
            val leg = subwayLeg(journeyId)
            val now = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(nowEpochMillis), ZoneId.systemDefault()
            )
            val csv = readCsv(assetName(journeyId))
            SubwayCsvTimetableParser.upcoming(csv, leg.stops.first(), now, limit = 2)
                .map { departure ->
                    UpcomingSubwayTrain(
                        departureAtEpochMillis = departure.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        destinationLabel = null
                    )
                }
        }

    /**
     * At the explicit subway-board tap, select the train whose full-schedule
     * origin arrival/departure is closest in absolute time to the tap. Persist
     * its train number and every route-station time so it can never drift to a
     * different train after the display refreshes or the app is backgrounded.
     */
    suspend fun selectNearestTrip(
        journeyId: JourneyId,
        referenceEpochMillis: Long
    ): SubwayTripSelection = withContext(Dispatchers.IO) {
        val leg = subwayLeg(journeyId)
        val reference = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(referenceEpochMillis), ZoneId.systemDefault()
        )
        val selected = SubwayCsvTimetableParser.selectTrain(
            readCsv(assetName(journeyId)), leg.stops.first(), leg.stops, reference
        )
        SubwayTripSelection(
            serviceDate = selected.originDeparture.toLocalDate().toString(),
            trainKey = "${directionLabel(journeyId)}:${selected.trainNumber}",
            departureEpochMillis = selected.originDeparture.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            arrivalsEpochMillis = selected.events.map { it.arrival.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() },
            departuresEpochMillis = selected.events.map { it.departure.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        )
    }

    private fun subwayLeg(journeyId: JourneyId) = RouteCatalog.journey(journeyId).legs
        .firstOrNull { it.kind.name == "SUBWAY" }
        ?: throw SubwayScheduleException("해당 여정에 지하철 구간이 없습니다.")

    private fun assetName(journeyId: JourneyId): String = when (journeyId) {
        JourneyId.COMMUTE -> "timetables/line1_down_20241007.csv"
        JourneyId.RETURN -> "timetables/line1_up.csv"
        else -> throw SubwayScheduleException("병원 독립 경로에는 지하철 구간이 없습니다.")
    }

    private fun directionLabel(journeyId: JourneyId): String = when (journeyId) {
        JourneyId.COMMUTE -> "DOWN"
        JourneyId.RETURN -> "UP"
        else -> throw SubwayScheduleException("병원 독립 경로에는 지하철 구간이 없습니다.")
    }

    private fun readCsv(name: String): String = try {
        context.assets.open(name).use { stream ->
            BufferedReader(InputStreamReader(stream, Charset.forName("MS949"))).use { it.readText() }
        }
    } catch (_: Exception) {
        throw SubwayScheduleException("내장 지하철 시간표를 읽지 못했습니다. 설정된 CSV 자료를 확인해 주세요.")
    }
}

internal data class SubwayStationEvent(val name: String, val arrival: LocalDateTime, val departure: LocalDateTime)
internal data class SelectedSubwayCsvTrain(
    val trainNumber: String,
    val originDeparture: LocalDateTime,
    val events: List<SubwayStationEvent>
)

/** Pure CSV rules, separated from Android assets so the source behavior is unit-testable. */
internal object SubwayCsvTimetableParser {
    private val timePattern = Regex("^\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*$")
    private val quickTimePattern = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?::\\d{2})?")
    private val metadataColumns = setOf("요일별", "역명", "구분", "")

    fun upcoming(
        csvText: String,
        stationName: String,
        referenceTime: LocalDateTime,
        limit: Int
    ): List<LocalDateTime> {
        val (header, rows) = table(csvText)
        val dayPrefix = dayLabel(referenceTime.toLocalDate())
        val station = normalize(stationName)
        val row = rows.firstOrNull { row ->
            row["요일별"].orEmpty().startsWith(dayPrefix) &&
                normalize(row["역명"].orEmpty()) == station &&
                row["구분"].orEmpty().contains("출발")
        } ?: return emptyList()

        return header.drop(3).asSequence()
            .flatMap { column -> quickTimePattern.findAll(row[column].orEmpty()).map { it.groupValues[1] + ":" + it.groupValues[2] } }
            .mapNotNull { value ->
                val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(value) ?: return@mapNotNull null
                runCatching {
                    referenceTime.toLocalDate().atTime(match.groupValues[1].toInt(), match.groupValues[2].toInt())
                }.getOrNull()
            }
            .filter { !it.isBefore(referenceTime) }
            .distinct()
            .sorted()
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    fun selectTrain(
        csvText: String,
        originStation: String,
        routeStations: List<String>,
        referenceTime: LocalDateTime
    ): SelectedSubwayCsvTrain {
        val (header, rows) = table(csvText)
        val weekdayLabel = dayLabel(referenceTime.toLocalDate())
        val serviceRows = rows.filter { it["요일별"].orEmpty().startsWith(weekdayLabel) }
        if (serviceRows.isEmpty()) throw SubwayScheduleException("CSV에서 ${weekdayLabel} 시간표를 찾지 못했습니다.")

        val origin = normalize(originStation)
        var originRows = serviceRows.filter {
            normalize(it["역명"].orEmpty()) == origin && it["구분"].orEmpty().contains("출발")
        }
        if (originRows.isEmpty()) {
            originRows = serviceRows.filter {
                normalize(it["역명"].orEmpty()) == origin && it["구분"].orEmpty().contains("도착")
            }
        }
        if (originRows.isEmpty()) throw SubwayScheduleException("CSV에서 ${originStation} 운행시각을 찾지 못했습니다.")

        val allOriginRows = serviceRows.filter { normalize(it["역명"].orEmpty()) == origin }
        val candidates = header.filterNot { it.trim().removePrefix("\uFEFF") in metadataColumns }
            .mapNotNull { trainNumber ->
                val times = allOriginRows.mapNotNull { row -> serviceDateTime(row[trainNumber], referenceTime) }
                if (times.isEmpty()) null else trainNumber to times
            }
        val chosen = candidates.minByOrNull { (_, times) ->
            times.minOf { kotlin.math.abs(Duration.between(referenceTime, it).toMillis()) }
        } ?: throw SubwayScheduleException("CSV에서 ${originStation} 출발 열차를 찾지 못했습니다.")
        val (trainNumber, originTimes) = chosen
        val originDeparture = originTimes.maxOrNull()!!

        val rowsByStationAndKind = mutableMapOf<Pair<String, String>, Map<String, String>>()
        serviceRows.forEach { row ->
            val station = normalize(row["역명"].orEmpty())
            val kind = if (row["구분"].orEmpty().contains("출발")) "출발" else "도착"
            rowsByStationAndKind[station to kind] = row
        }

        var previousTime: LocalDateTime? = null
        val events = routeStations.map { routeStation ->
            val station = normalize(routeStation)
            val values = linkedMapOf<String, LocalDateTime?>()
            for (kind in listOf("도착", "출발")) {
                val row = rowsByStationAndKind[station to kind]
                var value = row?.let { serviceDateTime(it[trainNumber], referenceTime) }
                val previous = previousTime
                if (value != null && previous != null) {
                    while (value!!.isBefore(previous.minusHours(2))) value = value!!.plusDays(1)
                }
                values[kind] = value
                if (value != null) previousTime = value
            }
            val rawArrival = values["도착"] ?: values["출발"]
            val rawDeparture = values["출발"] ?: values["도착"]
            if (rawArrival == null || rawDeparture == null) {
                throw SubwayScheduleException("${trainNumber} 열차의 ${routeStation} 도착·출발 시각이 없습니다.")
            }
            SubwayStationEvent(routeStation, minOf(rawArrival, rawDeparture), maxOf(rawArrival, rawDeparture))
        }
        return SelectedSubwayCsvTrain(trainNumber, originDeparture, events)
    }

    private fun dayLabel(date: LocalDate): String = when (date.dayOfWeek.value) {
        6 -> "토요일"
        7 -> "휴일"
        else -> "평일"
    }

    private fun normalize(value: String): String = value.replace("역", "").trim()

    private fun serviceDateTime(raw: String?, reference: LocalDateTime): LocalDateTime? {
        val match = timePattern.matchEntire(raw.orEmpty()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val second = match.groupValues[3].toIntOrNull() ?: 0
        var candidate = runCatching { reference.toLocalDate().atTime(hour, minute, second) }.getOrNull() ?: return null
        if (reference.hour >= 18 && hour < 4) candidate = candidate.plusDays(1)
        else if (reference.hour < 4 && hour >= 18) candidate = candidate.minusDays(1)
        return candidate
    }

    private fun table(csvText: String): Pair<List<String>, List<Map<String, String>>> {
        val lines = csvText.lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) throw SubwayScheduleException("지하철 시간표 CSV가 비어 있습니다.")
        val header = parseCsvLine(lines.next()).mapIndexed { index, cell ->
            if (index == 0) cell.removePrefix("\uFEFF").trim() else cell.trim()
        }
        val rows = ArrayList<Map<String, String>>()
        while (lines.hasNext()) {
            val cells = parseCsvLine(lines.next())
            if (cells.size < 3) continue
            rows += header.indices.associate { index -> header[index] to cells.getOrElse(index) { "" }.trim() }
        }
        return header to rows
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    result += cell.toString()
                    cell.clear()
                }
                else -> cell.append(char)
            }
            index++
        }
        result += cell.toString()
        return result
    }
}
