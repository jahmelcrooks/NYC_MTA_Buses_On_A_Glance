package com.dngro.transit.presentation

import com.dngro.transit.data.MonitoredCall
import com.dngro.transit.data.MonitoredVehicleJourney
import com.dngro.transit.data.MtaStop
import com.dngro.transit.data.ScheduledTrip
import com.dngro.transit.data.parseSchedule
import com.dngro.transit.data.serviceDates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ScheduleTest {

    private val now = Instant.parse("2026-11-11T19:00:00Z") // 2:00 pm in New York
    private val json = Json { ignoreUnknownKeys = true }

    private fun at(minutes: Long) = now.plusSeconds(minutes * 60)

    private fun liveJourney(route: String, dest: String, minutes: Long, tripId: String? = null, status: String? = null) =
        MonitoredVehicleJourney(
            lineName = route,
            destinationName = dest,
            vehicleRef = "V$minutes",
            progressStatus = status?.let { JsonPrimitive(it) },
            framedVehicleJourneyRef = tripId?.let { id -> buildJsonObject { put("DatedVehicleJourneyRef", id) } },
            monitoredCall = MonitoredCall(expectedArrivalTime = at(minutes).toString()),
        )

    private fun trip(route: String, headsign: String, minutes: Long, tripId: String = "T$minutes") =
        ScheduledTrip(route, headsign, tripId, at(minutes))

    private val stop = MtaStop(id = "MTA_1", name = "Flatbush Av")

    private fun build(journeys: List<MonitoredVehicleJourney>, scheduled: List<ScheduledTrip>) =
        buildRoutes(listOf(StopArrivals(stop, 100f, journeys, scheduled = scheduled)), emptyMap(), emptySet(), 15, now)

    @Test
    fun parseSchedule_readsRoutesDirectionsAndTimes() {
        val raw = """
        {"code":200,"data":{"entry":{"stopId":"MTA_1","stopRouteSchedules":[
          {"routeId":"MTA NYCT_B9","stopRouteDirectionSchedules":[
            {"tripHeadsign":"KINGS PLAZA","scheduleStopTimes":[
              {"arrivalTime":${at(10).toEpochMilli()},"departureTime":0,"tripId":"MTA NYCT_A1"},
              {"departureTime":"${at(25).toEpochMilli()}","tripId":"MTA NYCT_A2","stopHeadsign":"KINGS PLAZA VIA AV M"}]}]},
          {"routeId":"MTA NYCT_B41-SBS","stopRouteDirectionSchedules":[{"scheduleStopTimes":[{"arrivalTime":${at(5).toEpochMilli()}}]}]}
        ]},"references":{"routes":[{"id":"MTA NYCT_B9","shortName":"B9"}]}}}
        """.trimIndent()

        val trips = parseSchedule(json.parseToJsonElement(raw))

        assertEquals(3, trips.size)
        assertEquals(ScheduledTrip("B9", "KINGS PLAZA", "MTA NYCT_A1", at(10)), trips[0])
        assertEquals("KINGS PLAZA VIA AV M", trips[1].headsign)
        assertEquals(at(25), trips[1].time)
        assertEquals("B41-SBS", trips[2].route) // no reference: taken from the id
        assertEquals("Unknown", trips[2].headsign)
    }

    @Test
    fun parseSchedule_errorsAndOddShapesAreEmpty() {
        assertTrue(parseSchedule(null).isEmpty())
        assertTrue(parseSchedule(json.parseToJsonElement("""{"code":404,"text":"not found"}""")).isEmpty())
        assertTrue(parseSchedule(json.parseToJsonElement("""{"data":{"entry":{"stopRouteSchedules":"x"}}}""")).isEmpty())
        assertTrue(parseSchedule(json.parseToJsonElement("[1,2]")).isEmpty())
    }

    @Test
    fun serviceDates_includeYesterdayOnlyBefore4amNewYorkTime() {
        assertEquals(listOf(LocalDate.of(2026, 11, 11)), serviceDates(now))
        // 06:30 UTC = 1:30 am in New York on the 12th.
        assertEquals(
            listOf(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 12)),
            serviceDates(Instant.parse("2026-11-12T06:30:00Z")),
        )
    }

    @Test
    fun scheduledTimesFollowTheLiveOnes_andMatchingTripsAreNotRepeated() {
        val arrivals = build(
            journeys = listOf(liveJourney("B9", "KINGS PLAZA", 4, tripId = "T4"), liveJourney("B9", "KINGS PLAZA", 12)),
            scheduled = listOf(
                trip("B9", "KINGS PLAZA", 4), // same trip as the first live bus
                trip("B9", "KINGS PLAZA", 8), // before the last live bus: covered
                trip("B9", "Kings Plaza", 20), // spelled differently: still the same direction
                trip("B9", "KINGS PLAZA", 35),
                trip("B9", "KINGS PLAZA", 200), // beyond the 2-hour horizon
                trip("B9", "KINGS PLAZA", -10), // already gone
            ),
        ).single().directions.single().arrivals

        assertEquals(listOf(at(4), at(12), at(20), at(35)), arrivals.map { it.expected })
        assertEquals(listOf(true, true, false, false), arrivals.map { it.live })
        assertEquals("Scheduled", arrivals[2].distanceText)
    }

    @Test
    fun aDirectionWithNoLiveBusesShowsItsTimetable() {
        val routes = build(
            journeys = listOf(liveJourney("B9", "KINGS PLAZA", 4)),
            scheduled = listOf(trip("B9", "BAY RIDGE", 15), trip("B70", "SUNSET PARK", 9)),
        )

        val b9 = routes.single { it.routeShortName == "B9" }
        assertEquals(setOf("KINGS PLAZA", "BAY RIDGE"), b9.directions.map { it.destinationName }.toSet())
        val bayRidge = b9.directions.single { it.destinationName == "BAY RIDGE" }.arrivals.single()
        assertFalse(bayRidge.live)
        assertTrue(routes.any { it.routeShortName == "B70" })
    }

    @Test
    fun aBusWaitingAtTheTerminalCountsAsScheduled() {
        val arrivals = build(
            journeys = listOf(
                liveJourney("B9", "KINGS PLAZA", 6),
                liveJourney("B9", "KINGS PLAZA", 18, status = "layover"),
                liveJourney("B9", "KINGS PLAZA", 30, status = "prevTrip"),
            ),
            scheduled = emptyList(),
        ).single().directions.single().arrivals

        assertEquals(listOf(true, false, false), arrivals.map { it.live })
    }

    @Test
    fun nextBusCarriesTheLiveFlag() {
        val routes = build(journeys = emptyList(), scheduled = listOf(trip("B9", "KINGS PLAZA", 15)))
        val next = nextBus(routes, now)!!
        assertFalse(next.live)
    }
}
