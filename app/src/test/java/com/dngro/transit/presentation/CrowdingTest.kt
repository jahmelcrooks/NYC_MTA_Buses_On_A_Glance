package com.dngro.transit.presentation

import com.dngro.transit.data.SiriResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrowdingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun caps(raw: String) = json.parseToJsonElement(raw)

    @Test
    fun occupancyCategories_siri1AndSiri2() {
        assertEquals(Crowding.SEATS, occupancyLevel("seatsAvailable"))
        assertEquals(Crowding.SEATS, occupancyLevel("manySeatsAvailable"))
        assertEquals(Crowding.SEATS, occupancyLevel("FEW_SEATS_AVAILABLE"))
        assertEquals(Crowding.STANDING, occupancyLevel("standingAvailable"))
        assertEquals(Crowding.STANDING, occupancyLevel("standingRoomOnly"))
        assertEquals(Crowding.FULL, occupancyLevel("full"))
        assertEquals(Crowding.FULL, occupancyLevel("crushedStandingRoomOnly"))
        assertEquals(Crowding.FULL, occupancyLevel("notAcceptingPassengers"))
        assertNull(occupancyLevel(""))
        assertNull(occupancyLevel("unknown"))
        assertNull(occupancyLevel(null))
    }

    @Test
    fun categoryWins_countStillShown() {
        val info = crowdInfo(
            JsonPrimitive("standingAvailable"),
            caps("""{"EstimatedPassengerCount":12,"EstimatedPassengerCapacity":60}"""),
        )
        assertEquals(CrowdInfo(Crowding.STANDING, 12), info)
    }

    @Test
    fun countOnly_levelFromRatio() {
        assertEquals(Crowding.SEATS, crowdInfo(null, caps("""{"EstimatedPassengerCount":10,"EstimatedPassengerCapacity":60}""")).level)
        assertEquals(Crowding.STANDING, crowdInfo(null, caps("""{"EstimatedPassengerCount":"40","EstimatedPassengerCapacity":"60"}""")).level)
        assertEquals(Crowding.FULL, crowdInfo(null, caps("""{"EstimatedPassengerCount":58,"EstimatedPassengerCapacity":60}""")).level)
        // No capacity: count shown, level unknown.
        assertEquals(CrowdInfo(null, 7), crowdInfo(null, caps("""[{"EstimatedPassengerCount":7}]""")))
    }

    @Test
    fun oddOrMissingData_isUnknownNotACrash() {
        assertEquals(CrowdInfo(null, null), crowdInfo(null, null))
        assertEquals(CrowdInfo(null, null), crowdInfo(JsonNull, caps("""{"EstimatedPassengerCount":"lots"}""")))
        assertEquals(CrowdInfo(null, null), crowdInfo(caps("""{"x":1}"""), caps("""{"EstimatedPassengerCount":-1,"EstimatedPassengerCapacity":0}""")))
    }

    @Test
    fun labels() {
        assertEquals("Seats", crowdLabel(Crowding.SEATS, null))
        assertEquals("Standing · 34 aboard", crowdLabel(Crowding.STANDING, 34))
        assertEquals("Full", crowdLabel(Crowding.FULL, null))
        assertEquals("12 aboard", crowdLabel(null, 12))
        assertEquals("", crowdLabel(null, null))
    }

    @Test
    fun endToEnd_fromSiriJsonToArrival() {
        val sample = """
        {"Siri":{"ServiceDelivery":{"StopMonitoringDelivery":[{"MonitoredStopVisit":[
          {"MonitoredVehicleJourney":{"PublishedLineName":"B9","DestinationName":"KINGS PLAZA",
            "VehicleRef":"MTA NYCT_1","Occupancy":"full",
            "MonitoredCall":{"ExpectedArrivalTime":"2026-11-11T14:25:00.000-05:00",
              "Extensions":{"Capacities":{"EstimatedPassengerCount":61,"EstimatedPassengerCapacity":62}}}}},
          {"MonitoredVehicleJourney":{"PublishedLineName":"B9","DestinationName":"KINGS PLAZA",
            "VehicleRef":"MTA NYCT_2",
            "MonitoredCall":{"ExpectedArrivalTime":"2026-11-11T14:35:00.000-05:00"}}}
        ]}]}}}
        """.trimIndent()
        val journeys = json.decodeFromString<SiriResponse>(sample)
            .siri!!.serviceDelivery!!.stopMonitoringDelivery
            .flatMap { it.monitoredStopVisit }
            .mapNotNull { it.monitoredVehicleJourney }
        val stop = com.dngro.transit.data.MtaStop(id = "MTA_1", name = "Flatbush Av")

        val arrivals = buildRoutes(listOf(StopArrivals(stop, 100f, journeys)), emptyMap(), emptySet(), 15)
            .single().directions.single().arrivals

        assertEquals(Crowding.FULL, arrivals[0].crowding)
        assertEquals(61, arrivals[0].passengers)
        assertNull(arrivals[1].crowding)
        assertNull(arrivals[1].passengers)
    }
}
