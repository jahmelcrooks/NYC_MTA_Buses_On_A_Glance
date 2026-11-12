package com.dngro.transit.presentation

import java.time.Instant

sealed class UiState {
    object Loading : UiState()

    data class Success(
        val routes: List<BusRouteUiModel>,
        val expandedRouteIds: Set<String> = emptySet(),
        val expandedDirectionIds: Set<String> = emptySet(),
        val pinnedRouteIds: Set<String> = emptySet(),
        /** True when the latest refresh failed and [routes] are the previous results. */
        val stale: Boolean = false,
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
    val stopDistanceMeters: Float = Float.MAX_VALUE,
    val stopDistanceText: String = "",
    val arrivals: List<BusArrivalDetail>,
)

data class BusArrivalDetail(
    val id: String,
    val distanceText: String,
    /**
     * When the bus is predicted to arrive, or null if MTA isn't reporting a prediction.
     * The "N min" label is derived from this at draw time so it never goes stale.
     */
    val expected: Instant?,
)
