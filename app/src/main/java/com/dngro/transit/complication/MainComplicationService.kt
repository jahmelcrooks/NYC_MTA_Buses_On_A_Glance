package com.dngro.transit.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Complication data source that displays the user's pinned favorite bus route on the watch face.
 */
class MainComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            return null
        }
        return createComplicationData("B41", "Pinned Bus Route B41")
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val prefs = applicationContext.getSharedPreferences("nyc_buses_prefs", MODE_PRIVATE)
        val pinnedRoutes = prefs.getStringSet("pinned_routes", emptySet()) ?: emptySet()

        val topRoute = pinnedRoutes.firstOrNull() ?: "MTA"
        val contentDesc = if (pinnedRoutes.isNotEmpty()) "Pinned Bus Route $topRoute" else "NYC Bus Transit"

        return createComplicationData(topRoute, contentDesc)
    }

    private fun createComplicationData(text: String, contentDescription: String): ComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build(),
        ).build()
}
