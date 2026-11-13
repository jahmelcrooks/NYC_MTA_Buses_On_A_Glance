package com.dngro.transit.presentation

import com.dngro.transit.data.Favorite
import com.dngro.transit.data.MonitoredCall
import com.dngro.transit.data.MonitoredVehicleJourney
import com.dngro.transit.data.cleanApiKey
import com.dngro.transit.data.decodeFavorites
import com.dngro.transit.data.encodeFavorites
import com.dngro.transit.data.withFavoriteToggled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FavoriteEtasTest {

    private val now = Instant.parse("2026-11-11T19:21:00Z")
    private val b41Downtown = Favorite("MTA_1", "Flatbush Av", "B41", "DOWNTOWN")

    private fun journey(route: String, dest: String, expected: String?) = MonitoredVehicleJourney(
        lineName = route,
        destinationName = dest,
        monitoredCall = MonitoredCall(expectedArrivalTime = expected),
    )

    // --- etasFor ---------------------------------------------------------------------------

    @Test
    fun etasFor_keepsOnlyThatRouteAndDirection_sortedAndLimited() {
        val journeys = listOf(
            journey("B41", "DOWNTOWN", "2026-11-11T14:30:00.000-05:00"),
            journey("B41", "DOWNTOWN", "2026-11-11T14:25:00.000-05:00"),
            journey("B41", "UPTOWN", "2026-11-11T14:22:00.000-05:00"), // other direction
            journey("B44", "DOWNTOWN", "2026-11-11T14:23:00.000-05:00"), // other route
            journey("B41", "DOWNTOWN", "2026-11-11T14:40:00.000-05:00"),
            journey("B41", "DOWNTOWN", "2026-11-11T14:50:00.000-05:00"),
        )

        val etas = etasFor(b41Downtown, journeys, now, max = 3)

        assertEquals(
            listOf(
                Instant.parse("2026-11-11T19:25:00Z"),
                Instant.parse("2026-11-11T19:30:00Z"),
                Instant.parse("2026-11-11T19:40:00Z"),
            ),
            etas,
        )
    }

    @Test
    fun etasFor_dropsDepartedBuses_andMissingPredictions() {
        val journeys = listOf(
            journey("B41", "DOWNTOWN", "2026-11-11T14:10:00.000-05:00"), // 11 minutes ago
            journey("B41", "DOWNTOWN", null),
            journey("B41", "DOWNTOWN", "2026-11-11T14:24:00.000-05:00"),
        )

        assertEquals(listOf(Instant.parse("2026-11-11T19:24:00Z")), etasFor(b41Downtown, journeys, now))
    }

    @Test
    fun etasFor_blankDestinationMatchesTheUnknownFavorite() {
        val unknown = b41Downtown.copy(destination = UNKNOWN_DESTINATION)
        val journeys = listOf(journey("B41", "", "2026-11-11T14:25:00.000-05:00"))

        assertEquals(1, etasFor(unknown, journeys, now).size)
    }

    // --- labels ----------------------------------------------------------------------------

    @Test
    fun etaSummary_formatsMinutes() {
        assertEquals("3, 11 min", etaSummary(listOf(now.plusSeconds(180), now.plusSeconds(660)), now))
        assertEquals("Now, 5 min", etaSummary(listOf(now.plusSeconds(10), now.plusSeconds(300)), now))
        assertEquals("Now", etaSummary(listOf(now.plusSeconds(10)), now))
        assertEquals("7 min", etaSummary(listOf(now.plusSeconds(420)), now))
    }

    @Test
    fun etaSummary_collapsesRepeatedMinutes_andOnlyShowsTwo() {
        val times = listOf(now.plusSeconds(10), now.plusSeconds(20), now.plusSeconds(180), now.plusSeconds(600))

        assertEquals("Now, 3 min", etaSummary(times, now))
    }

    @Test
    fun etaSummary_nothingOrFailed_isADash() {
        assertEquals("–", etaSummary(null, now))
        assertEquals("–", etaSummary(emptyList(), now))
    }

    @Test
    fun etaCompact_isShortForAWatchFaceSlot() {
        assertEquals("3m", etaCompact(listOf(now.plusSeconds(180)), now))
        assertEquals("Now", etaCompact(listOf(now.plusSeconds(5)), now))
        assertEquals("–", etaCompact(null, now))
        assertEquals("–", etaCompact(emptyList(), now))
    }

    // --- favorites storage -----------------------------------------------------------------

    @Test
    fun withFavoriteToggled_addsThenRemoves_keepingOrder() {
        val other = Favorite("MTA_2", "Church Av", "B44", "SHEEPSHEAD")

        val added = withFavoriteToggled(withFavoriteToggled(emptyList(), b41Downtown), other)
        assertEquals(listOf(b41Downtown, other), added)

        assertEquals(listOf(other), withFavoriteToggled(added, b41Downtown))
    }

    @Test
    fun favorites_roundTripThroughJson() {
        val list = listOf(b41Downtown, Favorite("MTA_2", "Church Av", "B44-SBS", "SHEEPSHEAD BAY"))

        assertEquals(list, decodeFavorites(encodeFavorites(list)))
    }

    @Test
    fun decodeFavorites_garbageMissingOrIncomplete_isEmptyNotACrash() {
        assertTrue(decodeFavorites(null).isEmpty())
        assertTrue(decodeFavorites("").isEmpty())
        assertTrue(decodeFavorites("not json").isEmpty())
        // The old format stored a set of route names, never a JSON list.
        assertTrue(decodeFavorites("""["B41","B44"]""").isEmpty())
    }

    // --- api key ---------------------------------------------------------------------------

    @Test
    fun cleanApiKey_stripsWhitespaceAndQuotes() {
        assertEquals("abc-123", cleanApiKey("  abc-123 \n"))
        assertEquals("abc-123", cleanApiKey("\"abc-123\""))
        assertEquals("abc123", cleanApiKey("abc 123"))
        assertEquals("", cleanApiKey("   "))
    }
}
