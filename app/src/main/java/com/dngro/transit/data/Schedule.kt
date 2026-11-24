package com.dngro.transit.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** One timetabled bus at a stop, from the OneBusAway schedule-for-stop API. */
data class ScheduledTrip(
    val route: String,
    val headsign: String,
    val tripId: String,
    val time: Instant,
)

/** NYC buses run on New York time whatever the watch's time zone is set to. */
internal val SERVICE_ZONE: ZoneId = ZoneId.of("America/New_York")

/**
 * Service dates whose timetable can still have buses after [now]. Trips that start late in the
 * evening belong to the previous day's service even after midnight, so before 4 a.m. that
 * day is included too.
 */
internal fun serviceDates(now: Instant): List<LocalDate> {
    val local = ZonedDateTime.ofInstant(now, SERVICE_ZONE)
    val today = local.toLocalDate()
    return if (local.hour < 4) listOf(today.minusDays(1), today) else listOf(today)
}

/**
 * Timetabled trips from a schedule-for-stop response. Tolerant like the alert parser: an
 * unexpected shape (or an error reply) gives an empty list and never breaks the live arrivals.
 */
internal fun parseSchedule(element: JsonElement?): List<ScheduledTrip> =
    try {
        extractSchedule(element)
    } catch (e: Exception) {
        emptyList()
    }

private fun extractSchedule(element: JsonElement?): List<ScheduledTrip> {
    val data = element.obj()?.get("data").obj() ?: return emptyList()
    val shortNames = data["references"].obj()?.get("routes").array()
        .mapNotNull { it.obj() }
        .mapNotNull { route ->
            val id = route["id"].text() ?: return@mapNotNull null
            val name = route["shortName"].text()?.takeIf { it.isNotBlank() }
                ?: route["nullSafeShortName"].text()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            id to name
        }
        .toMap()

    val trips = mutableListOf<ScheduledTrip>()
    for (routeSchedule in data["entry"].obj()?.get("stopRouteSchedules").array()) {
        val routeObj = routeSchedule.obj() ?: continue
        val routeId = routeObj["routeId"].text() ?: continue
        // "MTA NYCT_B41" -> "B41" when the references don't name it.
        val route = shortNames[routeId] ?: routeId.substringAfter('_')
        for (direction in routeObj["stopRouteDirectionSchedules"].array()) {
            val dirObj = direction.obj() ?: continue
            val headsign = dirObj["tripHeadsign"].text().orEmpty()
            for (stopTime in dirObj["scheduleStopTimes"].array()) {
                val st = stopTime.obj() ?: continue
                val millis = st["arrivalTime"].long() ?: st["departureTime"].long() ?: continue
                if (millis <= 0L) continue
                val sign = st["stopHeadsign"].text()?.takeIf { it.isNotBlank() } ?: headsign
                trips += ScheduledTrip(
                    route = route,
                    headsign = sign.ifBlank { "Unknown" },
                    tripId = st["tripId"].text().orEmpty(),
                    time = Instant.ofEpochMilli(millis),
                )
            }
        }
    }
    return trips
}

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray).orEmpty()

private fun JsonElement?.text(): String? = when (this) {
    is JsonNull, null -> null
    is JsonPrimitive -> content
    is JsonArray -> firstOrNull().text()
    else -> null
}

private fun JsonElement?.long(): Long? = text()?.trim()?.toDoubleOrNull()?.toLong()
