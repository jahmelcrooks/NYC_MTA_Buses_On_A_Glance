package com.dngro.transit.presentation

import com.dngro.transit.data.Favorite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TileCardTest {

    private val now = Instant.parse("2026-11-11T19:00:00Z")
    private val favorite = Favorite(stopId = "MTA_1", stopName = "Flatbush Av", route = "B9", destination = "KINGS PLAZA")

    private fun arrival(seconds: Long, live: Boolean = true, text: String = "ETA", crowding: Crowding? = null) =
        BusArrivalDetail(id = "a$seconds", distanceText = text, expected = now.plusSeconds(seconds), crowding = crowding, live = live)

    private fun routesWith(vararg arrivals: BusArrivalDetail, alerts: List<String> = emptyList()) = listOf(
        BusRouteUiModel(
            routeShortName = "B9",
            routeLongName = "",
            alerts = alerts,
            directions = listOf(
                BusDirectionUiModel(id = "x", destinationName = "BAY RIDGE", stopId = "MTA_1", stopName = "Flatbush Av", arrivals = listOf(arrival(30))),
                BusDirectionUiModel(id = "y", destinationName = "KINGS PLAZA", stopId = "MTA_1", stopName = "Flatbush Av", arrivals = arrivals.toList()),
            ),
        ),
    )

    @Test
    fun liveBus_withCrowdingAndStopsAway() {
        val status = statusFor(favorite, routesWith(arrival(180, text = "2 stops away", crowding = Crowding.STANDING), arrival(840)))
        val card = tileCard(status, now)

        assertEquals("B9", card.route)
        assertEquals("3, 14 min", card.times)
        assertTrue(card.live)
        assertEquals("to KINGS PLAZA", card.destination)
        assertEquals("Standing · 2 stops away", card.detail)
        assertEquals(Crowding.STANDING, card.crowding)
    }

    @Test
    fun scheduledFirst_isMarkedScheduled() {
        val card = tileCard(statusFor(favorite, routesWith(arrival(600, live = false, text = "Scheduled"))), now)

        assertFalse(card.live)
        assertEquals("10 min", card.times)
        assertEquals("Scheduled", card.detail)
    }

    @Test
    fun alertIsMentioned_andDepartedBusesDropped() {
        val card = tileCard(
            statusFor(favorite, routesWith(arrival(-300), arrival(60, crowding = Crowding.FULL), alerts = listOf("Detour"))),
            now,
        )

        assertEquals("1 min", card.times)
        assertEquals("Alert · Full", card.detail)
        assertTrue(card.hasAlert)
    }

    @Test
    fun failedLookup_and_noBusesForThisDirection() {
        assertEquals("No data", tileCard(statusFor(favorite, null), now).times)
        val other = favorite.copy(destination = "SOMEWHERE ELSE")
        val card = tileCard(statusFor(other, routesWith(arrival(60))), now)
        assertEquals("–", card.times)
        assertEquals("", card.detail)
        assertFalse(card.live)
        assertNull(card.crowding)
    }
}
