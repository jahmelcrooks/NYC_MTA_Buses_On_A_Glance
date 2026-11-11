package com.dngro.transit.data

import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

class BusRepository(private val apiKey: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val apiService: MtaApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl("https://bustime.mta.info/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MtaApiService::class.java)
    }

    /**
     * Fetches nearby stops and returns them as a Flow.
     * Handles exceptions and returns an empty list on failure.
     */
    fun getNearbyStops(lat: Double, lon: Double): Flow<List<MtaStop>> = flow {
        Log.d("BusRepository", "Fetching stops for lat: $lat, lon: $lon...")
        try {
            // Fetch stops within ~0.5 miles (805 meters) radius around current GPS location
            val response = apiService.getStopsForLocation(
                lat = lat,
                lon = lon,
                latSpan = 0.0075,
                lonSpan = 0.0095,
                radius = 805,
                apiKey = apiKey,
            )
            if (response.code != 200) {
                Log.e("BusRepository", "MTA API Error: ${response.code} - ${response.text}")
                emit(emptyList())
            } else {
                val stops = response.data?.stops ?: emptyList()
                Log.d("BusRepository", "Found ${stops.size} stops")
                emit(stops)
            }
        } catch (e: Exception) {
            Log.e("BusRepository", "Error fetching stops: ${e.message}", e)
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Fetches real-time bus arrivals for a specific stop ID.
     * Flattens the deep SIRI response into a simple list of journeys.
     */
    fun getBusArrivals(stopId: String): Flow<List<MonitoredVehicleJourney>> = flow {
        try {
            val response = apiService.getStopMonitoring(
                stopId = stopId,
                apiKey = apiKey,
            )
            val arrivals = response.siri.serviceDelivery.stopMonitoringDelivery
                .flatMap { it.monitoredStopVisit }
                .map { it.monitoredVehicleJourney }
            emit(arrivals)
        } catch (e: Exception) {
            e.printStackTrace()
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)
}
