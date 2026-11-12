package com.dngro.transit.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.dngro.transit.presentation.MainActivity
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.tiles.SuspendingTileService

private const val RESOURCES_VERSION = "1"

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
        val prefs = applicationContext.getSharedPreferences("nyc_buses_prefs", MODE_PRIVATE)
        val pinnedRoutes = prefs.getStringSet("pinned_routes", emptySet()) ?: emptySet()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(tileLayout(requestParams.deviceConfiguration, applicationContext, pinnedRoutes))
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .setFreshnessIntervalMillis(3 * 60 * 1000)
            .build()
    }
}

private fun tileLayout(
    deviceParameters: DeviceParameters,
    context: Context,
    pinnedRoutes: Set<String>,
): LayoutElementBuilders.LayoutElement {
    val content = Column.Builder()

    if (pinnedRoutes.isEmpty()) {
        content.addContent(
            Text.Builder(context, "No Starred Buses")
                .setTypography(Typography.TYPOGRAPHY_BODY1)
                .setColor(argb(0xFFFFD166.toInt())) // Pastel Gold
                .build(),
        )
        content.addContent(Spacer.Builder().setHeight(dp(4f)).build())
        content.addContent(
            Text.Builder(context, "Star a bus in the app to pin it here")
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .setColor(argb(0xFFBBBBBB.toInt()))
                .build(),
        )
    } else {
        content.addContent(
            Text.Builder(context, "Starred Favorites:")
                .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                .setColor(argb(0xFFAAAAAA.toInt()))
                .build(),
        )
        content.addContent(Spacer.Builder().setHeight(dp(4f)).build())

        pinnedRoutes.sorted().take(4).forEach { route ->
            content.addContent(
                Text.Builder(context, "★ $route")
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(argb(0xFFFFD166.toInt())) // Pastel Gold
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
        .setPrimaryLabelTextContent(
            Text.Builder(context, "NYC Buses")
                .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                .setColor(argb(0xFF5C9CE6.toInt())) // MTA Pastel Blue
                .build(),
        )
        .setContent(
            Column.Builder()
                .setModifiers(modifiers)
                .addContent(content.build())
                .build(),
        )
        .build()
}
