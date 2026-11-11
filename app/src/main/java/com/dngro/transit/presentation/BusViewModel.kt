package com.dngro.transit.presentation

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dngro.transit.BuildConfig
import com.dngro.transit.data.BusRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

sealed class UiState {
    object Loading : UiState()
    data class Success(
        val routes: List<BusRouteUiModel>,
        val expandedRouteIds: Set<String> = emptySet(),
        val expandedDirectionIds: Set<String> = emptySet(),
        val pinnedRouteIds: Set<String> = emptySet(),
    ) : UiState()
    data class Error(val message: String) : UiState()
}

data class BusRouteUiModel(
    val routeShortName: String,
    val routeLongName: String,
    val nearestStopDistanceMeters: Float = Float.MAX_VALUE,
    val isPinned: Boolean = false,
    val directions: List<BusDirectionUiModel>,
)

data class BusDirectionUiModel(
    val id: String, // route_destination
    val destinationName: String,
    val stopName: String,
    val stopDistanceText: String = "",
    val arrivals: List<BusArrivalDetail>,
)

data class BusArrivalDetail(
    val id: String,
    val distanceText: String,
    val arrivalClockTime: String,
    val minutesAway: String,
)

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

    private val maxRoutesToDisplay = 15
    private val maxHalfMileMeters = 804.67f // 0.5 miles
    private val timeFormatter = DateTimeFormatter.ofPattern("H:mm:ss")

    init {
        refresh()
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    private fun formatDistanceToUser(meters: Float): String {
        val miles = meters * 0.000621371f
        return if (miles < 0.1f) {
            val feet = (meters * 3.28084f).toInt()
            "$feet ft away"
        } else {
            String.format(Locale.US, "%.1f mi away", miles)
        }
    }

    @SuppressLint("MissingPermission")
    fun refresh(isPullToRefresh: Boolean = false) {
        if (!isPullToRefresh) {
            _uiState.value = UiState.Loading
        } else {
            _isRefreshing.value = true
        }
        viewModelScope.launch {
            try {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            Log.d("BusViewModel", "High accuracy location: ${location.latitude}, ${location.longitude}")
                            loadNearbyBusArrivals(location.latitude, location.longitude, isPullToRefresh)
                        } else {
                            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                                if (lastLoc != null) {
                                    Log.d("BusViewModel", "Fallback lastLocation: ${lastLoc.latitude}, ${lastLoc.longitude}")
                                    loadNearbyBusArrivals(lastLoc.latitude, lastLoc.longitude, isPullToRefresh)
                                } else {
                                    Log.e("BusViewModel", "Location null")
                                    if (!isPullToRefresh) {
                                        _uiState.value = UiState.Error("Could not determine watch location")
                                    }
                                    _isRefreshing.value = false
                                }
                            }.addOnFailureListener {
                                if (!isPullToRefresh) {
                                    _uiState.value = UiState.Error("Location error")
                                }
                                _isRefreshing.value = false
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("BusViewModel", "Location failure: ${e.message}")
                        fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                            if (lastLoc != null) {
                                loadNearbyBusArrivals(lastLoc.latitude, lastLoc.longitude, isPullToRefresh)
                            } else {
                                if (!isPullToRefresh) {
                                    _uiState.value = UiState.Error("Location error")
                                }
                                _isRefreshing.value = false
                            }
                        }.addOnFailureListener {
                            if (!isPullToRefresh) {
                                _uiState.value = UiState.Error("Location error")
                            }
                            _isRefreshing.value = false
                        }
                    }
            } catch (_: Exception) {
                Log.e("BusViewModel", "Refresh crash")
                _isRefreshing.value = false
            }
        }
    }

    private fun loadNearbyBusArrivals(lat: Double, lon: Double, isPullToRefresh: Boolean = false) {
        viewModelScope.launch {
            try {
                repository.getNearbyStops(lat, lon).collect { stops ->
                    Log.d("BusViewModel", "Raw stops fetched: ${stops.size}")

                    val stopsWithDistance = stops.map { stop ->
                        stop to calculateDistanceMeters(lat, lon, stop.lat, stop.lon)
                    }.filter { (_, dist) ->
                        dist <= maxHalfMileMeters
                    }.sortedBy { (_, dist) ->
                        dist
                    }

                    if (stopsWithDistance.isEmpty()) {
                        Log.w("BusViewModel", "No stops found within 0.5 miles")
                        if (!isPullToRefresh) _uiState.value = UiState.Error("No bus stops within 0.5 mi")
                        _isRefreshing.value = false
                        return@collect
                    }

                    val routeMetadata = stopsWithDistance.flatMap { it.first.routes }
                        .associate { it.shortName to (it.longName ?: "") }

                    val stopsToFetch = stopsWithDistance.take(20)
                    val allArrivalsFlat = coroutineScope {
                        stopsToFetch.map { (stop, dist) ->
                            async {
                                try {
                                    repository.getBusArrivals(stop.id).first().map { journey ->
                                        Quadruple(stop, dist, journey, routeMetadata[journey.lineName] ?: "")
                                    }
                                } catch (e: Exception) {
                                    Log.e("BusViewModel", "Stop ${stop.id} failed: ${e.message}")
                                    emptyList()
                                }
                            }
                        }.awaitAll().flatten()
                    }

                    val groupedByRoute = allArrivalsFlat.groupBy { it.third.lineName }
                    val currentPinned = _pinnedRouteIds.value

                    val routesList = groupedByRoute.map { (routeShortName, quadList) ->
                        val rawRouteLongName = quadList.firstOrNull { it.fourth.isNotEmpty() }?.fourth ?: ""
                        val simplifiedRouteName = if (rawRouteLongName.contains(" - ")) {
                            rawRouteLongName.substringBefore(" - ")
                        } else {
                            rawRouteLongName
                        }

                        val minDistanceForRoute = quadList.minOfOrNull { it.second } ?: Float.MAX_VALUE

                        val directions = quadList.groupBy { it.third.destinationName.ifEmpty { "Unknown" } }
                            .map { (destName, items) ->
                                val topStopItem = items.first()
                                val stopName = topStopItem.first.name
                                val stopDistMeters = topStopItem.second
                                val stopDistText = formatDistanceToUser(stopDistMeters)

                                val arrivals = items
                                    .distinctBy { it.third.monitoredCall.expectedArrivalTime }
                                    .sortedBy { it.third.monitoredCall.expectedArrivalTime }
                                    .take(5)
                                    .map { (_, _, journey, _) ->
                                        val rawTime = journey.monitoredCall.expectedArrivalTime
                                        var formattedTime = ""
                                        var minAway = ""

                                        if (rawTime != null) {
                                            try {
                                                val arrivalDT = OffsetDateTime.parse(rawTime)
                                                val localArrival = arrivalDT.atZoneSameInstant(ZoneId.systemDefault())
                                                formattedTime = localArrival.format(timeFormatter)

                                                val diffMinutes = ChronoUnit.MINUTES.between(Instant.now(), arrivalDT.toInstant())
                                                minAway = if (diffMinutes <= 0) "Arriving" else "$diffMinutes min away"
                                            } catch (e: Exception) {
                                                formattedTime = rawTime.substringAfter("T").substringBefore("-")
                                            }
                                        }

                                        val distance = journey.monitoredCall.extensions?.distances?.presentableDistance
                                        val displayDistance = if (distance.isNullOrEmpty() || distance.contains("approaching", ignoreCase = true)) {
                                            "ETA"
                                        } else {
                                            distance
                                        }

                                        BusArrivalDetail(
                                            id = "${journey.lineName}_${journey.monitoredCall.expectedArrivalTime}",
                                            distanceText = displayDistance,
                                            arrivalClockTime = formattedTime,
                                            minutesAway = minAway,
                                        )
                                    }

                                BusDirectionUiModel(
                                    id = "${routeShortName}_$destName",
                                    destinationName = destName,
                                    stopName = stopName,
                                    stopDistanceText = stopDistText,
                                    arrivals = arrivals,
                                )
                            }

                        BusRouteUiModel(
                            routeShortName = routeShortName,
                            routeLongName = simplifiedRouteName,
                            nearestStopDistanceMeters = minDistanceForRoute,
                            isPinned = currentPinned.contains(routeShortName),
                            directions = directions,
                        )
                    }.sortedWith(
                        compareByDescending<BusRouteUiModel> { it.isPinned }
                            .thenBy { it.nearestStopDistanceMeters },
                    ).take(maxRoutesToDisplay)

                    _uiState.update { currentState ->
                        if (routesList.isEmpty() && !isPullToRefresh) {
                            UiState.Error("No upcoming buses nearby (< 0.5 mi)")
                        } else {
                            if (currentState is UiState.Success) {
                                currentState.copy(routes = routesList, pinnedRouteIds = currentPinned)
                            } else if (routesList.isNotEmpty()) {
                                UiState.Success(routes = routesList, pinnedRouteIds = currentPinned)
                            } else {
                                UiState.Error("No upcoming buses nearby (< 0.5 mi)")
                            }
                        }
                    }
                    _isRefreshing.value = false
                }
            } catch (e: Exception) {
                Log.e("BusViewModel", "Mapping error: ${e.message}")
                _isRefreshing.value = false
            }
        }
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
            Log.d("BusViewModel", "Tile update info: ${e.message}")
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
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
