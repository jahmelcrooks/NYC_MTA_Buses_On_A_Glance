package com.dngro.transit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlacesTest {

    private val day = 24L * 60 * 60 * 1000
    private val stop = MtaStop(id = "MTA_1", name = "Church Av", lat = 40.65, lon = -73.95)

    private fun place(lat: Double, lon: Double, miles: Double = 0.5, savedAt: Long = 1_000L) =
        SavedPlace(lat, lon, miles, savedAt, listOf(stop))

    @Test
    fun haversine_knownDistance() {
        // About 111.2 km per degree of latitude.
        assertEquals(111_195.0, haversineMeters(40.0, -74.0, 41.0, -74.0), 200.0)
        assertEquals(0.0, haversineMeters(40.7, -73.9, 40.7, -73.9), 0.001)
    }

    @Test
    fun findPlace_matchesNearbySameRadiusAndFresh() {
        val places = listOf(place(40.6500, -73.9500))

        assertNotNull(findPlace(places, 40.6505, -73.9500, 0.5, nowMillis = 2_000L)) // ~55 m
        assertNull(findPlace(places, 40.6600, -73.9500, 0.5, nowMillis = 2_000L)) // ~1.1 km
        assertNull(findPlace(places, 40.6500, -73.9500, 0.25, nowMillis = 2_000L)) // other radius
        assertNull(findPlace(places, 40.6500, -73.9500, 0.5, nowMillis = 1_000L + 15 * day)) // too old
        assertNull(findPlace(places, 40.6500, -73.9500, 0.5, nowMillis = 500L)) // clock went backwards
    }

    @Test
    fun withPlaceSaved_replacesSameSpot_andKeepsNewestFew() {
        val first = place(40.65, -73.95, savedAt = 1L)
        val sameSpot = place(40.6501, -73.95, savedAt = 2L)

        val replaced = withPlaceSaved(listOf(first), sameSpot)
        assertEquals(listOf(2L), replaced.map { it.savedAtEpochMillis })

        var list = emptyList<SavedPlace>()
        for (i in 1..7) list = withPlaceSaved(list, place(40.0 + i, -73.0, savedAt = i.toLong()))
        assertEquals(MAX_SAVED_PLACES, list.size)
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), list.map { it.savedAtEpochMillis })
    }

    @Test
    fun encodeDecode_roundTrips_andGarbageIsEmpty() {
        val places = listOf(place(40.65, -73.95))

        assertEquals(places, decodePlaces(encodePlaces(places)))
        assertTrue(decodePlaces("not json").isEmpty())
        assertTrue(decodePlaces(null).isEmpty())
    }

    @Test
    fun alerts_roundTrip_andGarbageIsEmpty() {
        val alerts = mapOf("MTA_1|B41|DOWNTOWN" to 123L)

        assertEquals(alerts, decodeAlerts(encodeAlerts(alerts)))
        assertTrue(decodeAlerts("[1,2").isEmpty())
    }
}
