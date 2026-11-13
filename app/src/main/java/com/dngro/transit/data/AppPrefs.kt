package com.dngro.transit.data

import android.content.Context
import androidx.core.content.edit
import com.dngro.transit.BuildConfig
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    private const val KEY_METRIC = "use_metric"
    private const val KEY_HOUR24 = "use_24h"
    private const val KEY_PLACES = "places_v1"
    private const val KEY_ALERTS = "arrival_alerts_v1"

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

    fun clearApiKey(context: Context) {
        prefs(context).edit { remove(KEY_USER_API_KEY) }
    }

    fun displayPrefs(context: Context): DisplayPrefs {
        val p = prefs(context)
        return DisplayPrefs(
            metric = p.getBoolean(KEY_METRIC, false),
            hour24 = if (p.contains(KEY_HOUR24)) {
                p.getBoolean(KEY_HOUR24, false)
            } else {
                android.text.format.DateFormat.is24HourFormat(context)
            },
        )
    }

    fun saveDisplayPrefs(context: Context, display: DisplayPrefs) {
        prefs(context).edit {
            putBoolean(KEY_METRIC, display.metric)
            putBoolean(KEY_HOUR24, display.hour24)
        }
    }

    fun loadPlaces(context: Context): List<SavedPlace> = decodePlaces(prefs(context).getString(KEY_PLACES, null))

    fun savePlaces(context: Context, places: List<SavedPlace>) {
        prefs(context).edit { putString(KEY_PLACES, encodePlaces(places)) }
    }

    /** Pending buzz alerts as "key" to trigger time (epoch ms). The alarms themselves live in AlarmManager. */
    fun loadAlerts(context: Context): Map<String, Long> =
        decodeAlerts(prefs(context).getString(KEY_ALERTS, null))

    fun saveAlerts(context: Context, alerts: Map<String, Long>) {
        prefs(context).edit { putString(KEY_ALERTS, encodeAlerts(alerts)) }
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

/** Units and clock style. */
data class DisplayPrefs(val metric: Boolean = false, val hour24: Boolean = false)

internal fun encodeAlerts(alerts: Map<String, Long>): String = AppPrefs.json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), alerts)

internal fun decodeAlerts(raw: String?): Map<String, Long> {
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
        AppPrefs.json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), raw)
    } catch (e: Exception) {
        emptyMap()
    }
}
