/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles.yuki;

import android.content.Context;

import com.android.systemui.res.R;

/** Words and icons for the Charging tile and panel. */
final class ChargingTileText {
    private ChargingTileText() {}

    static int modeName(String mode) {
        if (PartsContract.MODE_SLOW.equals(mode)) return R.string.yuki_charging_slow;
        if (PartsContract.MODE_SUPERFAST.equals(mode)) return R.string.yuki_charging_superfast;
        return R.string.yuki_charging_fast;
    }

    static int modeIcon(String mode) {
        if (PartsContract.MODE_SLOW.equals(mode)) return R.drawable.ic_yuki_charging_slow;
        if (PartsContract.MODE_SUPERFAST.equals(mode)) return R.drawable.ic_yuki_charging_superfast;
        return R.drawable.ic_yuki_charging_fast;
    }

    /** "68% · 27.4 W · full in about 35 min", "68% · On battery", ... */
    static String liveLine(Context c, ChargingStatus s, boolean bypass) {
        if (!s.plugged) return c.getString(R.string.yuki_charging_on_battery, s.level);
        if (bypass) return c.getString(R.string.yuki_charging_bypass_line, s.level);
        if (s.full) return c.getString(R.string.yuki_charging_full, s.level);
        if (s.usbPort) return c.getString(R.string.yuki_charging_live_usb, s.level, s.watts);
        if (s.timeToFullMs > 0) {
            return c.getString(R.string.yuki_charging_live_full_in, s.level, s.watts,
                    duration(c, s.timeToFullMs));
        }
        return c.getString(R.string.yuki_charging_live, s.level, s.watts);
    }

    /** The tile's second line: "Fast · 27 W" while charging, the mode otherwise. */
    static String tileLine(Context c, String mode, ChargingStatus s, boolean bypass) {
        if (bypass) return c.getString(R.string.yuki_bypass);
        // A USB data port caps charging far below any mode: say so instead of the mode.
        String name = c.getString(s != null && s.usbPort ? R.string.yuki_charging_usb_port
                : modeName(mode));
        if (s != null && s.charging && s.watts >= 0.5) {
            return c.getString(R.string.yuki_tile_charging_watts, name, s.watts);
        }
        return name;
    }

    private static String duration(Context c, long ms) {
        long minutes = Math.max(1, Math.round(ms / 60000.0));
        return minutes < 60 ? c.getString(R.string.yuki_minutes, minutes)
                : c.getString(R.string.yuki_hours_minutes, minutes / 60, minutes % 60);
    }
}
