package com.dngro.transit.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.dngro.transit.presentation.MainActivity

/**
 * Shows the user's first starred bus route on the watch face. Tapping it opens the app.
 *
 * It shows the route name only, not arrival times: reading the user's location and calling
 * the MTA API from a background service is a bigger change (it needs background location).
 */
class MainComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            return null
        }
        return createComplicationData("B41", "Starred bus route B41")
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // A Set has no order, so sort it: the same route is shown every time (the tile does too).
        val route = prefs.getStringSet(PINNED_ROUTES_KEY, emptySet()).orEmpty().sorted().firstOrNull()

        return if (route != null) {
            createComplicationData(route, "Starred bus route $route")
        } else {
            createComplicationData("Bus", "Open NYC Buses On A Glance")
        }
    }

    private fun createComplicationData(text: String, contentDescription: String): ComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build(),
        )
            .setTapAction(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val PREFS_NAME = "nyc_buses_prefs"
        private const val PINNED_ROUTES_KEY = "pinned_routes"

        /**
         * The manifest sets UPDATE_PERIOD_SECONDS=0 (no automatic refresh), so call this
         * whenever the starred routes change.
         */
        fun requestUpdate(context: Context) {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, MainComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
