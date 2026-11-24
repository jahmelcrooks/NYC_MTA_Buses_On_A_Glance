package com.dngro.transit.data

import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface MtaApiService {
    /** OneBusAway discovery API: stops inside a lat/lon box (see Geo.kt for the spans). */
    @GET("api/where/stops-for-location.json")
    suspend fun getStopsForLocation(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("latSpan") latSpan: Double,
        @Query("lonSpan") lonSpan: Double,
        @Query("radius") radius: Int,
        @Query("key") apiKey: String,
    ): StopResponse

    /** SIRI StopMonitoring: live arrivals (and any service alerts) for one stop. */
    @GET("api/siri/stop-monitoring.json")
    suspend fun getStopMonitoring(
        @Query("MonitoringRef") stopId: String,
        @Query("key") apiKey: String,
        @Query("version") version: String = "2",
    ): SiriResponse

    /**
     * OneBusAway timetable for one stop and service date (YYYY-MM-DD). Returned raw and read
     * tolerantly by parseSchedule(): it's a bonus, so an odd shape must never break anything.
     */
    @GET("api/where/schedule-for-stop/{stopId}.json")
    suspend fun getScheduleForStop(
        @Path("stopId") stopId: String,
        @Query("date") date: String,
        @Query("key") apiKey: String,
    ): JsonElement
}
