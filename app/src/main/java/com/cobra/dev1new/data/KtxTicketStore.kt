package com.cobra.dev1new.data

import android.content.Context
import com.cobra.dev1new.domain.JourneyId
import com.cobra.dev1new.domain.KtxSchedule
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Date-bound KTX tickets received from dev-3.  These are separate from the
 * manual settings; dev-3 provides editable default platform data per ticket.
 */
data class ExternalKtxTicket(
    val journeyId: JourneyId,
    val travelDate: LocalDate,
    val activateOn: LocalDate,
    val departureTime: String,
    val arrivalTime: String,
    val trainIdentifier: String,
    val platform: String,
    val car: String,
    val seat: String,
)

object KtxTicketStore {
    private const val PREFS = "external_ktx_tickets"
    private const val KEY_TICKETS = "tickets_json"

    fun read(context: Context): List<ExternalKtxTicket> = parse(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TICKETS, "[]").orEmpty()
    )

    fun replace(context: Context, rawJson: String): List<ExternalKtxTicket> {
        val tickets = parse(rawJson)
        val canonical = JSONArray().apply {
            tickets.forEach { ticket -> put(JSONObject()
                .put("journey_id", ticket.journeyId.name)
                .put("travel_date", ticket.travelDate.toString())
                .put("activate_on", ticket.activateOn.toString())
                .put("departure_time", ticket.departureTime)
                .put("arrival_time", ticket.arrivalTime)
                .put("train_identifier", ticket.trainIdentifier)
                .put("platform", ticket.platform)
                .put("car", ticket.car)
                .put("seat", ticket.seat)
            ) }
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TICKETS, canonical.toString()).apply()
        return tickets
    }

    /**
     * Use the nearest eligible ticket for each route.  A ticket is held until
     * the day after the preceding same-direction trip, then becomes active;
     * this avoids replacing this Monday's ticket with next Monday's early.
     */
    fun mergeActiveSchedules(
        current: Map<JourneyId, KtxSchedule>,
        tickets: List<ExternalKtxTicket>,
        today: LocalDate = LocalDate.now(),
    ): Map<JourneyId, KtxSchedule> {
        val next = current.toMutableMap()
        listOf(JourneyId.COMMUTE, JourneyId.RETURN).forEach { journeyId ->
            val candidate = activeTicketFor(tickets, journeyId, today)
            val previous = next[journeyId] ?: KtxSchedule()
            if (candidate != null) {
                next[journeyId] = KtxSchedule(
                    departureTime = candidate.departureTime,
                    arrivalTime = candidate.arrivalTime,
                    trainIdentifier = candidate.trainIdentifier,
                    platform = candidate.platform.ifBlank { previous.platform },
                    car = candidate.car,
                    seat = candidate.seat,
                    travelDate = candidate.travelDate.toString(),
                )
            } else if (previous.travelDate.isNotBlank() && runCatching {
                    LocalDate.parse(previous.travelDate).isBefore(today)
                }.getOrDefault(false)) {
                // A past imported ticket must never masquerade as the next
                // week's train.  Preserve only the user's manual platform.
                next[journeyId] = KtxSchedule(platform = previous.platform)
            }
        }
        return next
    }

    /** The same ticket-selection rule used for both the saved schedule and its UI source color. */
    fun activeTicketFor(
        tickets: List<ExternalKtxTicket>,
        journeyId: JourneyId,
        today: LocalDate = LocalDate.now(),
    ): ExternalKtxTicket? = tickets.asSequence()
        .filter { it.journeyId == journeyId }
        .filter { !it.activateOn.isAfter(today) && !it.travelDate.isBefore(today) }
        .minByOrNull { it.travelDate }

    private fun parse(rawJson: String): List<ExternalKtxTicket> = runCatching {
        val rows = JSONArray(rawJson)
        buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val journeyId = runCatching { JourneyId.valueOf(row.optString("journey_id")) }.getOrNull() ?: continue
                val travelDate = runCatching { LocalDate.parse(row.optString("travel_date")) }.getOrNull() ?: continue
                val activateOn = runCatching { LocalDate.parse(row.optString("activate_on")) }.getOrNull() ?: continue
                val departure = row.optString("departure_time")
                val arrival = row.optString("arrival_time")
                val train = row.optString("train_identifier")
                if (departure.isBlank() || arrival.isBlank() || train.isBlank()) continue
                add(ExternalKtxTicket(
                    journeyId, travelDate, activateOn, departure, arrival, train,
                    row.optString("platform"), row.optString("car"), row.optString("seat"),
                ))
            }
        }.distinctBy { it.journeyId to it.travelDate }.sortedBy { it.travelDate }
    }.getOrDefault(emptyList())
}
