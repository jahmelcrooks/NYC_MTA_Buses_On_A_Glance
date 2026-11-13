package com.dngro.transit.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.dngro.transit.data.AppPrefs
import com.dngro.transit.data.BusRepositories
import com.dngro.transit.presentation.FavoriteEta
import com.dngro.transit.presentation.MainActivity
import com.dngro.transit.presentation.etaSummary
import com.dngro.transit.presentation.loadFavoriteEtas
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

private const val RESOURCES_VERSION = "1"

/** The tile has a short time budget; give up on the network well before it runs out. */
private const val FETCH_TIMEOUT_MS = 8_000L
private const val MAX_TILE_FAVORITES = 3
private const val FRESHNESS_MS = 60_000L

private val GOLD = argb(0xFFFFD166.toInt())
private val BLUE = argb(0xFF5C9CE6.toInt())
private val LIGHT_GRAY = argb(0xFFBBBBBB.toInt())
private val WHITE = argb(0xFFFFFFFF.toInt())

/** Live arrivals for the user's starred buses. Needs no location: a star names its own stop. */
@OptIn(ExperimentalHorologistApi::class)
class MainTileService : SuspendingTileService() {

    override suspend fun resourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ) = ResourceBuilders.Resources.Builder()
        .setVersion(RESOURCES_VERSION)
        .build()

    override suspend fun tileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): TileBuilders.Tile {
        val context = applicationContext
        val favorites = AppPrefs.loadFavorites(context)
        val hasKey = AppPrefs.apiKey(context).isNotBlank()
        val etas: List<FavoriteEta> = if (favorites.isEmpty() || !hasKey) {
            emptyList()
        } else {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                loadFavoriteEtas(BusRepositories.forContext(context), favorites, Instant.now(), MAX_TILE_FAVORITES)
            } ?: favorites.take(MAX_TILE_FAVORITES).map { FavoriteEta(it, null) }
        }
        // Taken after the fetch (which can take seconds) so the minutes shown are current.
        val now = Instant.now()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(
                                        tileLayout(
                                            deviceParameters = requestParams.deviceConfiguration,
                                            context = context,
                                            hasFavorites = favorites.isNotEmpty(),
                                            hasKey = hasKey,
                                            etas = etas,
                                            now = now,
                                        ),
                                    )
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .build()
    }
}

private fun tileLayout(
    deviceParameters: DeviceParameters,
    context: Context,
    hasFavorites: Boolean,
    hasKey: Boolean,
    etas: List<FavoriteEta>,
    now: Instant,
): LayoutElementBuilders.LayoutElement {
    val content = Column.Builder()

    when {
        !hasKey -> {
            content.addContent(label(context, "API key needed", Typography.TYPOGRAPHY_BODY1, GOLD))
            content.addContent(Spacer.Builder().setHeight(dp(4f)).build())
            content.addContent(label(context, "Open the app to add it", Typography.TYPOGRAPHY_CAPTION2, LIGHT_GRAY))
        }
        !hasFavorites -> {
            content.addContent(label(context, "No Starred Buses", Typography.TYPOGRAPHY_BODY1, GOLD))
            content.addContent(Spacer.Builder().setHeight(dp(4f)).build())
            content.addContent(label(context, "Star a bus in the app to see it here", Typography.TYPOGRAPHY_CAPTION2, LIGHT_GRAY))
        }
        else -> etas.forEach { eta ->
            content.addContent(
                Row.Builder()
                    .addContent(label(context, eta.favorite.route, Typography.TYPOGRAPHY_TITLE3, GOLD))
                    .addContent(Spacer.Builder().setWidth(dp(8f)).build())
                    .addContent(label(context, etaSummary(eta.arrivals, now), Typography.TYPOGRAPHY_BODY1, WHITE))
                    .build(),
            )
            content.addContent(Spacer.Builder().setHeight(dp(2f)).build())
        }
    }

    val modifiers = ModifiersBuilders.Modifiers.Builder()
        .setClickable(
            ModifiersBuilders.Clickable.Builder()
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(context.packageName)
                                .setClassName(MainActivity::class.java.name)
                                .build(),
                        )
                        .build(),
                )
                .setId("tile_click")
                .build(),
        )
        .build()

    return PrimaryLayout.Builder(deviceParameters)
        .setResponsiveContentInsetEnabled(true)
        .setPrimaryLabelTextContent(label(context, "NYC Buses", Typography.TYPOGRAPHY_CAPTION1, BLUE))
        .setContent(
            Column.Builder()
                .setModifiers(modifiers)
                .addContent(content.build())
                .build(),
        )
        .build()
}

private fun label(context: Context, text: String, typography: Int, color: androidx.wear.protolayout.ColorBuilders.ColorProp): Text =
    Text.Builder(context, text)
        .setTypography(typography)
        .setColor(color)
        .build()
