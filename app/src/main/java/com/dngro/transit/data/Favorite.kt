package com.dngro.transit.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A starred "this route, going this way, from this stop". Because it names a stop, the tile and
 * the complication can fetch its arrivals directly, with no location needed.
 */
@Serializable
data class Favorite(
    val stopId: String,
    val stopName: String,
    val route: String,
    val destination: String,
) {
    val key: String get() = favoriteKey(stopId, route, destination)
}

fun favoriteKey(stopId: String, route: String, destination: String): String =
    "$stopId|$route|$destination"

/** Adds [favorite] if it isn't starred yet, otherwise removes it. Keeps the order stars were added. */
internal fun withFavoriteToggled(current: List<Favorite>, favorite: Favorite): List<Favorite> =
    if (current.any { it.key == favorite.key }) {
        current.filterNot { it.key == favorite.key }
    } else {
        current + favorite
    }

internal fun encodeFavorites(favorites: List<Favorite>): String =
    AppPrefs.json.encodeToString(ListSerializer(Favorite.serializer()), favorites)

/** Never throws: unreadable or missing data just means "no favorites". */
internal fun decodeFavorites(raw: String?): List<Favorite> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        AppPrefs.json.decodeFromString(ListSerializer(Favorite.serializer()), raw)
            .filter { it.stopId.isNotBlank() && it.route.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }
}
