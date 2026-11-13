package com.dngro.transit.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ExtrasTest {

    private val now = Instant.parse("2026-11-11T19:00:00Z")

    private fun arrival(secondsFromNow: Long?, text: String = "ETA") = BusArrivalDetail(
        id = "a$secondsFromNow",
        distanceText = text,
        expected = secondsFromNow?.let { now.plusSeconds(it) },
    )

    private fun direction(
        dest: String = "DOWNTOWN",
        meters: Float = 0f,
        pinned: Boolean = false,
        vararg arrivals: BusArrivalDetail,
    ) = BusDirectionUiModel(
        id = "r_$dest",
        destinationName = dest,
        stopId = "MTA_1",
        stopName = "Stop",
        stopDistanceMeters = meters,
        isPinned = pinned,
        arrivals = arrivals.toList(),
    )

    private fun route(name: String, vararg directions: BusDirectionUiModel) =
        BusRouteUiModel(routeShortName = name, routeLongName = "", directions = directions.toList())

    @Test
    fun walkSeconds_padsForStreets_andIgnoresUnknownDistance() {
        // 400 m * 1.3 / 1.34 m/s = 388.06 s -> 389
        assertEquals(389L, walkSeconds(400f))
        assertEquals(0L, walkSeconds(Float.MAX_VALUE))
        assertEquals("7 min walk", walkLabel(400f))
        assertEquals("1 min walk", walkLabel(5f))
        assertEquals("", walkLabel(Float.MAX_VALUE))
    }

    @Test
    fun reachability_thresholds() {
        assertEquals(Reach.TOO_SOON, reachability(now.plusSeconds(299), now, 300))
        assertEquals(Reach.TIGHT, reachability(now.plusSeconds(300), now, 300))
        assertEquals(Reach.TIGHT, reachability(now.plusSeconds(419), now, 300))
        assertEquals(Reach.OK, reachability(now.plusSeconds(420), now, 300))
        assertEquals(Reach.OK, reachability(null, now, 300))
        assertEquals(Reach.OK, reachability(now.plusSeconds(1), now, 0))
    }

    @Test
    fun nextBus_skipsBusesYouCannotCatch_whenAnotherIsReachable() {
        val routes = listOf(
            route("B41", direction(meters = 400f, arrivals = arrayOf(arrival(120), arrival(900, "2 stops away")))),
        )

        val next = nextBus(routes, now)!!

        assertEquals(now.plusSeconds(900), next.expected)
        assertEquals("2 stops away", next.stopsAwayText)
        assertEquals(Reach.OK, next.reach)
    }

    @Test
    fun nextBus_fallsBackToUnreachableBus_whenNothingElse() {
        val routes = listOf(route("B41", direction(meters = 400f, arrivals = arrayOf(arrival(60)))))

        val next = nextBus(routes, now)!!

        assertEquals(Reach.TOO_SOON, next.reach)
        assertEquals("", next.stopsAwayText)
    }

    @Test
    fun nextBus_prefersStarredDirections_evenIfALaterBus() {
        val routes = listOf(
            route("B44", direction("SHEEPSHEAD", arrivals = arrayOf(arrival(60)))),
            route("B41", direction("DOWNTOWN", pinned = true, arrivals = arrayOf(arrival(600)))),
        )

        assertEquals("B41", nextBus(routes, now)!!.route)
    }

    @Test
    fun nextBus_ignoresDepartedBuses_andNullWhenNothing() {
        val routes = listOf(route("B41", direction(arrivals = arrayOf(arrival(-300), arrival(null)))))

        assertNull(nextBus(routes, now))
        assertNull(nextBus(emptyList(), now))
    }

    @Test
    fun alertTriggerTime_isTwoMinutesBeforeTheFirstBusFarEnoughAway() {
        val d = direction(arrivals = arrayOf(arrival(100), arrival(600)))

        // The 100 s bus is skipped (its buzz time is already past); the 600 s bus buzzes at +480 s.
        assertEquals(now.plusSeconds(480), alertTriggerTime(d, now))
        assertNull(alertTriggerTime(direction(arrivals = arrayOf(arrival(100))), now))
        assertNull(alertTriggerTime(direction(arrivals = arrayOf(arrival(null))), now))
    }

    @Test
    fun routesAtStopSorted_soonestFirst_noPredictionLast() {
        val sorted = routesAtStopSorted(
            listOf(
                route("B44", direction(arrivals = arrayOf(arrival(null)))),
                route("B41", direction(arrivals = arrayOf(arrival(900)))),
                route("B8", direction(arrivals = arrayOf(arrival(60)))),
            ),
            now,
        )

        assertEquals(listOf("B8", "B41", "B44"), sorted.map { it.routeShortName })
    }

    @Test
    fun formatDistance_metric() {
        assertEquals("50 m away", formatDistanceToUser(50f, metric = true))
        assertEquals("0.4 km away", formatDistanceToUser(402f, metric = true))
        assertTrue(formatDistanceToUser(402f).endsWith("mi away"))
    }
}
