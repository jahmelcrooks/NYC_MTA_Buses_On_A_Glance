package com.dngro.transit.presentation

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

sealed class UiState {
    object Loading : UiState()

    data class Success(
        val routes: List<BusRouteUiModel>,
        val expandedRouteIds: Set<String> = emptySet(),
        /** Keys (see favoriteKey) of the starred route/direction/stop combinations. */
        val favoriteKeys: Set<String> = emptySet(),
        /** True when the latest refresh failed and [routes] are the previous results. */
        val stale: Boolean = false,
        /** True while [routes] are the saved results from the last session, before a refresh lands. */
        val fromCache: Boolean = false,
    ) : UiState()

    /** [needsApiKey]: the fix is to enter a (new) MTA API key, so the screen offers that. */
    data class Error(val message: String, val needsApiKey: Boolean = false) : UiState()
}

/** The "all routes at this stop" screen, loaded live for the one stop. */
sealed class StopViewState {
    abstract val stopName: String

    data class Loading(override val stopName: String) : StopViewState()
    data class Success(
        val stopId: String,
        override val stopName: String,
        val routes: List<BusRouteUiModel>,
    ) : StopViewState()
    data class Error(override val stopName: String, val message: String) : StopViewState()
}

// The models below are @Serializable so the last results can be saved for an instant next launch.

@Serializable
data class BusRouteUiModel(
    val routeShortName: String,
    val routeLongName: String,
    val nearestStopDistanceMeters: Float = Float.MAX_VALUE,
    /** True if any direction of this route is starred. */
    val isPinned: Boolean = false,
    /** Detours/delays that mention this route (at most a couple, already trimmed). */
    val alerts: List<String> = emptyList(),
    val directions: List<BusDirectionUiModel>,
)

@Serializable
data class BusDirectionUiModel(
    val id: String, // route_destination
    val destinationName: String,
    val stopId: String = "",
    val stopName: String,
    val stopDistanceMeters: Float = Float.MAX_VALUE,
    val stopDistanceText: String = "",
    val isPinned: Boolean = false,
    val arrivals: List<BusArrivalDetail>,
)

@Serializable
data class BusArrivalDetail(
    val id: String,
    val distanceText: String,
    /**
     * When the bus is predicted to arrive, or null if MTA isn't reporting a prediction.
     * The "N min" label is derived from this at draw time so it never goes stale.
     */
    @Serializable(with = InstantEpochMillisSerializer::class)
    val expected: Instant?,
)

object InstantEpochMillisSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("InstantEpochMillis", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilli())

    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
}
