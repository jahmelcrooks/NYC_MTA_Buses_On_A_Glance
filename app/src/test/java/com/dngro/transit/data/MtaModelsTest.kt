package com.dngro.transit.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These fixtures are hand-written from MTA's documented field names, not captured from the
 * live API. Replacing them with real captured responses is a worthwhile follow-up.
 */
class MtaModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun siri_toleratesArraysNullsAndMissingFields() {
        val sample = """
        {"Siri":{"ServiceDelivery":{"StopMonitoringDelivery":[{"MonitoredStopVisit":[
          {"MonitoredVehicleJourney":{"PublishedLineName":["Q58"],"DestinationName":"RIDGEWOOD",
            "VehicleRef":"MTA NYCT_1234"}},
          {"MonitoredVehicleJourney":{"PublishedLineName":"B41","DestinationName":null,
            "MonitoredCall":{"ExpectedArrivalTime":"2026-11-11T14:25:00.000-05:00",
              "Extensions":{"Distances":{"PresentableDistance":"approaching"}}}}},
          {}
        ]}]}}}
        """.trimIndent()

        val journeys = json.decodeFromString<SiriResponse>(sample)
            .siri!!.serviceDelivery!!.stopMonitoringDelivery
            .flatMap { it.monitoredStopVisit }
            .mapNotNull { it.monitoredVehicleJourney }

        assertEquals(2, journeys.size)

        assertEquals("Q58", journeys[0].lineName)
        assertEquals("RIDGEWOOD", journeys[0].destinationName)
        assertEquals("MTA NYCT_1234", journeys[0].vehicleRef)
        assertNull(journeys[0].monitoredCall.expectedArrivalTime)

        assertEquals("B41", journeys[1].lineName)
        assertEquals("", journeys[1].destinationName)
        assertEquals("approaching", journeys[1].monitoredCall.extensions?.distances?.presentableDistance)
    }

    @Test
    fun siri_emptyObjectDecodesToNothing() {
        val parsed = json.decodeFromString<SiriResponse>("{}")
        assertNull(parsed.siri)
    }

    @Test
    fun stops_supportsOneBusAwayListKey() {
        val sample = """
        {"code":200,"data":{"list":[
          {"id":"MTA_308209","name":"5 AV/W 34 ST","lat":40.7485,"lon":-73.9857,"direction":"N"}
        ]}}
        """.trimIndent()

        val stops = json.decodeFromString<StopResponse>(sample).data!!.allStops

        assertEquals(1, stops.size)
        assertEquals("MTA_308209", stops[0].id)
        assertTrue(stops[0].routes.isEmpty())
    }

    @Test
    fun stops_supportsStopsKeyWithInlineRoutes() {
        val sample = """
        {"code":200,"data":{"stops":[
          {"id":"MTA_300001","name":"X","lat":40.7,"lon":-73.9,
           "routes":[{"shortName":"B63","longName":"Bay Ridge - Fulton Ferry"}]}
        ]}}
        """.trimIndent()

        val stops = json.decodeFromString<StopResponse>(sample).data!!.allStops

        assertEquals(1, stops.size)
        assertEquals("B63", stops[0].routes.single().shortName)
    }

    @Test
    fun stops_errorEnvelopeStillDecodes() {
        val parsed = json.decodeFromString<StopResponse>("""{"code":401,"text":"permission denied"}""")

        assertEquals(401, parsed.code)
        assertNull(parsed.data)
    }
}
