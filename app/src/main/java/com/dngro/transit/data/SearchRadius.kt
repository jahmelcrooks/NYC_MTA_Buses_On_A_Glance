package com.dngro.transit.data

/** The search radii the user can pick in Settings, in miles. */
val RADIUS_OPTIONS_MILES: List<Double> = listOf(0.25, 0.5, 0.75, 1.0)

const val DEFAULT_RADIUS_MILES = 0.5

private const val METERS_PER_MILE = 1609.344

/** Snaps any saved or unknown value to the nearest allowed radius (the default if invalid). */
internal fun normalizeRadiusMiles(raw: Double?): Double {
    if (raw == null || raw.isNaN()) return DEFAULT_RADIUS_MILES
    return RADIUS_OPTIONS_MILES.minByOrNull { kotlin.math.abs(it - raw) } ?: DEFAULT_RADIUS_MILES
}

internal fun radiusMeters(miles: Double): Float = (miles * METERS_PER_MILE).toFloat()

/** "0.25 mi", "0.5 mi", "0.75 mi", "1 mi". */
fun radiusLabel(miles: Double): String =
    if (miles == miles.toLong().toDouble()) "${miles.toLong()} mi" else "$miles mi"
