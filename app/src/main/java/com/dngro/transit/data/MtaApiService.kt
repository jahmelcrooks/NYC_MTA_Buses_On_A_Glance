package com.dngro.transit.data

import retrofit2.http.GET
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
}
