/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles.yuki;

import android.content.Context;

import androidx.annotation.WorkerThread;

import com.android.systemui.res.R;

/** What the Charging tile shows; read on the tile's background looper. */
public final class ChargingTileState {
    public final String mode;
    public final String secondaryLabel;
    /** Plugged in: charging, bypassing or full. */
    public final boolean active;
    public final int iconRes;
    /** Played once when the mode changes to Superfast (the bolt fills in), else 0. */
    public final int animatedIconRes;

    private ChargingTileState(String mode, String secondaryLabel, boolean active, int iconRes,
            int animatedIconRes) {
        this.mode = mode;
        this.secondaryLabel = secondaryLabel;
        this.active = active;
        this.iconRes = iconRes;
        this.animatedIconRes = animatedIconRes;
    }

    @WorkerThread
    public static ChargingTileState read(Context context, String lastMode) {
        String mode = PartsContract.getChargingMode(context);
        ChargingStatus status = ChargingStatus.read(context);
        boolean bypass = PartsContract.BYPASS_ON.equals(PartsContract.getBypassState(context));
        boolean superfast = PartsContract.MODE_SUPERFAST.equals(mode);
        int icon = superfast ? R.drawable.ic_yuki_charging_superfast_filled
                : ChargingTileText.modeIcon(mode);
        int animated = superfast && lastMode != null && !lastMode.equals(mode)
                ? R.drawable.ic_yuki_charging_superfast_anim : 0;
        return new ChargingTileState(mode, ChargingTileText.tileLine(context, mode, status, bypass),
                status.plugged, icon, animated);
    }
}
