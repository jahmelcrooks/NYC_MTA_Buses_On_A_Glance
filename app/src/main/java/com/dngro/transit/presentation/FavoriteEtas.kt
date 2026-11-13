package com.dngro.transit.presentation

import com.dngro.transit.data.BusApiException
import com.dngro.transit.data.BusRepository
import com.dngro.transit.data.Favorite
import com.dngro.transit.data.MonitoredVehicleJourney
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
