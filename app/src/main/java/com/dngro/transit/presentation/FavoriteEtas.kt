package com.dngro.transit.presentation

import com.dngro.transit.data.BusApiException
import com.dngro.transit.data.BusRepository
import com.dngro.transit.data.Favorite
import com.dngro.transit.data.MonitoredVehicleJourney
import com.dngro.transit.data.MtaStop
import com.dngro.transit.data.ScheduledTrip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant

/** Upcoming arrival times for one starred route; [arrivals] is null if the lookup failed. */
internal data class FavoriteEta(val favorite: Favorite, val arrivals: List<Instant>?)

/** Upcoming arrival times of [favorite]'s route and direction in [journeys] (one stop's buses). */
internal fun etasFor(
    favorite: Favorite,
    journeys: List<MonitoredVehicleJourney>,
    now: Instant,
    max: Int = 3,
): List<Instant> =
    journeys
        .filter {
            it.lineName == favorite.route &&
                it.destinationName.ifEmpty { UNKNOWN_DESTINATION } == favorite.destination
        }
        .mapNotNull { parseInstant(it.monitoredCall.expectedArrivalTime) }
        .filter { !it.isBefore(now.minusSeconds(30)) }
        .distinct()
        .sorted()
        .take(max)

/**
 * Fetches arrivals for the first [maxFavorites] favorites, one request per distinct stop.
 * Needs no location: a favorite already names its stop. A failed stop gives `arrivals = null`.
 */
internal suspend fun loadFavoriteEtas(
    repository: BusRepository,
    favorites: List<Favorite>,
    now: Instant,
    maxFavorites: Int,
): List<FavoriteEta> = coroutineScope {
    val picked = favorites.take(maxFavorites)
    val journeysByStop = picked.map { it.stopId }.distinct()
        .map { stopId ->
            async {
                stopId to try {
                    repository.getBusArrivals(stopId)
                } catch (e: BusApiException) {
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One odd failure must not cancel the other stops' lookups (or crash the tile).
                    null
                }
            }
        }
        .awaitAll()
        .toMap()
    picked.map { favorite ->
        FavoriteEta(favorite, journeysByStop[favorite.stopId]?.let { etasFor(favorite, it, now) })
    }
}

/** "3, 11 min", "Now, 5 min", "Now", or "–" when there is nothing (or the lookup failed). */
internal fun etaSummary(arrivals: List<Instant>?, now: Instant, maxTimes: Int = 2): String {
    if (arrivals.isNullOrEmpty()) return "–"
    val minutes = arrivals.map { minutesUntil(it, now) }.distinct().take(maxTimes)
    val text = minutes.joinToString(", ") { if (it <= 0L) "Now" else it.toString() }
    return if (minutes.last() > 0L) "$text min" else text
}

/** Short form for a watch-face slot: "3m", "Now" or "–". */
internal fun etaCompact(arrivals: List<Instant>?, now: Instant): String {
    val first = arrivals?.firstOrNull() ?: return "–"
    val minutes = minutesUntil(first, now)
    return if (minutes <= 0L) "Now" else "${minutes}m"
}

// ---------------------------------------------------------------------------
// Tile: richer status per starred bus (live vs scheduled, crowding, stops away, alerts)

/** Everything the tile shows for one starred bus. [arrivals] is null if the lookup failed. */
internal data class FavoriteStatus(
    val favorite: Favorite,
    val arrivals: List<BusArrivalDetail>?,
    val hasAlert: Boolean = false,
)

/**
 * Like [loadFavoriteEtas], but runs each stop through the same mapping as the app: live buses,
 * timetabled ones after them, crowding and service alerts. One request per distinct stop, plus
 * the stop's timetable (cached for the day by [scheduleFor]).
 */
internal suspend fun loadFavoriteStatuses(
    repository: BusRepository,
    favorites: List<Favorite>,
    maxFavorites: Int,
    scheduleFor: suspend (stopId: String) -> List<ScheduledTrip>,
): List<FavoriteStatus> = coroutineScope {
    val picked = favorites.take(maxFavorites)
    val routesByStop = picked.map { it.stopId to it.stopName }.distinctBy { it.first }
        .map { (stopId, stopName) ->
            async {
                stopId to try {
                    val schedule = async {
                        try {
                            scheduleFor(stopId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    val monitoring = repository.getStopMonitoring(stopId)
                    buildRoutes(
                        stopArrivals = listOf(
                            StopArrivals(MtaStop(id = stopId, name = stopName), 0f, monitoring.journeys, monitoring.alerts, schedule.await()),
                        ),
                        routeLongNames = emptyMap(),
                        favoriteKeys = emptySet(),
                        maxRoutes = Int.MAX_VALUE,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One stop failing (or odd data) must not cancel the others or crash the tile.
                    null
                }
            }
        }
        .awaitAll()
        .toMap()
    picked.map { favorite -> statusFor(favorite, routesByStop[favorite.stopId]) }
}

/** The starred route + direction's arrivals within one stop's mapped routes. */
internal fun statusFor(favorite: Favorite, stopRoutes: List<BusRouteUiModel>?): FavoriteStatus {
    if (stopRoutes == null) return FavoriteStatus(favorite, null)
    val route = stopRoutes.firstOrNull { it.routeShortName == favorite.route }
    val direction = route?.directions?.firstOrNull { it.destinationName == favorite.destination }
    return FavoriteStatus(
        favorite = favorite,
        arrivals = direction?.arrivals.orEmpty(),
        hasAlert = route?.alerts?.isNotEmpty() == true,
    )
}

/** What one tile card shows, worked out ahead so it can be unit-tested without the tile library. */
internal data class TileCard(
    val route: String,
    /** "3, 11 min", "Now", "–" or "No data". */
    val times: String,
    /** Whether the first time shown is a live prediction (green dot) or scheduled (grey dot). */
    val live: Boolean,
    val destination: String,
    /** "Standing · 2 stops away", "Scheduled", "Alert · Full", or "". */
    val detail: String,
    val crowding: Crowding?,
    val hasAlert: Boolean,
)

internal fun tileCard(status: FavoriteStatus, now: Instant): TileCard {
    val favorite = status.favorite
    val upcoming = status.arrivals
        ?.filter { it.expected != null && !it.expected.isBefore(now.minusSeconds(60)) }
    val first = upcoming?.firstOrNull()
    val times = when {
        upcoming == null -> "No data"
        else -> etaSummary(upcoming.mapNotNull { it.expected }, now)
    }
    val away = first?.distanceText?.takeIf { it != "ETA" && it != "Scheduled" }.orEmpty()
    val parts = mutableListOf<String>()
    if (status.hasAlert) parts += "Alert"
    when {
        first == null -> Unit
        !first.live -> parts += "Scheduled"
        else -> {
            parts += crowdLabel(first.crowding, null)
            parts += away
        }
    }
    val detail = parts.filter { it.isNotEmpty() }.joinToString(" · ")
    return TileCard(
        route = favorite.route,
        times = times,
        live = first?.live ?: false,
        destination = "to ${favorite.destination}",
        detail = detail,
        crowding = first?.crowding,
        hasAlert = status.hasAlert,
    )
}
