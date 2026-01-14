package com.runvision.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.runvision.wear.MainActivity
import com.runvision.wear.R

/**
 * RunVision Launcher Tile
 *
 * Simple tile that launches the app when tapped.
 * Shows app icon and "TAP TO START" text.
 */
class RunVisionTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val ID_IC_RUNNER = "ic_runner"
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(createTileLayout())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(
                ID_IC_RUNNER,
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder()
                            .setResourceId(R.drawable.ic_runner)
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(resources)
    }

    private fun createTileLayout(): LayoutElementBuilders.LayoutElement {
        // Launch MainActivity when tile is tapped
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .setId("launch_app")
            .build()

        // Main container - full tile clickable
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF1A1A1A.toInt()))  // Dark background
                            .build()
                    )
                    .build()
            )
            .addContent(
                // Vertical layout: Icon + Text
                LayoutElementBuilders.Column.Builder()
                    .setWidth(expand())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setPadding(
                                ModifiersBuilders.Padding.Builder()
                                    .setAll(dp(24f))
                                    .build()
                            )
                            .build()
                    )
                    // Runner icon
                    .addContent(
                        LayoutElementBuilders.Image.Builder()
                            .setResourceId(ID_IC_RUNNER)
                            .setWidth(dp(48f))
                            .setHeight(dp(48f))
                            .setColorFilter(
                                LayoutElementBuilders.ColorFilter.Builder()
                                    .setTint(argb(0xFF4CAF50.toInt()))  // Green tint
                                    .build()
                            )
                            .build()
                    )
                    // Spacer between icon and text
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder()
                            .setHeight(dp(12f))
                            .build()
                    )
                    // "RunVision" title
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText("RunVision")
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(18f))
                                    .setColor(argb(0xFFFFFFFF.toInt()))
                                    .build()
                            )
                            .build()
                    )
                    // Spacer
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder()
                            .setHeight(dp(4f))
                            .build()
                    )
                    // "TAP TO START" subtitle
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText("TAP TO START")
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(12f))
                                    .setColor(argb(0xFF888888.toInt()))  // Gray
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
