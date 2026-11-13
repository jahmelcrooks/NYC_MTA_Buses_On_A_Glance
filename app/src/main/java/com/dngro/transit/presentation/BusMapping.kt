package com.dngro.transit.presentation

import com.dngro.transit.data.MonitoredVehicleJourney
import com.dngro.transit.data.MtaStop
import com.dngro.transit.data.ServiceAlert
import com.dngro.transit.data.alertsForRoute
import com.dngro.transit.data.favoriteKey
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
    val alerts: List<ServiceAlert> = emptyList(),
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
    favoriteKeys: Set<String>,
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
            val existing = directions[destination]
            if (existing != null) {
                // A nearer stop already covers it, unless the user starred THIS stop and not that
                // one. A starred stop must stay visible (and unstar-able) even when it is no longer
                // the nearest, or it would silently stay on the tile with no way to remove it.
                val existingStarred = favoriteKey(existing.stopId, route, destination) in favoriteKeys
                val thisStarred = favoriteKey(entry.stop.id, route, destination) in favoriteKeys
                if (existingStarred || !thisStarred) continue
            }

            directions[destination] = BusDirectionUiModel(
                id = "${route}_$destination",
                destinationName = destination,
                stopId = entry.stop.id,
                stopName = entry.stop.name,
                stopDistanceMeters = entry.distanceMeters,
                stopDistanceText = formatDistanceToUser(entry.distanceMeters),
                arrivals = journeys.toArrivals(),
            )
        }
    }

    val allAlerts = stopArrivals.flatMap { it.alerts }
    val routes = byRoute.map { (route, directions) ->
        BusRouteUiModel(
            routeShortName = route,
            routeLongName = routeLongNames[route].orEmpty().substringBefore(" - "),
            nearestStopDistanceMeters = directions.values.minOf { it.stopDistanceMeters },
            alerts = alertsForRoute(allAlerts, route),
            directions = directions.values.toList(),
        )
    }
    return applyFavorites(routes, favoriteKeys).take(maxRoutes)
}

/**
 * Marks starred directions, and sorts routes with a starred direction first, then by distance.
 * Cheap enough to re-run whenever the user stars something.
 */
internal fun applyFavorites(
    routes: List<BusRouteUiModel>,
    favoriteKeys: Set<String>,
): List<BusRouteUiModel> =
    routes
        .map { route ->
            val directions = route.directions.map {
                it.copy(isPinned = favoriteKey(it.stopId, route.routeShortName, it.destinationName) in favoriteKeys)
            }
            route.copy(directions = directions, isPinned = directions.any { it.isPinned })
        }
        .sortedWith(
            compareByDescending<BusRouteUiModel> { it.isPinned }
                .thenBy { it.nearestStopDistanceMeters },
        )

/** Stops chosen for the search, and how far out we had to look to find them. */
internal data class NearbySelection<T>(val items: List<Pair<T, Float>>, val radiusMeters: Float)

/**
 * Looks close first: returns the stops within the smallest radius in [tiers] that holds at least
 * [minCount] of them. If even the widest radius has fewer, returns whatever is inside it.
 * [tiers] must be in ascending order.
 */
internal fun <T> selectWithinTiers(
    items: List<Pair<T, Float>>,
    tiers: List<Float>,
    minCount: Int,
): NearbySelection<T> {
    val sorted = items.sortedBy { it.second }
    for (tier in tiers) {
        val within = sorted.filter { it.second <= tier }
        if (within.size >= minCount) return NearbySelection(within, tier)
    }
    val widest = tiers.maxOrNull() ?: 0f
    return NearbySelection(sorted.filter { it.second <= widest }, widest)
}

/**
 * Drops arrivals that have already left (allowing [graceSeconds] of slack), then any direction
 * or route left empty. Used on saved results, whose times may be old by the next launch.
 */
internal fun pruneExpired(
    routes: List<BusRouteUiModel>,
    now: Instant,
    graceSeconds: Long = 60,
): List<BusRouteUiModel> {
    val cutoff = now.minusSeconds(graceSeconds)
    return routes.mapNotNull { route ->
        val directions = route.directions.mapNotNull { direction ->
            val arrivals = direction.arrivals.filter { it.expected != null && !it.expected.isBefore(cutoff) }
            if (arrivals.isEmpty()) null else direction.copy(arrivals = arrivals)
        }
        if (directions.isEmpty()) null else route.copy(directions = directions)
    }
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

/**
 * Arrivals that haven't left yet (with [graceSeconds] of slack). Arrivals with no prediction are
 * kept: they have no time to compare. Used when drawing, so a bus that has since left never shows
 * as "Now", even if the list on screen is old (e.g. while the watch sat in ambient mode).
 */
internal fun BusDirectionUiModel.upcoming(now: Instant, graceSeconds: Long = 60): List<BusArrivalDetail> {
    val cutoff = now.minusSeconds(graceSeconds)
    return arrivals.filter { it.expected == null || !it.expected.isBefore(cutoff) }
}

/** Whole minutes until [expected], rounded to the nearest minute and never negative. */
internal fun minutesUntil(expected: Instant, now: Instant): Long =
    Math.floorDiv(Duration.between(now, expected).seconds + 30L, 60L).coerceAtLeast(0L)

internal fun formatDistanceToUser(meters: Float, metric: Boolean = false): String {
    if (metric) {
        return if (meters < 100f) "${meters.toInt()} m away" else String.format(Locale.US, "%.1f km away", meters / 1000f)
    }
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
