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
import com.dngro.transit.data.AppPrefs
import com.dngro.transit.data.BusRepositories
import com.dngro.transit.presentation.MainActivity
import com.dngro.transit.presentation.etaCompact
import com.dngro.transit.presentation.loadFavoriteEtas
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Watch-face slot showing the next arrival of the first bus the user starred, e.g. "B41  3m".
 * Needs no location: a star names its own stop. Tapping it opens the app.
 */
class MainComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            return null
        }
        return createComplicationData("3m", "B41", "B41, next bus in 3 minutes")
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val favorite = AppPrefs.loadFavorites(applicationContext).firstOrNull()
            ?: return createComplicationData("Bus", null, "Open NYC Buses On A Glance and star a bus")

        if (AppPrefs.apiKey(applicationContext).isBlank()) {
            return createComplicationData("Key", favorite.route, "Open NYC Buses On A Glance to add an API key")
        }

        val now = Instant.now()
        val eta = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            loadFavoriteEtas(BusRepositories.forContext(applicationContext), listOf(favorite), now, 1)
                .firstOrNull()
        }
        val text = etaCompact(eta?.arrivals, now)
        val description = if (text == "–") {
            "${favorite.route}, no arrival time available"
        } else {
            "${favorite.route} to ${favorite.destination}, next bus $text"
        }
        return createComplicationData(text, favorite.route, description)
    }

    private fun createComplicationData(text: String, title: String?, contentDescription: String): ComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build(),
        )
            .setTitle(title?.let { PlainComplicationText.Builder(it).build() })
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
        private const val FETCH_TIMEOUT_MS = 8_000L

        /** Ask the watch face for fresh data now, e.g. right after the user stars a bus. */
        fun requestUpdate(context: Context) {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, MainComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
