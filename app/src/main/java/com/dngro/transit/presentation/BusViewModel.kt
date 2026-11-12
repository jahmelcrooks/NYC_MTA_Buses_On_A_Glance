package com.dngro.transit.presentation

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dngro.transit.BuildConfig
import com.dngro.transit.data.BusApiException
import com.dngro.transit.data.BusError
import com.dngro.transit.data.BusRepository
import com.dngro.transit.data.MtaStop
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BusViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BusRepository(BuildConfig.MTA_API_KEY)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private val prefs = application.getSharedPreferences("nyc_buses_prefs", Context.MODE_PRIVATE)

    private val _pinnedRouteIds = MutableStateFlow<Set<String>>(
        prefs.getStringSet("pinned_routes", emptySet()) ?: emptySet(),
    )

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Only one refresh runs at a time; starting a new one cancels the old one. */
    private var refreshJob: Job? = null
    private var lastLoadedAtMillis = 0L

    // No refresh in init: the UI starts one once location permission is known to be granted.

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
                val location = currentLocation() ?: throw LocationUnavailableException()

                val nearby = repository.getNearbyStops(location.latitude, location.longitude)
                    .map { stop ->
                        stop to distanceMeters(location.latitude, location.longitude, stop.lat, stop.lon)
                    }
                    .filter { (_, meters) -> meters <= MAX_RADIUS_METERS }
                    .sortedBy { (_, meters) -> meters }

                if (nearby.isEmpty()) {
                    showError("No bus stops within 0.5 mi")
                    return@launch
                }

                val stopArrivals = fetchArrivals(nearby.take(MAX_STOPS_TO_QUERY))
                val routeLongNames = nearby
                    .flatMap { it.first.routes }
                    .filter { it.shortName.isNotBlank() }
                    .associate { it.shortName to it.longName.orEmpty() }

                val routes = buildRoutes(
                    stopArrivals = stopArrivals,
                    routeLongNames = routeLongNames,
                    pinnedRoutes = _pinnedRouteIds.value,
                    maxRoutes = MAX_ROUTES_TO_DISPLAY,
                )
                if (routes.isEmpty()) {
                    showError("No upcoming buses nearby (< 0.5 mi)")
                    return@launch
                }
                showRoutes(routes)
            } catch (e: BusApiException) {
                Log.w(TAG, "Refresh failed (${e.error}): ${e.message}")
                showFailure(e.error.userMessage())
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
        when (_uiState.value) {
            is UiState.Success -> {
                if (SystemClock.elapsedRealtime() - lastLoadedAtMillis >= STALE_AFTER_MS) refresh()
            }
            is UiState.Error -> if (retryErrors) refresh()
            UiState.Loading -> refresh()
        }
    }

    private suspend fun fetchArrivals(stops: List<Pair<MtaStop, Float>>): List<StopArrivals> {
        val results = coroutineScope {
            stops.map { (stop, meters) ->
                async {
                    try {
                        Result.success(StopArrivals(stop, meters, repository.getBusArrivals(stop.id)))
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
        // Re-read the stars now: the user may have starred a route while this refresh ran.
        val pinned = _pinnedRouteIds.value
        val displayRoutes = routes
            .map { it.copy(isPinned = it.routeShortName in pinned) }
            .sortedWith(
                compareByDescending<BusRouteUiModel> { it.isPinned }
                    .thenBy { it.nearestStopDistanceMeters },
            )
        _uiState.update { current ->
            val previous = current as? UiState.Success
            UiState.Success(
                routes = displayRoutes,
                expandedRouteIds = previous?.expandedRouteIds.orEmpty(),
                expandedDirectionIds = previous?.expandedDirectionIds.orEmpty(),
                pinnedRouteIds = pinned,
            )
        }
        _isRefreshing.value = false
    }

    /** A real, authoritative answer such as "no stops here". Replaces whatever is showing. */
    private fun showError(message: String) {
        _uiState.value = UiState.Error(message)
        _isRefreshing.value = false
    }

    /** A failed refresh. Keeps the last good results on screen (marked stale) if there are any. */
    private fun showFailure(message: String) {
        _uiState.update { current ->
            if (current is UiState.Success) current.copy(stale = true) else UiState.Error(message)
        }
        _isRefreshing.value = false
    }

    /**
     * Uses a recent cached fix immediately; otherwise asks for a fresh one and gives up
     * after [LOCATION_TIMEOUT_MS], falling back to a cached fix only if it is under 5 minutes old
     * (an old fix would show buses near somewhere the user has already left). Returns null otherwise.
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

        val cancellation = CancellationTokenSource()
        return try {
            withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                try {
                    fusedLocationClient
                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                        .awaitTask()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            } ?: cached?.takeIf { ageNanos(it) < MAX_FALLBACK_FIX_NANOS }
        } finally {
            cancellation.cancel()
        }
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

    fun toggleRoutePin(routeShortName: String) {
        val current = _pinnedRouteIds.value
        val updated = if (current.contains(routeShortName)) {
            current - routeShortName
        } else {
            current + routeShortName
        }
        _pinnedRouteIds.value = updated
        prefs.edit {
            putStringSet("pinned_routes", updated)
        }

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

        _uiState.update { state ->
            if (state is UiState.Success) {
                val updatedRoutes = state.routes.map { route ->
                    if (route.routeShortName == routeShortName) {
                        route.copy(isPinned = updated.contains(routeShortName))
                    } else {
                        route
                    }
                }.sortedWith(
                    compareByDescending<BusRouteUiModel> { it.isPinned }
                        .thenBy { it.nearestStopDistanceMeters },
                )
                state.copy(routes = updatedRoutes, pinnedRouteIds = updated)
            } else {
                state
            }
        }
    }

    private companion object {
        const val TAG = "BusViewModel"
        const val MAX_ROUTES_TO_DISPLAY = 15
        const val MAX_RADIUS_METERS = 804.67f // 0.5 miles

        /** Arrivals are fetched per stop, so this caps the requests made per refresh. */
        const val MAX_STOPS_TO_QUERY = 8
        const val STALE_AFTER_MS = 60_000L
        const val LOCATION_TIMEOUT_MS = 15_000L
        const val FRESH_FIX_NANOS = 60_000_000_000L // 60 s

        /** A cached fix older than this is not trusted as "where the user is now". */
        const val MAX_FALLBACK_FIX_NANOS = 300_000_000_000L // 5 min
    }
}

private class LocationUnavailableException : Exception()

private fun BusError.userMessage(): String = when (this) {
    BusError.NO_API_KEY -> "MTA API key missing. See the README."
    BusError.BAD_API_KEY -> "MTA rejected the API key."
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
