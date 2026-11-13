package com.dngro.transit.presentation

import com.dngro.transit.data.latSpanDegrees
import com.dngro.transit.data.lonSpanDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NearbyAndSnapshotTest {

    private val now = Instant.parse("2026-11-11T19:21:00Z")
    private val tiers = listOf(402f, 805f, 1609f)

    private fun stops(vararg meters: Float) = meters.mapIndexed { i, m -> "S$i" to m }

    @Test
    fun selectWithinTiers_prefersTheSmallestRadiusWithEnoughStops() {
        val result = selectWithinTiers(stops(100f, 200f, 300f, 700f), tiers, minCount = 3)

        assertEquals(402f, result.radiusMeters)
        assertEquals(listOf("S0", "S1", "S2"), result.items.map { it.first })
    }

    @Test
    fun selectWithinTiers_widensWhenTheCloseTierHasTooFew() {
        val result = selectWithinTiers(stops(100f, 500f, 700f, 1200f), tiers, minCount = 3)

        assertEquals(805f, result.radiusMeters)
        assertEquals(listOf("S0", "S1", "S2"), result.items.map { it.first })
    }

    @Test
    fun selectWithinTiers_returnsWhatExistsInTheWidestTier_evenIfBelowTheMinimum() {
        val result = selectWithinTiers(stops(1500f, 5000f), tiers, minCount = 3)

        assertEquals(1609f, result.radiusMeters)
        assertEquals(listOf("S0"), result.items.map { it.first })
    }

    @Test
    fun selectWithinTiers_noStopsAtAll_isEmpty() {
        assertTrue(selectWithinTiers(emptyList<Pair<String, Float>>(), tiers, 3).items.isEmpty())
    }

    @Test
    fun selectWithinTiers_sortsByDistance() {
        val result = selectWithinTiers(stops(300f, 100f, 200f), tiers, minCount = 3)

        assertEquals(listOf("S1", "S2", "S0"), result.items.map { it.first })
    }

    @Test
    fun searchBoxSpans_matchTheRadius() {
        // 805 m each way: 1610 m / 111,320 m per degree of latitude.
        assertEquals(0.014463, latSpanDegrees(805.0), 0.00001)
        // At NYC's latitude a degree of longitude is about 76% as long, so the span is wider.
        assertEquals(0.019077, lonSpanDegrees(805.0, 40.7), 0.0001)
    }

    private fun arrival(id: String, expected: Instant?) = BusArrivalDetail(id, "ETA", expected)

    private fun route(name: String, vararg directions: BusDirectionUiModel) = BusRouteUiModel(
        routeShortName = name,
        routeLongName = "",
        directions = directions.toList(),
    )

    private fun direction(dest: String, vararg arrivals: BusArrivalDetail) = BusDirectionUiModel(
        id = "x_$dest",
        destinationName = dest,
        stopId = "MTA_1",
        stopName = "Stop",
        arrivals = arrivals.toList(),
    )

    @Test
    fun pruneExpired_dropsDepartedBuses_thenEmptyDirectionsAndRoutes() {
        val routes = listOf(
            route(
                "B41",
                direction("DOWNTOWN", arrival("a", now.minusSeconds(300)), arrival("b", now.plusSeconds(240))),
                direction("UPTOWN", arrival("c", now.minusSeconds(120))),
            ),
            route("B44", direction("SHEEPSHEAD", arrival("d", now.minusSeconds(600)))),
        )

        val pruned = pruneExpired(routes, now)

        assertEquals(listOf("B41"), pruned.map { it.routeShortName })
        assertEquals(listOf("DOWNTOWN"), pruned.single().directions.map { it.destinationName })
        assertEquals(listOf("b"), pruned.single().directions.single().arrivals.map { it.id })
    }

    @Test
    fun pruneExpired_keepsABusThatJustLeft_withinTheGraceWindow() {
        val routes = listOf(route("B41", direction("DOWNTOWN", arrival("a", now.minusSeconds(30)))))

        assertEquals(1, pruneExpired(routes, now).size)
    }

    @Test
    fun pruneExpired_dropsArrivalsWithNoPrediction() {
        val routes = listOf(route("B41", direction("DOWNTOWN", arrival("a", null))))

        assertTrue(pruneExpired(routes, now).isEmpty())
    }

    @Test
    fun snapshot_roundTripsIncludingTimesAndAlerts() {
        val original = Snapshot(
            savedAtEpochMillis = 1_700_000_000_000L,
            routes = listOf(
                BusRouteUiModel(
                    routeShortName = "B41",
                    routeLongName = "Flatbush",
                    nearestStopDistanceMeters = 120f,
                    isPinned = true,
                    alerts = listOf("Detour at Church Av"),
                    directions = listOf(
                        BusDirectionUiModel(
                            id = "B41_DOWNTOWN",
                            destinationName = "DOWNTOWN",
                            stopId = "MTA_1",
                            stopName = "Flatbush Av",
                            stopDistanceMeters = 120f,
                            stopDistanceText = "394 ft away",
                            isPinned = true,
                            arrivals = listOf(arrival("a", now), arrival("b", null)),
                        ),
                    ),
                ),
            ),
        )

        val decoded = decodeSnapshot(encodeSnapshot(original))

        assertEquals(original, decoded)
    }

    @Test
    fun decodeSnapshot_garbageOrMissing_isNull() {
        assertNull(decodeSnapshot(null))
        assertNull(decodeSnapshot(""))
        assertNull(decodeSnapshot("{not json"))
        assertNull(decodeSnapshot("""{"unexpected":true}"""))
    }

    @Test
    fun decodeSnapshot_toleratesUnknownFieldsFromNewerVersions() {
        val raw = """{"savedAtEpochMillis":5,"routes":[],"futureField":"x"}"""

        assertNotNull(decodeSnapshot(raw))
    }
}
