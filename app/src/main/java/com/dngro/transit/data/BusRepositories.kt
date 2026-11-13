package com.dngro.transit.data

import android.content.Context

/**
 * Shares one [BusRepository] (and its HTTP client) between the app, tile and complication,
 * and rebuilds it if the user enters a different API key.
 */
object BusRepositories {
    @Volatile
    private var cached: Pair<String, BusRepository>? = null

    fun forContext(context: Context): BusRepository {
        val key = AppPrefs.apiKey(context)
        cached?.takeIf { it.first == key }?.let { return it.second }
        return synchronized(this) {
            cached?.takeIf { it.first == key }?.second
                ?: BusRepository(key).also { cached = key to it }
        }
    }
}
