package com.dngro.transit.presentation

import com.dngro.transit.data.AppPrefs
import kotlinx.serialization.Serializable

/** The last results shown, saved so the next launch has something to show instantly. */
@Serializable
internal data class Snapshot(
    val savedAtEpochMillis: Long,
    val routes: List<BusRouteUiModel>,
)

internal fun encodeSnapshot(snapshot: Snapshot): String =
    AppPrefs.json.encodeToString(Snapshot.serializer(), snapshot)

/** Never throws: anything unreadable (or from an older app version) is simply "no snapshot". */
internal fun decodeSnapshot(raw: String?): Snapshot? {
    if (raw.isNullOrBlank()) return null
    return try {
        AppPrefs.json.decodeFromString(Snapshot.serializer(), raw)
    } catch (e: Exception) {
        null
    }
}
