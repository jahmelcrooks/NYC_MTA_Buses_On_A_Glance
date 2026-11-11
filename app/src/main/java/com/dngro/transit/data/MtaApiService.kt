package com.dngro.transit.data

import retrofit2.http.GET
import retrofit2.http.Query

interface MtaApiService {
    /**
     * Discovery API: Find nearby stops based on location.
     */
    @GET("api/where/stops-for-location.json")
    suspend fun getStopsForLocation(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("latSpan") latSpan: Double = 0.0075,
        @Query("lonSpan") lonSpan: Double = 0.0095,
        @Query("radius") radius: Int = 805,
        @Query("key") apiKey: String,
    ): StopResponse

    /**
     * SIRI API: Get real-time arrival information for a specific stop.
     */
    @GET("api/siri/stop-monitoring.json")
    suspend fun getStopMonitoring(
        @Query("MonitoringRef") stopId: String,
        @Query("key") apiKey: String,
        @Query("version") version: String = "2",
    ): SiriResponse
}
