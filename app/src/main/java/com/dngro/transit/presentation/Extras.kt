package com.dngro.transit.presentation

import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

// Pure helpers for the v1.11 features, kept free of Android types so they can be unit-tested.

private const val WALK_METERS_PER_SECOND = 1.34 // about 3 mph

/** Straight-line distance understates a walk along streets, so pad it. */
private const val WALK_DETOUR_FACTOR = 1.3

/**
 * Within this distance you're treated as already at the stop: watch GPS is often off by this
 * much, and a bus that's 1 minute away must never look "too soon" while you stand at the stop.
 */
private const val AT_STOP_METERS = 60f

private fun distanceKnown(meters: Float) = meters < Float.MAX_VALUE / 2

internal fun walkSeconds(straightLineMeters: Float): Long =
    if (!distanceKnown(straightLineMeters) || straightLineMeters <= AT_STOP_METERS) 0L
    else ceil(straightLineMeters * WALK_DETOUR_FACTOR / WALK_METERS_PER_SECOND).toLong()

/** "At the stop", "1 min walk", "6 min walk". Empty when the distance is unknown. */
internal fun walkLabel(straightLineMeters: Float): String {
    if (!distanceKnown(straightLineMeters)) return ""
    val seconds = walkSeconds(straightLineMeters)
    if (seconds <= 0L) return "At the stop"
    return "${maxOf(1L, ceil(seconds / 60.0).toLong())} min walk"
}

enum class Reach { OK, TIGHT, TOO_SOON }

private const val TIGHT_MARGIN_SECONDS = 120L

/** Can you walk to the stop before this bus gets there? No prediction counts as OK. */
internal fun reachability(expected: Instant?, now: Instant, walkSeconds: Long): Reach {
    if (expected == null || walkSeconds <= 0L) return Reach.OK
    val secondsUntil = Duration.between(now, expected).seconds
    return when {
        secondsUntil < walkSeconds -> Reach.TOO_SOON
        secondsUntil < walkSeconds + TIGHT_MARGIN_SECONDS -> Reach.TIGHT
        else -> Reach.OK
    }
}

/** The one bus to highlight at the top of the list. */
internal data class NextBus(
    val route: String,
    val destination: String,
    val expected: Instant,
    /** MTA's "2 stops away" style text, or empty when it only says "approaching"/"ETA". */
    val stopsAwayText: String,
    val reach: Reach,
    val walkLabel: String,
    val crowding: Crowding? = null,
    val passengers: Int? = null,
    val live: Boolean = true,
    /** When this bus is too soon to walk to: the next one you can make, as (route, time). */
    val thenRoute: String? = null,
    val thenExpected: Instant? = null,
)

/**
 * The soonest bus. Starred directions win if any of them has a bus coming; otherwise everything
 * nearby is considered. A bus that's too soon to walk to is still shown (a bus 1 minute away is
 * the most useful thing to know when you're near the stop), together with the next one you can
 * make.
 */
internal fun nextBus(routes: List<BusRouteUiModel>, now: Instant): NextBus? {
    data class Candidate(val route: String, val direction: BusDirectionUiModel, val arrival: BusArrivalDetail, val expected: Instant)

    fun candidates(onlyPinned: Boolean): List<Candidate> =
        routes.flatMap { route ->
            route.directions
                .filter { !onlyPinned || it.isPinned }
                .flatMap { direction ->
                    direction.upcoming(now).mapNotNull { arrival ->
                        arrival.expected?.let { Candidate(route.routeShortName, direction, arrival, it) }
                    }
                }
        }

    val pool = candidates(onlyPinned = true).ifEmpty { candidates(onlyPinned = false) }
    if (pool.isEmpty()) return null

    fun catchOf(c: Candidate) = reachability(c.expected, now, walkSeconds(c.direction.stopDistanceMeters))
    val best = pool.minByOrNull { it.expected } ?: return null
    val then = if (catchOf(best) == Reach.TOO_SOON) {
        pool.filter { catchOf(it) != Reach.TOO_SOON }.minByOrNull { it.expected }
    } else {
        null
    }

    val distanceText = best.arrival.distanceText
    return NextBus(
        route = best.route,
        destination = best.direction.destinationName,
        expected = best.expected,
        stopsAwayText = if (distanceText == "ETA") "" else distanceText,
        reach = catchOf(best),
        walkLabel = walkLabel(best.direction.stopDistanceMeters),
        crowding = best.arrival.crowding,
        passengers = best.arrival.passengers,
        live = best.arrival.live,
        thenRoute = then?.route,
        thenExpected = then?.expected,
    )
}

internal const val ALERT_LEAD_SECONDS = 120L
private const val ALERT_MIN_DELAY_SECONDS = 15L

/**
 * When to buzz for this direction: [leadSeconds] before the first bus that is still far enough
 * away for the buzz to be useful. Null if there is no such bus.
 */
internal fun alertTriggerTime(
    direction: BusDirectionUiModel,
    now: Instant,
    leadSeconds: Long = ALERT_LEAD_SECONDS,
): Instant? =
    direction.upcoming(now)
        .mapNotNull { it.expected }
        .map { it.minusSeconds(leadSeconds) }
        .filter { it.isAfter(now.plusSeconds(ALERT_MIN_DELAY_SECONDS)) }
        .minOrNull()

/** What a bus stop screen needs: every route at one stop, soonest first. */
internal fun routesAtStopSorted(routes: List<BusRouteUiModel>, now: Instant): List<BusRouteUiModel> =
    routes.sortedBy { route ->
        route.directions.mapNotNull { d -> d.upcoming(now).firstNotNullOfOrNull { it.expected } }.minOrNull()
            ?: Instant.MAX
    }
