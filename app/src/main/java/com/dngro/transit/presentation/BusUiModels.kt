package com.dngro.transit.presentation

import kotlinx.serialization.Serializable

/**
 * A clean UI-ready representation of a bus arrival.
 * Used for displaying in the main list, tiles, and complications.
 */
@Serializable
data class BusUiModel(
    val route: String,
    val destination: String,
    val statusText: String,
    val stopsAway: Int
)

/**
 * A wrapper for a list of bus arrivals, making it easier to serialize
 * and pass between different Wear OS components.
 */
@Serializable
data class WearBusList(
    val buses: List<BusUiModel>
)
