package com.android.systemui.qs.pipeline.data.repository

import android.content.ComponentName
import com.android.systemui.qs.pipeline.shared.TileSpec

object TilesSettingConverter {

    const val DELIMITER = ","

    /**
     * Tiles that moved from an app's TileService into SystemUI. A stored list (or a restore) that
     * still names the old custom tile gets the native one in its place, so layouts keep working;
     * the setting is rewritten the next time the list is stored.
     */
    private val MIGRATED_CUSTOM_TILES =
        mapOf(
            // Power profile and Charging: the device's Parts app (org.lineageos.settings) kept
            // its TileServices until SystemUI got native tiles with panels.
            ComponentName(
                "org.lineageos.settings",
                "org.lineageos.settings.power.PowerProfileTileService",
            ) to "power_profile",
            ComponentName(
                "org.lineageos.settings",
                "org.lineageos.settings.charge.ChargingControlTileService",
            ) to "charging_control",
        )

    private fun migrate(spec: TileSpec): TileSpec =
        if (spec is TileSpec.CustomTileSpec) {
            MIGRATED_CUSTOM_TILES[spec.componentName]?.let(TileSpec::create) ?: spec
        } else {
            spec
        }

    fun toTilesList(commaSeparatedTiles: String) =
        commaSeparatedTiles
            .split(DELIMITER)
            .map(TileSpec::create)
            .map(::migrate)
            .filter { it != TileSpec.Invalid }
            .distinct()

    fun toTilesSet(commaSeparatedTiles: String) =
        commaSeparatedTiles
            .split(DELIMITER)
            .map(TileSpec::create)
            .map(::migrate)
            .filter { it != TileSpec.Invalid }
            .toSet()
}
