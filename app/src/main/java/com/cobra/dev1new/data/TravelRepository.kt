package com.cobra.dev1new.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cobra.dev1new.domain.JourneyId
import com.cobra.dev1new.domain.JourneyReducer
import com.cobra.dev1new.domain.KtxSchedule
import com.cobra.dev1new.domain.LegPhase
import com.cobra.dev1new.domain.LegProgress
import com.cobra.dev1new.domain.SubwayTripSelection
import com.cobra.dev1new.domain.TravelSnapshot
import com.cobra.dev1new.domain.TransitionResult
import com.cobra.dev1new.domain.TransportKind
import com.cobra.dev1new.domain.VehicleArrival
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

private class TravelDatabase(context: Context) : SQLiteOpenHelper(
    context,
    "dev1native.db",
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE journey_snapshot (id INTEGER PRIMARY KEY CHECK(id = 1), json TEXT NOT NULL, updated_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE diagnostic_event (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, event_type TEXT NOT NULL, event_at INTEGER NOT NULL, payload_json TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX diagnostic_event_session_time ON diagnostic_event(session_id, event_at)")
        db.execSQL(
            "CREATE TABLE diagnostic_sample (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, measured_at INTEGER NOT NULL, received_at INTEGER NOT NULL, latitude REAL, longitude REAL, accuracy REAL, speed REAL, payload_json TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX diagnostic_sample_session_time ON diagnostic_sample(session_id, measured_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema upgrades are additive and must never discard a journey or diagnostic record.
        if (oldVersion < 2) {
            // Reserved for the first explicit schema migration.
        }
    }
}

/**
 * Canonical persisted travel state shared by Activity and foreground service.
 * The StateFlow is only an in-process observation of the SQLite row, not a second state store.
 */
class TravelRepository private constructor(context: Context) {
    private val database = TravelDatabase(context.applicationContext)
    private val mutex = Any()
    private val mutableState = MutableStateFlow(readSnapshot())
    val state: StateFlow<TravelSnapshot> = mutableState.asStateFlow()

    fun snapshot(): TravelSnapshot = synchronized(mutex) { mutableState.value }

    fun startInitialPlan(journeyId: JourneyId, nowEpochMillis: Long): TransitionResult =
        update { JourneyReducer.startInitialPlan(it, journeyId, nowEpochMillis) }

    fun cancelPlan(nowEpochMillis: Long): TransitionResult =
        update { JourneyReducer.cancelPlan(it, nowEpochMillis) }

    fun autoBoard(nowEpochMillis: Long): TransitionResult =
        update { JourneyReducer.autoBoard(it, nowEpochMillis) }

    fun manualBoardSubway(
        trip: SubwayTripSelection,
        nowEpochMillis: Long
    ): TransitionResult = update {
        JourneyReducer.manualBoardSubway(it, trip, nowEpochMillis)
    }

    fun updateStopIndex(index: Int, nowEpochMillis: Long): TransitionResult = update {
        JourneyReducer.updateStopIndex(it, index, nowEpochMillis)
    }

    fun markBusPreArrivalAlert(threshold: Int, nowEpochMillis: Long): TransitionResult = update {
        JourneyReducer.markBusPreArrivalAlert(it, threshold, nowEpochMillis)
    }

    fun autoAlight(nowEpochMillis: Long): TransitionResult =
        update { JourneyReducer.autoAlight(it, nowEpochMillis) }

    fun resetForNewDay(nowEpochMillis: Long): TransitionResult =
        update { JourneyReducer.resetForNewDay(it, nowEpochMillis) }

    fun saveKtxSchedule(journeyId: JourneyId, schedule: KtxSchedule, nowEpochMillis: Long) = synchronized(mutex) {
        val next = mutableState.value.copy(
            ktxSchedules = mutableState.value.ktxSchedules + (journeyId to schedule),
            updatedAtEpochMillis = nowEpochMillis,
            lastTransition = "ktx_schedule_saved"
        )
        persistSnapshot(next)
        mutableState.value = next
        next
    }

    fun saveBusArrivals(items: List<VehicleArrival>, fetchedAtEpochMillis: Long) = synchronized(mutex) {
        val current = mutableState.value
        val progress = current.activeProgress()
        if (progress == null || progress.phase != LegPhase.PLANNED ||
            com.cobra.dev1new.domain.RouteCatalog.journey(progress.journeyId).legs[progress.legIndex].kind != TransportKind.BUS
        ) return@synchronized current
        val updated = progress.copy(
            arrivalVehicles = items,
            arrivalFetchedAtEpochMillis = fetchedAtEpochMillis
        )
        val next = current.copy(
            journeys = current.journeys + (progress.journeyId to updated),
            updatedAtEpochMillis = fetchedAtEpochMillis,
            lastTransition = "bus_arrivals_updated"
        )
        persistSnapshot(next)
        mutableState.value = next
        next
    }

    fun savePlannedSubwayText(journeyId: JourneyId, text: String, fetchedAtEpochMillis: Long) = synchronized(mutex) {
        val current = mutableState.value
        val progress = current.activeProgress()
        if (progress == null || progress.journeyId != journeyId || progress.phase != LegPhase.PLANNED ||
            com.cobra.dev1new.domain.RouteCatalog.journey(progress.journeyId).legs[progress.legIndex].kind != TransportKind.SUBWAY ||
            progress.plannedSubwayText == text
        ) return@synchronized current
        val updated = progress.copy(plannedSubwayText = text)
        val next = current.copy(
            journeys = current.journeys + (journeyId to updated),
            updatedAtEpochMillis = fetchedAtEpochMillis,
            lastTransition = "subway_schedule_updated"
        )
        persistSnapshot(next)
        mutableState.value = next
        next
    }

    fun update(transform: (TravelSnapshot) -> TransitionResult): TransitionResult = synchronized(mutex) {
        val result = transform(mutableState.value)
        if (result is TransitionResult.Applied) {
            persistSnapshot(result.snapshot)
            mutableState.value = result.snapshot
        }
        result
    }

    private fun readSnapshot(): TravelSnapshot {
        val db = database.readableDatabase
        db.rawQuery("SELECT json FROM journey_snapshot WHERE id = 1", null).use { cursor ->
            if (!cursor.moveToFirst()) return TravelSnapshot()
            return try {
                decode(JSONObject(cursor.getString(0)))
            } catch (_: Exception) {
                TravelSnapshot(lastTransition = "snapshot_read_error")
            }
        }
    }

    private fun persistSnapshot(snapshot: TravelSnapshot) {
        val values = ContentValues().apply {
            put("id", 1)
            put("json", encode(snapshot).toString())
            put("updated_at", snapshot.updatedAtEpochMillis)
        }
        database.writableDatabase.insertWithOnConflict(
            "journey_snapshot", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun encode(snapshot: TravelSnapshot): JSONObject = JSONObject().apply {
        put("schema_version", 2)
        put("active_journey_id", snapshot.activeJourneyId?.name ?: JSONObject.NULL)
        put("updated_at", snapshot.updatedAtEpochMillis)
        put("last_transition", snapshot.lastTransition)
        val rows = JSONArray()
        snapshot.journeys.values.forEach { progress ->
            val row = JSONObject()
                .put("journey_id", progress.journeyId.name)
                .put("leg_index", progress.legIndex)
                .put("phase", progress.phase.name)
                .put("stop_index", progress.stopIndex)
                .put("planned_at", progress.plannedAtEpochMillis ?: JSONObject.NULL)
                .put("boarded_at", progress.boardedAtEpochMillis ?: JSONObject.NULL)
                .put("journey_started_at", progress.journeyStartedAtEpochMillis ?: JSONObject.NULL)
                .put("arrival_fetched_at", progress.arrivalFetchedAtEpochMillis ?: JSONObject.NULL)
                .put("planned_subway_text", progress.plannedSubwayText)
                .put("bus_two_stop_alert_sent", progress.busTwoStopAlertSent)
                .put("bus_one_stop_alert_sent", progress.busOneStopAlertSent)
            val arrivals = JSONArray()
            progress.arrivalVehicles.forEach { item ->
                arrivals.put(JSONObject()
                    .put("seconds", item.remainingSeconds ?: JSONObject.NULL)
                    .put("minutes", item.remainingMinutes ?: JSONObject.NULL)
                    .put("stops", item.remainingStops ?: JSONObject.NULL)
                    .put("state", item.stateLabel ?: ""))
            }
            row.put("arrival_vehicles", arrivals)
            progress.selectedSubwayTrip?.let { trip ->
                row.put("subway_trip", JSONObject()
                    .put("service_date", trip.serviceDate)
                    .put("train_key", trip.trainKey)
                    .put("departure_at", trip.departureEpochMillis)
                    .put("arrivals", JSONArray(trip.arrivalsEpochMillis))
                    .put("departures", JSONArray(trip.departuresEpochMillis)))
            }
            rows.put(row)
        }
        put("journeys", rows)
        val schedules = JSONArray()
        snapshot.ktxSchedules.forEach { (journeyId, schedule) ->
            schedules.put(JSONObject()
                .put("journey_id", journeyId.name)
                .put("departure_time", schedule.departureTime)
                .put("arrival_time", schedule.arrivalTime)
                .put("train_identifier", schedule.trainIdentifier)
                .put("platform", schedule.platform)
                .put("car", schedule.car)
                .put("seat", schedule.seat))
        }
        put("ktx_schedules", schedules)
    }

    private fun decode(json: JSONObject): TravelSnapshot {
        val journeys = mutableMapOf<JourneyId, LegProgress>()
        val rows = json.optJSONArray("journeys") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val journeyId = runCatching {
                JourneyId.valueOf(row.optString("journey_id"))
            }.getOrNull() ?: continue
            val phase = runCatching {
                LegPhase.valueOf(row.optString("phase"))
            }.getOrDefault(LegPhase.READY)
            val tripJson = row.optJSONObject("subway_trip")
            val trip = tripJson?.let {
                SubwayTripSelection(
                    serviceDate = it.optString("service_date"),
                    trainKey = it.optString("train_key"),
                    departureEpochMillis = it.optLong("departure_at"),
                    arrivalsEpochMillis = it.longList("arrivals"),
                    departuresEpochMillis = it.longList("departures")
                )
            }
            val arrivalRows = row.optJSONArray("arrival_vehicles") ?: JSONArray()
            val arrivals = (0 until arrivalRows.length()).mapNotNull { index ->
                val item = arrivalRows.optJSONObject(index) ?: return@mapNotNull null
                VehicleArrival(
                    remainingSeconds = item.nullableLong("seconds"),
                    remainingMinutes = item.nullableInt("minutes"),
                    remainingStops = item.nullableInt("stops"),
                    stateLabel = item.optString("state").takeIf(String::isNotBlank)
                )
            }
            journeys[journeyId] = LegProgress(
                journeyId = journeyId,
                legIndex = row.optInt("leg_index", 0).coerceAtLeast(0),
                phase = phase,
                stopIndex = row.optInt("stop_index", 0).coerceAtLeast(-1),
                plannedAtEpochMillis = row.nullableLong("planned_at"),
                boardedAtEpochMillis = row.nullableLong("boarded_at"),
                journeyStartedAtEpochMillis = row.nullableLong("journey_started_at"),
                selectedSubwayTrip = trip,
                arrivalVehicles = arrivals,
                arrivalFetchedAtEpochMillis = row.nullableLong("arrival_fetched_at"),
                plannedSubwayText = row.optString("planned_subway_text"),
                busTwoStopAlertSent = row.optBoolean("bus_two_stop_alert_sent", false),
                busOneStopAlertSent = row.optBoolean("bus_one_stop_alert_sent", false)
            )
        }
        val schedules = mutableMapOf<JourneyId, KtxSchedule>()
        val scheduleRows = json.optJSONArray("ktx_schedules") ?: JSONArray()
        for (i in 0 until scheduleRows.length()) {
            val row = scheduleRows.optJSONObject(i) ?: continue
            val journeyId = runCatching { JourneyId.valueOf(row.optString("journey_id")) }.getOrNull() ?: continue
            schedules[journeyId] = KtxSchedule(
                departureTime = row.optString("departure_time"),
                arrivalTime = row.optString("arrival_time"),
                trainIdentifier = row.optString("train_identifier"),
                platform = row.optString("platform"),
                car = row.optString("car"),
                seat = row.optString("seat")
            )
        }
        val active = runCatching {
            JourneyId.valueOf(json.optString("active_journey_id"))
        }.getOrNull()
        return TravelSnapshot(
            activeJourneyId = active,
            journeys = journeys,
            ktxSchedules = schedules,
            updatedAtEpochMillis = json.optLong("updated_at", 0L),
            lastTransition = json.optString("last_transition")
        )
    }

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    private fun JSONObject.longList(key: String): List<Long> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.optLong(it) }
    }

    companion object {
        @Volatile private var instance: TravelRepository? = null

        fun get(context: Context): TravelRepository = instance ?: synchronized(this) {
            instance ?: TravelRepository(context).also { instance = it }
        }
    }
}
