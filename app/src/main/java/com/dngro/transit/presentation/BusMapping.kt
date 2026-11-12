package com.dngro.transit.presentation

import com.dngro.transit.data.MonitoredVehicleJourney
import com.dngro.transit.data.MtaStop
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

// Pure functions (no Android types) so they can be unit-tested on the JVM.

internal const val UNKNOWN_DESTINATION = "Unknown"
internal const val MAX_ARRIVALS_PER_DIRECTION = 5

/** The arrivals reported by one stop, plus how far that stop is from the user. */
internal data class StopArrivals(
    val stop: MtaStop,
    val distanceMeters: Float,
    val journeys: List<MonitoredVehicleJourney>,
)

/**
 * Groups arrivals into route -> direction cards.
 *
 * Each (route, destination) is shown using only the NEAREST stop that has buses for it.
 * Merging arrivals from several stops would list the same physical bus once per stop,
 * with different ETAs, all labelled with a single stop name.
 */
internal fun buildRoutes(
    stopArrivals: List<StopArrivals>,
    routeLongNames: Map<String, String>,
    pinnedRoutes: Set<String>,
    maxRoutes: Int,
): List<BusRouteUiModel> {
    val byRoute = LinkedHashMap<String, LinkedHashMap<String, BusDirectionUiModel>>()

    for (entry in stopArrivals.sortedBy { it.distanceMeters }) {
        val grouped = entry.journeys
            .filter { it.lineName.isNotBlank() }
            .groupBy { it.lineName to it.destinationName.ifEmpty { UNKNOWN_DESTINATION } }

        for ((key, journeys) in grouped) {
            val (route, destination) = key
            val directions = byRoute.getOrPut(route) { LinkedHashMap() }
            if (directions.containsKey(destination)) continue // a nearer stop already covers it

            directions[destination] = BusDirectionUiModel(
                id = "${route}_$destination",
                destinationName = destination,
                stopName = entry.stop.name,
                stopDistanceMeters = entry.distanceMeters,
                stopDistanceText = formatDistanceToUser(entry.distanceMeters),
                arrivals = journeys.toArrivals(),
            )
        }
    }

    return byRoute
        .map { (route, directions) ->
            BusRouteUiModel(
                routeShortName = route,
                routeLongName = routeLongNames[route].orEmpty().substringBefore(" - "),
                nearestStopDistanceMeters = directions.values.minOf { it.stopDistanceMeters },
                isPinned = route in pinnedRoutes,
                directions = directions.values.toList(),
            )
        }
        .sortedWith(
            compareByDescending<BusRouteUiModel> { it.isPinned }
                .thenBy { it.nearestStopDistanceMeters },
        )
        .take(maxRoutes)
}

private fun List<MonitoredVehicleJourney>.toArrivals(): List<BusArrivalDetail> =
    distinctBy { it.vehicleRef ?: "t:${it.monitoredCall.expectedArrivalTime}" }
        .map { journey ->
            val distance = journey.monitoredCall.extensions?.distances?.presentableDistance
            BusArrivalDetail(
                id = "${journey.vehicleRef ?: journey.lineName}_${journey.monitoredCall.expectedArrivalTime}",
                distanceText = if (distance.isNullOrEmpty() || distance.contains("approaching", ignoreCase = true)) {
                    "ETA"
                } else {
                    distance
                },
                expected = parseInstant(journey.monitoredCall.expectedArrivalTime),
            )
        }
        .sortedBy { it.expected ?: Instant.MAX }
        .take(MAX_ARRIVALS_PER_DIRECTION)

/** Whole minutes until [expected], rounded to the nearest minute and never negative. */
internal fun minutesUntil(expected: Instant, now: Instant): Long =
    Math.floorDiv(Duration.between(now, expected).seconds + 30L, 60L).coerceAtLeast(0L)

internal fun formatDistanceToUser(meters: Float): String {
    val miles = meters * 0.000621371f
    return if (miles < 0.1f) {
        "${(meters * 3.28084f).toInt()} ft away"
    } else {
        String.format(Locale.US, "%.1f mi away", miles)
    }
}

internal fun parseInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(raw).toInstant()
    } catch (e: Exception) {
        null
    }
}
