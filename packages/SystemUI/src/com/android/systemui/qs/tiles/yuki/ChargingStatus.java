/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles.yuki;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import androidx.annotation.WorkerThread;

/** One reading of the battery for the Charging tile and panel (binder calls: not on main). */
final class ChargingStatus {
    final int level;
    final boolean plugged;
    final boolean charging;
    final boolean full;
    /** A USB data port (computer or charging hub): 5 V at a few watts at most. */
    final boolean usbPort;
    /** Power going into the battery, 0 unless charging. */
    final double watts;
    /** ms, or -1 when unknown. */
    final long timeToFullMs;

    private ChargingStatus(int level, boolean plugged, boolean charging, boolean full,
            boolean usbPort, double watts, long timeToFullMs) {
        this.level = level;
        this.plugged = plugged;
        this.charging = charging;
        this.full = full;
        this.usbPort = usbPort;
        this.watts = watts;
        this.timeToFullMs = timeToFullMs;
    }

    @WorkerThread
    static ChargingStatus read(Context context) {
        Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) {
            return new ChargingStatus(0, false, false, false, false, 0, -1);
        }
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int raw = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int level = scale > 0 ? Math.round(raw * 100f / scale) : raw;
        int pluggedType = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean plugged = pluggedType > 0;
        boolean usbPort = pluggedType == BatteryManager.BATTERY_PLUGGED_USB;
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = plugged && status == BatteryManager.BATTERY_STATUS_CHARGING;
        boolean full = plugged && status == BatteryManager.BATTERY_STATUS_FULL;
        double watts = 0;
        long toFull = -1;
        BatteryManager bm = context.getSystemService(BatteryManager.class);
        if (charging && bm != null) {
            // Some kernels report charging current as negative: the magnitude is what matters.
            int current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            int milliVolts = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            if (current != Integer.MIN_VALUE) {
                watts = Math.abs((long) current) / 1e6 * milliVolts / 1e3;
            }
            toFull = bm.computeChargeTimeRemaining();
        }
        return new ChargingStatus(level, plugged, charging, full, usbPort, watts, toFull);
    }
}
