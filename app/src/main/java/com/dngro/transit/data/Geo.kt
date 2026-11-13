package com.dngro.transit.data

import kotlin.math.cos

private const val METERS_PER_DEGREE_LAT = 111_320.0

/** Total latitude span (degrees) of a box reaching [radiusMeters] either side of the centre. */
internal fun latSpanDegrees(radiusMeters: Double): Double =
    2 * radiusMeters / METERS_PER_DEGREE_LAT

/** Total longitude span (degrees) of that box. A degree of longitude shrinks away from the equator. */
internal fun lonSpanDegrees(radiusMeters: Double, latitude: Double): Double =
    2 * radiusMeters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude)))
