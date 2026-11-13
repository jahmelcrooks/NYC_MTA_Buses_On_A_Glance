package com.dngro.transit.data

import kotlin.math.cos

private const val METERS_PER_DEGREE_LAT = 111_320.0

/** Total latitude span (degrees) of a box reaching [radiusMeters] either side of the centre. */
internal fun latSpanDegrees(radiusMeters: Double): Double =
    2 * radiusMeters / METERS_PER_DEGREE_LAT

/** Total longitude span (degrees) of that box. A degree of longitude shrinks away from the equator. */
internal fun lonSpanDegrees(radiusMeters: Double, latitude: Double): Double =
    2 * radiusMeters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude)))

/** Great-circle distance in metres (pure Kotlin, so it can be unit-tested without Android). */
internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * kotlin.math.sin(dLon / 2).let { it * it }
    return 2 * earthRadius * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
}
