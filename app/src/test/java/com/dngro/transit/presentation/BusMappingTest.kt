package com.dngro.transit.presentation

import com.dngro.transit.data.Distances
import com.dngro.transit.data.Extensions
import com.dngro.transit.data.MonitoredCall
import com.dngro.transit.data.MonitoredVehicleJourney
import com.dngro.transit.data.MtaStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BusMappingTest {

    private val now = Instant.parse("2026-11-11T19:21:00Z")

    private fun stop(id: String, name: String) =
        MtaStop(id = id, name = name, lat = 40.7, lon = -73.9)

    private fun journey(
        route: String,
        destination: String,
        vehicle: String?,
        expected: String?,
        distance: String? = null,
    ) = MonitoredVehicleJourney(
        lineName = route,
        destinationName = destination,
        vehicleRef = vehicle,
        monitoredCall = MonitoredCall(
            extensions = distance?.let { Extensions(Distances(it)) },
            expectedArrivalTime = expected,
        ),
    )

    @Test
    fun nearestStopWins_andSameBusIsNotListedTwice() {
        val near = StopArrivals(
            stop("MTA_1", "Near St"), 100f,
            listOf(journey("B41", "DOWNTOWN", "V1", "2026-11-11T14:25:00.000-05:00")),
        )
        val far = StopArrivals(
            stop("MTA_2", "Far St"), 300f,
            listOf(
                // Same physical bus V1, reaching the next stop two minutes later.
                journey("B41", "DOWNTOWN", "V1", "2026-11-11T14:27:00.000-05:00"),
                journey("B41", "DOWNTOWN", "V2", "2026-11-11T14:30:00.000-05:00"),
            ),
        )

        val routes = buildRoutes(listOf(far, near), emptyMap(), emptySet(), 15)

        assertEquals(1, routes.size)
        val direction = routes[0].directions.single()
        assertEquals("Near St", direction.stopName)
        assertEquals(1, direction.arrivals.size)
        assertEquals(Instant.parse("2026-11-11T19:25:00Z"), direction.arrivals[0].expected)
    }

    @Test
    fun routeNotServedByNearestStop_usesTheNextStopThatHasIt() {
        val near = StopArrivals(
            stop("MTA_1", "Near St"), 100f,
            listOf(journey("B44", "SHEEPSHEAD", "V1", "2026-11-11T14:25:00.000-05:00")),
        )
        val far = StopArrivals(
            stop("MTA_2", "Far St"), 300f,
            listOf(journey("B41", "DOWNTOWN", "V2", "2026-11-11T14:26:00.000-05:00")),
        )

        val routes = buildRoutes(listOf(near, far), emptyMap(), emptySet(), 15)

        assertEquals(listOf("B44", "B41"), routes.map { it.routeShortName })
        assertEquals("Far St", routes[1].directions.single().stopName)
    }

    @Test
    fun pinnedRoutesSortFirst() {
        val near = StopArrivals(
            stop("MTA_1", "Near St"), 100f,
            listOf(journey("B44", "SHEEPSHEAD", "V1", "2026-11-11T14:25:00.000-05:00")),
        )
        val far = StopArrivals(
            stop("MTA_2", "Far St"), 300f,
            listOf(journey("B41", "DOWNTOWN", "V2", "2026-11-11T14:26:00.000-05:00")),
        )

        val routes = buildRoutes(listOf(near, far), emptyMap(), setOf("B41"), 15)

        assertEquals(listOf("B41", "B44"), routes.map { it.routeShortName })
        assertTrue(routes[0].isPinned)
    }

    @Test
    fun sameVehicleTwiceAtOneStop_isListedOnce() {
        val entry = StopArrivals(
            stop("MTA_1", "A"), 50f,
            listOf(
                journey("B41", "DOWNTOWN", "V1", "2026-11-11T14:25:00.000-05:00"),
                journey("B41", "DOWNTOWN", "V1", "2026-11-11T14:25:30.000-05:00"),
            ),
        )

        val routes = buildRoutes(listOf(entry), emptyMap(), emptySet(), 15)

        assertEquals(1, routes.single().directions.single().arrivals.size)
    }

    @Test
    fun arrivalsAreSortedAndCappedAtFive() {
        val journeys = (1..7).map { i ->
            // Deliberately in reverse time order: V7 arrives first.
            journey("B41", "DOWNTOWN", "V$i", "2026-11-11T14:%02d:00.000-05:00".format(28 - i))
        }
        val entry = StopArrivals(stop("MTA_1", "A"), 50f, journeys)

        val arrivals = buildRoutes(listOf(entry), emptyMap(), emptySet(), 15)
            .single().directions.single().arrivals

        assertEquals(5, arrivals.size)
        assertEquals(Instant.parse("2026-11-11T19:21:00Z"), arrivals.first().expected)
        assertEquals(arrivals.map { it.expected }.sortedBy { it }, arrivals.map { it.expected })
    }

    @Test
    fun busWithoutPrediction_sortsLastAndHasNoExpectedTime() {
        val entry = StopArrivals(
            stop("MTA_1", "A"), 50f,
            listOf(
                journey("B41", "DOWNTOWN", "V1", null),
                journey("B41", "DOWNTOWN", "V2", "2026-11-11T14:25:00.000-05:00"),
            ),
        )

        val arrivals = buildRoutes(listOf(entry), emptyMap(), emptySet(), 15)
            .single().directions.single().arrivals

        assertEquals(Instant.parse("2026-11-11T19:25:00Z"), arrivals[0].expected)
        assertNull(arrivals[1].expected)
    }

    @Test
    fun blankRouteIsDropped_andBlankDestinationBecomesUnknown() {
        val entry = StopArrivals(
            stop("MTA_1", "A"), 50f,
            listOf(
                journey("", "NOWHERE", "V0", "2026-11-11T14:25:00.000-05:00"),
                journey("B41", "", "V1", "2026-11-11T14:25:00.000-05:00"),
            ),
        )

        val routes = buildRoutes(listOf(entry), emptyMap(), emptySet(), 15)

        assertEquals(listOf("B41"), routes.map { it.routeShortName })
        assertEquals(UNKNOWN_DESTINATION, routes[0].directions.single().destinationName)
    }

    @Test
    fun approachingBecomesEta_otherDistanceTextIsKept() {
        val entry = StopArrivals(
            stop("MTA_1", "A"), 50f,
            listOf(
                journey("B41", "DOWNTOWN", "V1", "2026-11-11T14:22:00.000-05:00", distance = "approaching"),
                journey("B41", "DOWNTOWN", "V2", "2026-11-11T14:30:00.000-05:00", distance = "1.2 miles away"),
            ),
        )

        val arrivals = buildRoutes(listOf(entry), emptyMap(), emptySet(), 15)
            .single().directions.single().arrivals

        assertEquals("ETA", arrivals[0].distanceText)
        assertEquals("1.2 miles away", arrivals[1].distanceText)
    }

    @Test
    fun longRouteNameIsTrimmedAtDash() {
        val entry = StopArrivals(
            stop("MTA_1", "A"), 50f,
            listOf(journey("B41", "DOWNTOWN", "V1", "2026-11-11T14:25:00.000-05:00")),
        )

        val routes = buildRoutes(listOf(entry), mapOf("B41" to "Flatbush - Downtown Brooklyn"), emptySet(), 15)

        assertEquals("Flatbush", routes.single().routeLongName)
    }

    @Test
    fun minutesUntil_roundsToNearestMinute_neverNegative() {
        assertEquals(0L, minutesUntil(now.plusSeconds(29), now))
        assertEquals(1L, minutesUntil(now.plusSeconds(30), now))
        assertEquals(2L, minutesUntil(now.plusSeconds(119), now)) // 1:59 is "2 min", not "1 min"
        assertEquals(5L, minutesUntil(now.plusSeconds(300), now))
        assertEquals(0L, minutesUntil(now.minusSeconds(40), now))
    }

    @Test
    fun formatDistanceToUser_usesFeetThenMiles() {
        assertEquals("328 ft away", formatDistanceToUser(100f))
        assertEquals("0.6 mi away", formatDistanceToUser(1000f))
    }

    @Test
    fun parseInstant_handlesOffsetsAndGarbage() {
        assertEquals(
            Instant.parse("2026-11-11T19:25:00Z"),
            parseInstant("2026-11-11T14:25:00.000-05:00"),
        )
        assertNull(parseInstant("not a time"))
        assertNull(parseInstant(null))
        assertNull(parseInstant(""))
    }
}
