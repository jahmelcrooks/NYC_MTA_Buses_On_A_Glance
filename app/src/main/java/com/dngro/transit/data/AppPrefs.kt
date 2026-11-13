package com.dngro.transit.data

import android.content.Context
import androidx.core.content.edit
import com.dngro.transit.BuildConfig
import kotlinx.serialization.json.Json

/**
 * The one place that knows the SharedPreferences file and its keys. The app, the tile and the
 * complication all read the same data, so they must not each spell the names out themselves.
 */
object AppPrefs {
    private const val NAME = "nyc_buses_prefs"
    private const val KEY_USER_API_KEY = "user_api_key"
    private const val KEY_FAVORITES = "favorites_v1"
    private const val KEY_SNAPSHOT = "snapshot_v1"
    private const val KEY_RADIUS_MILES = "radius_miles"

    internal val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** A key the user entered on the watch wins over the one compiled into the build. */
    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_USER_API_KEY, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.MTA_API_KEY

    fun saveApiKey(context: Context, rawKey: String) {
        prefs(context).edit { putString(KEY_USER_API_KEY, cleanApiKey(rawKey)) }
    }

    fun hasUserApiKey(context: Context): Boolean =
        !prefs(context).getString(KEY_USER_API_KEY, null).isNullOrBlank()

    fun searchRadiusMiles(context: Context): Double =
        normalizeRadiusMiles(
            if (prefs(context).contains(KEY_RADIUS_MILES)) {
                prefs(context).getFloat(KEY_RADIUS_MILES, DEFAULT_RADIUS_MILES.toFloat()).toDouble()
            } else {
                null
            },
        )

    fun saveSearchRadiusMiles(context: Context, miles: Double) {
        prefs(context).edit { putFloat(KEY_RADIUS_MILES, normalizeRadiusMiles(miles).toFloat()) }
    }

    fun loadFavorites(context: Context): List<Favorite> =
        decodeFavorites(prefs(context).getString(KEY_FAVORITES, null))

    fun saveFavorites(context: Context, favorites: List<Favorite>) {
        prefs(context).edit { putString(KEY_FAVORITES, encodeFavorites(favorites)) }
    }

    fun snapshotJson(context: Context): String? = prefs(context).getString(KEY_SNAPSHOT, null)

    fun saveSnapshotJson(context: Context, raw: String) {
        prefs(context).edit { putString(KEY_SNAPSHOT, raw) }
    }
}

/** Removes the whitespace and stray quotes that come from pasting or typing a key on a watch. */
internal fun cleanApiKey(raw: String): String =
    raw.filterNot { it.isWhitespace() }.trim('"', '\'')
