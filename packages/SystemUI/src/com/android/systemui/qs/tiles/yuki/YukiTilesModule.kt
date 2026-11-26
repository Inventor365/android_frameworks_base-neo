/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles.yuki

import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.ChargingTile
import com.android.systemui.qs.tiles.PowerProfileTile
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * The Power profile and Yuki Charging tiles, in one module so a ROM only has to include it from
 * its own tile module (Yuki QS tiles v1).
 */
@Module
interface YukiTilesModule {

    @Binds
    @IntoMap
    @StringKey(PowerProfileTile.TILE_SPEC)
    fun bindPowerProfileTile(powerProfileTile: PowerProfileTile): QSTileImpl<*>

    @Binds
    @IntoMap
    @StringKey(ChargingTile.TILE_SPEC)
    fun bindChargingTile(chargingTile: ChargingTile): QSTileImpl<*>

    companion object {
        @Provides
        @IntoMap
        @StringKey(PowerProfileTile.TILE_SPEC)
        fun providePowerProfileTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(PowerProfileTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_yuki_profile_auto,
                        labelRes = R.string.yuki_power_title,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(ChargingTile.TILE_SPEC)
        fun provideChargingTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(ChargingTile.TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_yuki_charging_fast,
                        labelRes = R.string.yuki_charging_title,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )
    }
}
