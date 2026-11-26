/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles.yuki;

import android.content.Context;

import com.android.systemui.res.R;

/** Words and icons for the Power profile tile and panel. */
public final class PowerProfileTileText {
    private PowerProfileTileText() {}

    public static int name(int profile) {
        switch (profile) {
            case PartsContract.PROFILE_BALANCED: return R.string.yuki_profile_balanced;
            case PartsContract.PROFILE_BATTERY: return R.string.yuki_profile_battery;
            case PartsContract.PROFILE_PERFORMANCE: return R.string.yuki_profile_performance;
            case PartsContract.PROFILE_GAMING: return R.string.yuki_profile_gaming;
            case PartsContract.PROFILE_AUTO:
            default: return R.string.yuki_profile_auto;
        }
    }

    public static int icon(int profile) {
        switch (profile) {
            case PartsContract.PROFILE_BALANCED: return R.drawable.ic_yuki_profile_balanced;
            case PartsContract.PROFILE_BATTERY: return R.drawable.ic_yuki_profile_battery;
            case PartsContract.PROFILE_PERFORMANCE: return R.drawable.ic_yuki_profile_performance;
            case PartsContract.PROFILE_GAMING: return R.drawable.ic_yuki_profile_gaming;
            case PartsContract.PROFILE_AUTO:
            default: return R.drawable.ic_yuki_profile_auto;
        }
    }

    /** What Auto is doing, as Parts publishes it; 0 when it says nothing useful. */
    public static int autoState(String state) {
        if (state == null) return 0;
        switch (state) {
            case "benchmark": return R.string.yuki_auto_benchmark;
            case "heavy_mgame": return R.string.yuki_auto_heavy_mgame;
            case "esports_tgame": return R.string.yuki_auto_esports_tgame;
            case "extreme_open": return R.string.yuki_auto_extreme_open;
            case "casual_game": return R.string.yuki_auto_casual_game;
            case "highfps": return R.string.yuki_auto_highfps;
            case "heavy_compute": return R.string.yuki_auto_heavy_compute;
            case "video": return R.string.yuki_auto_video;
            case "videochat": return R.string.yuki_auto_videochat;
            case "navigation": return R.string.yuki_auto_navigation;
            case "huanji": return R.string.yuki_auto_huanji;
            case "lightui": return R.string.yuki_auto_lightui;
            case "normal": return R.string.yuki_auto_normal;
            case "idle": return R.string.yuki_auto_idle;
            case "idle_audio": return R.string.yuki_auto_idle_audio;
            default: return 0;
        }
    }

    /**
     * "Auto · tuning for video playback" (panel) / "Auto · video playback" (tile), or the
     * profile's name.
     */
    public static String subtitle(Context context, int profile, String autoState, boolean panel) {
        int state = profile == PartsContract.PROFILE_AUTO ? autoState(autoState) : 0;
        if (state == 0) return context.getString(name(profile));
        return context.getString(panel ? R.string.yuki_power_auto_tuning
                : R.string.yuki_power_auto_short, context.getString(state));
    }
}
