package com.dngro.transit.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The stops found around a spot you've used the app at (home, work...). Stops rarely change, so
 * reusing them skips one network request and makes a launch there faster.
 */
@Serializable
data class SavedPlace(
    val lat: Double,
    val lon: Double,
    val miles: Double,
    val savedAtEpochMillis: Long,
    val stops: List<MtaStop>,
)

internal const val MAX_SAVED_PLACES = 5
internal const val PLACE_MATCH_METERS = 150.0
internal const val PLACE_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

/** A saved place at (about) this spot with the same radius and not too old, or null. */
internal fun findPlace(
    places: List<SavedPlace>,
    lat: Double,
    lon: Double,
    miles: Double,
    nowMillis: Long,
    maxAgeMs: Long = PLACE_MAX_AGE_MS,
    matchMeters: Double = PLACE_MATCH_METERS,
): SavedPlace? =
    places
        .filter { it.miles == miles && it.stops.isNotEmpty() }
        .filter { nowMillis - it.savedAtEpochMillis in 0..maxAgeMs }
        .map { it to haversineMeters(lat, lon, it.lat, it.lon) }
        .filter { it.second <= matchMeters }
        .minByOrNull { it.second }
        ?.first

/** Adds [place], replacing any entry for the same spot and radius, keeping the newest few. */
internal fun withPlaceSaved(places: List<SavedPlace>, place: SavedPlace, max: Int = MAX_SAVED_PLACES): List<SavedPlace> =
    (places.filterNot {
        it.miles == place.miles && haversineMeters(it.lat, it.lon, place.lat, place.lon) <= PLACE_MATCH_METERS
    } + place)
        .sortedByDescending { it.savedAtEpochMillis }
        .take(max)

internal fun encodePlaces(places: List<SavedPlace>): String = AppPrefs.json.encodeToString(ListSerializer(SavedPlace.serializer()), places)

/** Never throws: garbage or an older format simply means "nothing saved". */
internal fun decodePlaces(raw: String?): List<SavedPlace> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        AppPrefs.json.decodeFromString(ListSerializer(SavedPlace.serializer()), raw)
    } catch (e: Exception) {
        emptyList()
    }
}
