package com.dngro.transit.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
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
import com.dngro.transit.data.ScheduleCache
import com.dngro.transit.presentation.Crowding
import com.dngro.transit.presentation.FavoriteStatus
import com.dngro.transit.presentation.MainActivity
import com.dngro.transit.presentation.TileCard
import com.dngro.transit.presentation.loadFavoriteStatuses
import com.dngro.transit.presentation.tileCard
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

private const val RESOURCES_VERSION = "1"

/** The tile has a short time budget; give up on the network well before it runs out. */
private const val FETCH_TIMEOUT_MS = 8_000L
private const val SCHEDULE_TIMEOUT_MS = 5_000L
private const val FRESHNESS_MS = 60_000L

private val GOLD = argb(0xFFFFD166.toInt())
private val BLUE = argb(0xFF5C9CE6.toInt())
private val GREEN = argb(0xFF70C1B3.toInt())
private val RED = argb(0xFFFF8A80.toInt())
private val LIGHT_GRAY = argb(0xFFBBBBBB.toInt())
private val DIM_GRAY = argb(0xFF777777.toInt())
private val WHITE = argb(0xFFFFFFFF.toInt())
private val CARD = argb(0xFF222327.toInt())

/**
 * Starred buses, each on its own card like the app's "Next bus" card: route, next times with a
 * live (green) or scheduled (grey) dot, destination, and crowding / stops away / alerts.
 * Needs no location: a star names its own stop.
 */
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
        val device = requestParams.deviceConfiguration
        val favorites = AppPrefs.loadFavorites(context)
        val hasKey = AppPrefs.apiKey(context).isNotBlank()
        // Bigger screens fit three cards; on smaller ones two, so each still has room for details.
        val maxCards = if (device.screenHeightDp >= 225) 3 else 2

        val statuses: List<FavoriteStatus> = if (favorites.isEmpty() || !hasKey) {
            emptyList()
        } else {
            val repository = BusRepositories.forContext(context)
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                loadFavoriteStatuses(repository, favorites, maxCards) { stopId ->
                    ScheduleCache.get(repository, stopId, SCHEDULE_TIMEOUT_MS)
                }
            } ?: favorites.take(maxCards).map { FavoriteStatus(it, null) }
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
                                            deviceParameters = device,
                                            context = context,
                                            hasFavorites = favorites.isNotEmpty(),
                                            hasKey = hasKey,
                                            cards = statuses.map { tileCard(it, now) },
                                            showDetails = maxCards <= 2 || statuses.size <= 2,
                                            hiddenCount = (favorites.size - statuses.size).coerceAtLeast(0),
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
    cards: List<TileCard>,
    showDetails: Boolean,
    hiddenCount: Int,
): LayoutElementBuilders.LayoutElement {
    val content = Column.Builder()
        .setWidth(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

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
        else -> {
            cards.forEachIndexed { index, card ->
                if (index > 0) content.addContent(Spacer.Builder().setHeight(dp(4f)).build())
                content.addContent(busCard(context, card, showDetails))
            }
            if (hiddenCount > 0) {
                content.addContent(Spacer.Builder().setHeight(dp(2f)).build())
                content.addContent(label(context, "+$hiddenCount more in the app", Typography.TYPOGRAPHY_CAPTION3, LIGHT_GRAY))
            }
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
        .setPrimaryLabelTextContent(label(context, "Starred Buses", Typography.TYPOGRAPHY_CAPTION1, BLUE))
        .setContent(
            Column.Builder()
                .setWidth(expand())
                .setModifiers(modifiers)
                .addContent(content.build())
                .build(),
        )
        .build()
}

/** One starred bus on a rounded card, like the app's "Next bus" card. */
private fun busCard(context: Context, card: TileCard, showDetails: Boolean): LayoutElementBuilders.LayoutElement {
    val timesColor = if (card.live) WHITE else LIGHT_GRAY
    val header = Row.Builder()
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .addContent(dot(if (card.live) GREEN else DIM_GRAY))
        .addContent(Spacer.Builder().setWidth(dp(5f)).build())
        .addContent(label(context, card.route, Typography.TYPOGRAPHY_TITLE3, GOLD))
        .addContent(Spacer.Builder().setWidth(dp(6f)).build())
        .addContent(label(context, card.times, Typography.TYPOGRAPHY_BODY2, timesColor))
        .build()

    val body = Column.Builder()
        .setWidth(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
        .addContent(header)
        .addContent(label(context, card.destination, Typography.TYPOGRAPHY_CAPTION2, LIGHT_GRAY))
    if (showDetails && card.detail.isNotEmpty()) {
        val color = when {
            card.hasAlert -> GOLD
            card.crowding == Crowding.FULL -> RED
            card.crowding == Crowding.STANDING -> GOLD
            card.crowding == Crowding.SEATS -> GREEN
            else -> LIGHT_GRAY
        }
        body.addContent(label(context, card.detail, Typography.TYPOGRAPHY_CAPTION3, color))
    }

    return LayoutElementBuilders.Box.Builder()
        .setWidth(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setBackground(
                    ModifiersBuilders.Background.Builder()
                        .setColor(CARD)
                        .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(14f)).build())
                        .build(),
                )
                .setPadding(
                    ModifiersBuilders.Padding.Builder()
                        .setStart(dp(10f))
                        .setEnd(dp(10f))
                        .setTop(dp(4f))
                        .setBottom(dp(5f))
                        .build(),
                )
                .build(),
        )
        .addContent(body.build())
        .build()
}

/** Live (green) / scheduled (grey) marker. */
private fun dot(color: ColorBuilders.ColorProp): LayoutElementBuilders.LayoutElement =
    LayoutElementBuilders.Box.Builder()
        .setWidth(dp(6f))
        .setHeight(dp(6f))
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setBackground(
                    ModifiersBuilders.Background.Builder()
                        .setColor(color)
                        .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(3f)).build())
                        .build(),
                )
                .build(),
        )
        .build()

private fun label(context: Context, text: String, typography: Int, color: ColorBuilders.ColorProp): Text =
    Text.Builder(context, text)
        .setTypography(typography)
        .setColor(color)
        .setMaxLines(1)
        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
        .build()
