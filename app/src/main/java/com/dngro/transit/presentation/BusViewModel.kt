package com.dngro.transit.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dngro.transit.data.AppPrefs
import com.dngro.transit.data.BusApiException
import com.dngro.transit.data.BusError
import com.dngro.transit.data.BusRepositories
import com.dngro.transit.data.BusRepository
import com.dngro.transit.data.Favorite
import com.dngro.transit.data.MtaStop
import com.dngro.transit.data.cleanApiKey
import com.dngro.transit.data.normalizeRadiusMiles
import com.dngro.transit.data.radiusLabel
import com.dngro.transit.data.radiusMeters
import com.dngro.transit.data.withFavoriteToggled
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BusViewModel(application: Application) : AndroidViewModel(application) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)

    private val favorites = MutableStateFlow(AppPrefs.loadFavorites(application))

    private val _radiusMiles = MutableStateFlow(AppPrefs.searchRadiusMiles(application))
    val radiusMiles: StateFlow<Double> = _radiusMiles.asStateFlow()

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Only one refresh runs at a time; starting a new one cancels the old one. */
    private var refreshJob: Job? = null
    private var lastLoadedAtMillis = 0L

    /** The nearby-stops answer, reused while the user stays put so refreshes only fetch arrivals. */
    private var stopsCache: StopsSnapshot? = null

    // No refresh in init: the UI starts one once location permission is known to be granted.

    /**
     * Last session's results if they're recent, so the app opens with something on screen
     * while the real refresh runs. Arrivals that have already left are dropped.
     */
    private fun initialState(): UiState {
        val snapshot = decodeSnapshot(AppPrefs.snapshotJson(getApplication())) ?: return UiState.Loading
        val ageMillis = System.currentTimeMillis() - snapshot.savedAtEpochMillis
        if (ageMillis !in 0..SNAPSHOT_MAX_AGE_MS) return UiState.Loading

        val routes = pruneExpired(snapshot.routes, Instant.now())
        if (routes.isEmpty()) return UiState.Loading

        val keys = favoriteKeys()
        return UiState.Success(
            routes = applyFavorites(routes, keys),
            favoriteKeys = keys,
            fromCache = true,
        )
    }

    /**
     * Locate the user, find nearby stops and load arrivals. Existing results stay on screen
     * while this runs; the full-screen spinner only shows when there is nothing to show yet.
     */
    fun refresh() {
        refreshJob?.cancel()
        if (_uiState.value is UiState.Success) {
            _isRefreshing.value = true
        } else {
            _uiState.value = UiState.Loading
        }

        refreshJob = viewModelScope.launch {
            try {
                if (AppPrefs.apiKey(getApplication()).isBlank()) {
                    throw BusApiException(BusError.NO_API_KEY, "No MTA API key configured")
                }
                val repository = BusRepositories.forContext(getApplication())

                val location = currentLocation() ?: throw LocationUnavailableException()
                val miles = _radiusMiles.value
                val allStops = nearbyStops(repository, location, miles)
                val selection = selectWithinTiers(
                    items = allStops.map { stop ->
                        stop to distanceMeters(location.latitude, location.longitude, stop.lat, stop.lon)
                    },
                    tiers = listOf(radiusMeters(miles)),
                    minCount = 1,
                )

                if (selection.items.isEmpty()) {
                    showError("No bus stops within ${radiusLabel(miles)}. Try a larger radius in Settings.")
                    return@launch
                }

                val stopArrivals = fetchArrivals(repository, selection.items.take(MAX_STOPS_TO_QUERY))
                val routeLongNames = selection.items
                    .flatMap { it.first.routes }
                    .filter { it.shortName.isNotBlank() }
                    .associate { it.shortName to it.longName.orEmpty() }

                val routes = buildRoutes(
                    stopArrivals = stopArrivals,
                    routeLongNames = routeLongNames,
                    favoriteKeys = favoriteKeys(),
                    maxRoutes = MAX_ROUTES_TO_DISPLAY,
                )
                if (routes.isEmpty()) {
                    showError("No upcoming buses found nearby")
                    return@launch
                }
                showRoutes(routes)
            } catch (e: BusApiException) {
                Log.w(TAG, "Refresh failed (${e.error}): ${e.message}")
                showFailure(
                    message = e.error.userMessage(),
                    needsApiKey = e.error == BusError.NO_API_KEY || e.error == BusError.BAD_API_KEY,
                )
            } catch (e: LocationUnavailableException) {
                showFailure("Couldn't get your location. Is location on?")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Safety net: an unexpected bug should show an error, not crash the app.
                Log.e(TAG, "Unexpected refresh failure", e)
                showFailure("Something went wrong. Try again.")
            }
        }
    }

    /**
     * Refresh only if what's on screen is old or missing. Safe to call as often as you like.
     * [retryErrors] = false skips automatic retries of an error screen (e.g. a missing API key).
     */
    fun refreshIfStale(retryErrors: Boolean = true) {
        if (refreshJob?.isActive == true) return
        when (val state = _uiState.value) {
            is UiState.Success -> {
                if (SystemClock.elapsedRealtime() - lastLoadedAtMillis >= STALE_AFTER_MS) refresh()
            }
            is UiState.Error -> if (retryErrors && !state.needsApiKey) refresh()
            UiState.Loading -> refresh()
        }
    }

    /** Stores a key the user entered on the watch, then tries again with it. */
    fun saveApiKey(rawKey: String) {
        val key = cleanApiKey(rawKey)
        if (key.isBlank()) return
        AppPrefs.saveApiKey(getApplication(), key)
        // Without location permission the UI shows the permission screen, and it starts the
        // refresh itself once the permission is granted.
        if (hasLocationPermission()) refresh()
    }

    /** Sets the search radius (one of the Settings options) and reloads with it. */
    fun setRadiusMiles(miles: Double) {
        val value = normalizeRadiusMiles(miles)
        if (value == _radiusMiles.value) return
        AppPrefs.saveSearchRadiusMiles(getApplication(), value)
        _radiusMiles.value = value
        stopsCache = null
        if (hasLocationPermission()) refresh()
    }

    private suspend fun nearbyStops(repository: BusRepository, location: Location, miles: Double): List<MtaStop> {
        val cached = stopsCache
        if (cached != null && cached.miles == miles &&
            SystemClock.elapsedRealtime() - cached.loadedAtMillis < STOPS_CACHE_MS &&
            distanceMeters(location.latitude, location.longitude, cached.latitude, cached.longitude) <
            STOPS_CACHE_MOVE_METERS
        ) {
            return cached.stops
        }
        val stops = repository.getNearbyStops(
            lat = location.latitude,
            lon = location.longitude,
            radiusMeters = radiusMeters(miles).toInt(),
        )
        if (stops.isNotEmpty()) {
            stopsCache = StopsSnapshot(location.latitude, location.longitude, SystemClock.elapsedRealtime(), stops, miles)
        }
        return stops
    }

    private suspend fun fetchArrivals(
        repository: BusRepository,
        stops: List<Pair<MtaStop, Float>>,
    ): List<StopArrivals> {
        val results = coroutineScope {
            stops.map { (stop, meters) ->
                async {
                    try {
                        val monitoring = repository.getStopMonitoring(stop.id)
                        Result.success(StopArrivals(stop, meters, monitoring.journeys, monitoring.alerts))
                    } catch (e: BusApiException) {
                        Result.failure<StopArrivals>(e)
                    }
                }
            }.awaitAll()
        }
        val succeeded = results.mapNotNull { it.getOrNull() }
        if (succeeded.isEmpty()) {
            // Every stop failed, so surface the real reason instead of "no buses".
            throw results.firstNotNullOfOrNull { it.exceptionOrNull() }
                ?: BusApiException(BusError.SERVER, "No data returned")
        }
        return succeeded
    }

    private fun showRoutes(routes: List<BusRouteUiModel>) {
        lastLoadedAtMillis = SystemClock.elapsedRealtime()
        // Re-read the stars now: the user may have starred something while this refresh ran.
        val keys = favoriteKeys()
        val displayRoutes = applyFavorites(routes, keys)
        _uiState.update { current ->
            val previous = current as? UiState.Success
            UiState.Success(
                routes = displayRoutes,
                expandedRouteIds = previous?.expandedRouteIds.orEmpty(),
                favoriteKeys = keys,
            )
        }
        _isRefreshing.value = false
        saveSnapshot(displayRoutes)
    }

    private fun saveSnapshot(routes: List<BusRouteUiModel>) {
        try {
            val snapshot = Snapshot(System.currentTimeMillis(), routes)
            AppPrefs.saveSnapshotJson(getApplication(), encodeSnapshot(snapshot))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save results for next launch: ${e.message}")
        }
    }

    /** A real, authoritative answer such as "no stops here". Replaces whatever is showing. */
    private fun showError(message: String) {
        _uiState.value = UiState.Error(message)
        _isRefreshing.value = false
    }

    /**
     * A failed refresh. Keeps the last good results on screen (marked stale) if there are any,
     * except for API key problems: those never fix themselves, so the screen must say so.
     */
    private fun showFailure(message: String, needsApiKey: Boolean = false) {
        _uiState.update { current ->
            if (current is UiState.Success && !needsApiKey) {
                current.copy(stale = true, fromCache = false)
            } else {
                UiState.Error(message, needsApiKey)
            }
        }
        _isRefreshing.value = false
    }

    /**
     * Uses a recent cached fix immediately. Otherwise asks for a low-power fix first and only
     * turns on high-accuracy GPS if that doesn't answer in time. A cached fix is a last resort,
     * and only if it is under 5 minutes old (an old one would show buses near somewhere the
     * user has already left). Returns null if nothing works.
     * Callers must already hold a location permission.
     */
    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        val cached = try {
            fusedLocationClient.lastLocation.awaitTask()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (cached != null && ageNanos(cached) < FRESH_FIX_NANOS) return cached

        val fix = requestFix(Priority.PRIORITY_BALANCED_POWER_ACCURACY, BALANCED_TIMEOUT_MS)
            ?: requestFix(Priority.PRIORITY_HIGH_ACCURACY, HIGH_ACCURACY_TIMEOUT_MS)
        return fix ?: cached?.takeIf { ageNanos(it) < MAX_FALLBACK_FIX_NANOS }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFix(priority: Int, timeoutMs: Long): Location? {
        val cancellation = CancellationTokenSource()
        return try {
            withTimeoutOrNull(timeoutMs) {
                try {
                    fusedLocationClient.getCurrentLocation(priority, cancellation.token).awaitTask()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // SecurityException, Play services failure, etc.
                }
            }
        } finally {
            cancellation.cancel()
        }
    }

    private fun hasLocationPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(getApplication(), it) == PackageManager.PERMISSION_GRANTED
        }

    private fun ageNanos(location: Location): Long =
        SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    fun toggleRouteExpansion(routeId: String) {
        _uiState.update { state ->
            if (state is UiState.Success) {
                val newExpanded = if (state.expandedRouteIds.contains(routeId)) {
                    state.expandedRouteIds - routeId
                } else {
                    state.expandedRouteIds + routeId
                }
                state.copy(expandedRouteIds = newExpanded)
            } else {
                state
            }
        }
    }

    /** Stars or unstars "this route, this direction, from this stop". */
    fun toggleFavorite(route: BusRouteUiModel, direction: BusDirectionUiModel) {
        if (direction.stopId.isBlank()) return
        val favorite = Favorite(
            stopId = direction.stopId,
            stopName = direction.stopName,
            route = route.routeShortName,
            destination = direction.destinationName,
        )
        val updated = withFavoriteToggled(favorites.value, favorite)
        favorites.value = updated
        AppPrefs.saveFavorites(getApplication(), updated)

        try {
            androidx.wear.tiles.TileService.getUpdater(getApplication())
                .requestUpdate(com.dngro.transit.tile.MainTileService::class.java)
        } catch (e: Exception) {
            Log.d(TAG, "Tile update info: ${e.message}")
        }

        try {
            com.dngro.transit.complication.MainComplicationService.requestUpdate(getApplication())
        } catch (e: Exception) {
            Log.d(TAG, "Complication update info: ${e.message}")
        }

        val keys = favoriteKeys()
        _uiState.update { state ->
            if (state is UiState.Success) {
                state.copy(routes = applyFavorites(state.routes, keys), favoriteKeys = keys)
            } else {
                state
            }
        }
    }

    private fun favoriteKeys(): Set<String> = favorites.value.map { it.key }.toSet()

    private class StopsSnapshot(
        val latitude: Double,
        val longitude: Double,
        val loadedAtMillis: Long,
        val stops: List<MtaStop>,
        val miles: Double,
    )

    private companion object {
        const val TAG = "BusViewModel"
        const val MAX_ROUTES_TO_DISPLAY = 15

        /** Arrivals are fetched per stop, so this caps the requests made per refresh. */
        const val MAX_STOPS_TO_QUERY = 6
        const val STALE_AFTER_MS = 60_000L

        /** Reuse the stop list for this long, while the user stays within the distance below. */
        const val STOPS_CACHE_MS = 10 * 60_000L
        const val STOPS_CACHE_MOVE_METERS = 150f

        /** Saved results older than this are ignored on launch (the user has likely moved on). */
        const val SNAPSHOT_MAX_AGE_MS = 30 * 60_000L

        const val BALANCED_TIMEOUT_MS = 8_000L
        const val HIGH_ACCURACY_TIMEOUT_MS = 12_000L
        const val FRESH_FIX_NANOS = 60_000_000_000L // 60 s

        /** A cached fix older than this is not trusted as "where the user is now". */
        const val MAX_FALLBACK_FIX_NANOS = 300_000_000_000L // 5 min
    }
}

private class LocationUnavailableException : Exception()

private fun BusError.userMessage(): String = when (this) {
    BusError.NO_API_KEY -> "Add your MTA API key to get started."
    BusError.BAD_API_KEY -> "MTA rejected the API key. Enter a new one."
    BusError.NETWORK -> "No connection. Check Wi-Fi or LTE."
    BusError.SERVER -> "MTA data unavailable. Try again."
}

private suspend fun <T> Task<T>.awaitTask(): T? = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    // A cancelled task means "no fix", not "cancel the caller": returning null lets the
    // refresh finish with an error instead of silently dying with a spinner still showing.
    addOnCanceledListener { continuation.resume(null) }
}
