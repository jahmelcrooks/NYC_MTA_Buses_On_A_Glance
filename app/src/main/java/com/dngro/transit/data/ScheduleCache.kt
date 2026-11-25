package com.dngro.transit.data

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Timetables by stop and service date, shared by the app and the tile (same process). A day's
 * timetable doesn't change, so it's fetched once; a failed fetch is remembered for a while so an
 * unavailable endpoint isn't retried on every refresh.
 */
object ScheduleCache {
    private const val RETRY_MS = 10 * 60 * 1000L
    private const val MAX_ENTRIES = 40

    private class Entry(val trips: List<ScheduledTrip>, val ok: Boolean, val atMillis: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Today's timetable for a stop (plus last night's before 4 a.m.); empty if unavailable. */
    suspend fun get(repository: BusRepository, stopId: String, timeoutMs: Long = 8_000L): List<ScheduledTrip> {
        val nowMillis = System.nanoTime() / 1_000_000
        return serviceDates(Instant.now()).flatMap { date ->
            val key = "$stopId|$date"
            val cached = entries[key]
            if (cached != null && (cached.ok || nowMillis - cached.atMillis < RETRY_MS)) {
                return@flatMap cached.trips
            }
            val trips = withTimeoutOrNull(timeoutMs) {
                try {
                    repository.getSchedule(stopId, date)
                } catch (e: BusApiException) {
                    Log.w("ScheduleCache", "No timetable for $stopId (${e.error})")
                    null
                }
            }
            if (entries.size > MAX_ENTRIES) entries.clear()
            entries[key] = Entry(trips.orEmpty(), ok = trips != null, atMillis = nowMillis)
            trips.orEmpty()
        }
    }
}
