package com.dngro.transit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRadiusTest {
    @Test
    fun normalize_snapsToAllowedOptions_andDefaultsWhenMissing() {
        assertEquals(0.5, normalizeRadiusMiles(null), 0.0)
        assertEquals(0.5, normalizeRadiusMiles(Double.NaN), 0.0)
        assertEquals(0.75, normalizeRadiusMiles(0.75), 0.0)
        assertEquals(0.25, normalizeRadiusMiles(0.1), 0.0)
        assertEquals(1.0, normalizeRadiusMiles(5.0), 0.0)
        assertEquals(0.5, normalizeRadiusMiles(0.45), 0.0)
    }

    @Test
    fun meters_andLabels() {
        assertEquals(402.3f, radiusMeters(0.25), 0.1f)
        assertEquals(1609.3f, radiusMeters(1.0), 0.1f)
        assertEquals(listOf("0.25 mi", "0.5 mi", "0.75 mi", "1 mi"), RADIUS_OPTIONS_MILES.map { radiusLabel(it) })
    }
}
