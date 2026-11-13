package com.dngro.transit.data

import android.util.Log
import com.dngro.transit.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Why a request failed, so the UI can say something more useful than "no buses". */
enum class BusError {
    NO_API_KEY,
    BAD_API_KEY,
    NETWORK,
    SERVER,
}

class BusApiException(
    val error: BusError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** What one stop reported: the buses coming, and any alerts. */
data class StopMonitoring(
    val journeys: List<MonitoredVehicleJourney>,
    val alerts: List<ServiceAlert>,
)

class BusRepository(private val apiKey: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val apiService: MtaApiService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .apply {
                // Request URLs contain the API key and the user's coordinates, so only
                // log in debug builds, and redact the key even there.
                if (BuildConfig.DEBUG) addInterceptor(redactedLogging())
            }
            .build()

        Retrofit.Builder()
            .baseUrl("https://bustime.mta.info/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MtaApiService::class.java)
    }

    /**
     * Stops in a box reaching [radiusMeters] around the location. The caller filters by true
     * distance (the box corners are farther than the radius).
     * @throws BusApiException on any failure.
     */
    suspend fun getNearbyStops(lat: Double, lon: Double, radiusMeters: Int): List<MtaStop> {
        requireKey()
        val response = call {
            apiService.getStopsForLocation(
                lat = lat,
                lon = lon,
                latSpan = latSpanDegrees(radiusMeters.toDouble()),
                lonSpan = lonSpanDegrees(radiusMeters.toDouble(), lat),
                radius = radiusMeters,
                apiKey = apiKey,
            )
        }
        checkEnvelope(response.code, response.text)
        return response.data?.allStops.orEmpty()
            .filter { it.id.isNotBlank() && !it.lat.isNaN() && !it.lon.isNaN() }
    }

    /**
     * Real-time arrivals for one stop, plus any service alerts in the same response.
     * @throws BusApiException on any failure.
     */
    suspend fun getStopMonitoring(stopId: String): StopMonitoring {
        requireKey()
        val response = call {
            apiService.getStopMonitoring(
                stopId = stopId,
                apiKey = apiKey,
            )
        }
        val delivery = response.siri?.serviceDelivery
        return StopMonitoring(
            journeys = delivery?.stopMonitoringDelivery.orEmpty()
                .flatMap { it.monitoredStopVisit }
                .mapNotNull { it.monitoredVehicleJourney },
            alerts = parseAlerts(delivery?.situationExchangeDelivery),
        )
    }

    /** Just the buses, for callers (tile, complication) that don't show alerts. */
    suspend fun getBusArrivals(stopId: String): List<MonitoredVehicleJourney> =
        getStopMonitoring(stopId).journeys

    private fun requireKey() {
        if (apiKey.isBlank()) {
            throw BusApiException(BusError.NO_API_KEY, "No MTA API key configured")
        }
    }

    private fun checkEnvelope(code: Int, text: String?) {
        when (code) {
            in 200..299 -> Unit
            401, 403 -> throw BusApiException(BusError.BAD_API_KEY, "MTA rejected the API key ($code)")
            else -> throw BusApiException(BusError.SERVER, "MTA API error $code: $text")
        }
    }

    private suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val error = if (e.code() == 401 || e.code() == 403) BusError.BAD_API_KEY else BusError.SERVER
            throw BusApiException(error, "HTTP ${e.code()}", e)
        } catch (e: IOException) {
            throw BusApiException(BusError.NETWORK, e.message ?: "Network error", e)
        } catch (e: SerializationException) {
            throw BusApiException(BusError.SERVER, "Unreadable MTA response", e)
        }

    private fun redactedLogging(): HttpLoggingInterceptor {
        val logger = object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                Log.d(TAG, KEY_PARAM.replace(message, "\$1REDACTED"))
            }
        }
        return HttpLoggingInterceptor(logger).apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
    }

    private companion object {
        const val TAG = "BusRepository"
        val KEY_PARAM = Regex("([?&]key=)[^&\\s]+")
    }
}
