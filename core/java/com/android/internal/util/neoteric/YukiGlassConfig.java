/*
 * SPDX-FileCopyrightText: 2026 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.neoteric;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;

/**
 * Shared contract for Yuki's Addons system-wide Liquid Glass, Frosted Blur, and Blur settings.
 *
 * <p>Settings are stored in {@link Settings.Secure} (written by PLEnhanced "Yuki's Addons") and
 * mirrored live to {@code persist.sys.yuki.glass.*} by {@code WindowManagerService} so
 * SurfaceFlinger RenderEngine, SystemUI, Settings, and Pixel Launcher read them lock-free without
 * requiring a reboot.
 *
 * @hide
 */
public final class YukiGlassConfig {

    // Blur / Glass visual modes
    public static final int MODE_FOLLOW_GLOBAL = -1;
    public static final int MODE_OFF = 0;
    public static final int MODE_BLUR = 1;
    public static final int MODE_FROSTED = 2;
    public static final int MODE_LIQUID = 3;

    // RenderEngine BlurRegion.material IDs (must match frameworks/native BlurFilter.cpp)
    public static final int MATERIAL_PLAIN_OPTOUT = -1;
    public static final int MATERIAL_AUTO = 0;
    public static final int MATERIAL_COS_DARK_INACTIVE = 1;
    public static final int MATERIAL_COS_DARK_ACTIVE = 2;
    public static final int MATERIAL_COS_LIGHT_INACTIVE = 3;
    public static final int MATERIAL_COS_LIGHT_ACTIVE = 4;
    public static final int MATERIAL_COS_DARK_PANEL = 5;
    public static final int MATERIAL_COS_LIGHT_PANEL = 6;
    public static final int MATERIAL_COS_DARK_CARD = 7;
    public static final int MATERIAL_COS_LIGHT_CARD = 8;
    public static final int MATERIAL_COS_DARK_HUN = 9;
    public static final int MATERIAL_COS_LIGHT_HUN = 10;
    public static final int MATERIAL_DOCK_NEUTRAL = 11;
    public static final int MATERIAL_LIQUID_PANEL = 12;
    public static final int MATERIAL_LIQUID_SLAB_DARK = 13;
    public static final int MATERIAL_LIQUID_SLAB_LIGHT = 14;
    public static final int MATERIAL_PLAIN_BLUR = 15;

    // Settings.Secure keys
    public static final String KEY_ENABLED = "yuki_glass_enabled";
    public static final String KEY_STYLE = "yuki_glass_style";
    public static final String KEY_INTENSITY = "yuki_glass_intensity";
    public static final String KEY_REFRACTION = "yuki_glass_refraction";
    public static final String KEY_DISPERSION = "yuki_glass_dispersion";
    public static final String KEY_DEPTH = "yuki_glass_depth";
    public static final String KEY_VIBRANCY = "yuki_glass_vibrancy";
    public static final String KEY_RIM = "yuki_glass_rim";
    public static final String KEY_FROST = "yuki_glass_frost";

    public static final String KEY_QS_MODE = "yuki_glass_qs_mode";
    public static final String KEY_QS_TILES = "yuki_glass_qs_tiles";
    public static final String KEY_SHADE_MODE = "yuki_glass_shade_mode";
    public static final String KEY_NOTIF_CARDS = "yuki_glass_notif_cards";
    public static final String KEY_DUAL_TONE = "yuki_glass_dual_tone";

    public static final String KEY_RECENTS_MODE = "yuki_glass_recents_mode";
    public static final String KEY_RECENTS_RADIUS = "yuki_glass_recents_radius";
    public static final String KEY_RECENTS_ACTIONS = "yuki_glass_recents_actions";

    public static final String KEY_DRAWER_MODE = "yuki_glass_drawer_mode";
    public static final String KEY_DRAWER_RADIUS = "yuki_glass_drawer_radius";
    public static final String KEY_DRAWER_OPACITY = "yuki_glass_drawer_opacity";

    public static final String KEY_SETTINGS_MODE = "yuki_glass_settings_mode";
    public static final String KEY_SETTINGS_RADIUS = "yuki_glass_settings_radius";
    public static final String KEY_SETTINGS_OPACITY = "yuki_glass_settings_opacity";

    // Mirrored SystemProperties (u:object_r:system_prop:s0 -> readable by surfaceflinger & apps)
    public static final String PROP_ENABLED = "persist.sys.yuki.glass.enabled";
    public static final String PROP_STYLE = "persist.sys.yuki.glass.style";
    public static final String PROP_INTENSITY = "persist.sys.yuki.glass.intensity";
    public static final String PROP_REFRACTION = "persist.sys.yuki.glass.refraction";
    public static final String PROP_DISPERSION = "persist.sys.yuki.glass.dispersion";
    public static final String PROP_DEPTH = "persist.sys.yuki.glass.depth";
    public static final String PROP_VIBRANCY = "persist.sys.yuki.glass.vibrancy";
    public static final String PROP_RIM = "persist.sys.yuki.glass.rim";
    public static final String PROP_FROST = "persist.sys.yuki.glass.frost";
    public static final String PROP_SHADE_MODE = "persist.sys.yuki.glass.shade_mode";
    public static final String PROP_RECENTS_MODE = "persist.sys.yuki.glass.recents_mode";
    public static final String PROP_RECENTS_RADIUS = "persist.sys.yuki.glass.recents_radius";
    public static final String PROP_RECENTS_ACTIONS = "persist.sys.yuki.glass.recents_actions";
    public static final String PROP_DRAWER_MODE = "persist.sys.yuki.glass.drawer_mode";
    public static final String PROP_DRAWER_RADIUS = "persist.sys.yuki.glass.drawer_radius";
    public static final String PROP_DRAWER_OPACITY = "persist.sys.yuki.glass.drawer_opacity";

    // Defaults (stock-first: blur & glass off until enabled in YukiVerse Launcher or PLEnhanced)
    public static final int DEFAULT_ENABLED = 0;
    public static final int DEFAULT_STYLE = MODE_LIQUID;
    public static final int DEFAULT_INTENSITY = 100;
    public static final int DEFAULT_REFRACTION = 60;
    public static final int DEFAULT_DISPERSION = 60;
    public static final int DEFAULT_DEPTH = 100;
    public static final int DEFAULT_VIBRANCY = 15;
    public static final int DEFAULT_RIM = 80;
    public static final int DEFAULT_FROST = 50;
    public static final int DEFAULT_QS_TILES = 1;
    public static final int DEFAULT_NOTIF_CARDS = 1;
    public static final int DEFAULT_DUAL_TONE = 0;
    public static final int DEFAULT_RECENTS_RADIUS = 34;
    public static final int DEFAULT_RECENTS_ACTIONS = 1;
    public static final int DEFAULT_DRAWER_RADIUS = 34;
    public static final int DEFAULT_DRAWER_OPACITY = 45;
    public static final int DEFAULT_SETTINGS_RADIUS = 34;
    public static final int DEFAULT_SETTINGS_OPACITY = 76;

    /** Keys mirrored by WindowManagerService to SystemProperties. */
    public static final String[][] MIRRORED_KEYS = new String[][] {
        { KEY_ENABLED, PROP_ENABLED, String.valueOf(DEFAULT_ENABLED) },
        { KEY_STYLE, PROP_STYLE, String.valueOf(DEFAULT_STYLE) },
        { KEY_INTENSITY, PROP_INTENSITY, String.valueOf(DEFAULT_INTENSITY) },
        { KEY_REFRACTION, PROP_REFRACTION, String.valueOf(DEFAULT_REFRACTION) },
        { KEY_DISPERSION, PROP_DISPERSION, String.valueOf(DEFAULT_DISPERSION) },
        { KEY_DEPTH, PROP_DEPTH, String.valueOf(DEFAULT_DEPTH) },
        { KEY_VIBRANCY, PROP_VIBRANCY, String.valueOf(DEFAULT_VIBRANCY) },
        { KEY_RIM, PROP_RIM, String.valueOf(DEFAULT_RIM) },
        { KEY_FROST, PROP_FROST, String.valueOf(DEFAULT_FROST) },
        { KEY_SHADE_MODE, PROP_SHADE_MODE, String.valueOf(MODE_FOLLOW_GLOBAL) },
        { KEY_RECENTS_MODE, PROP_RECENTS_MODE, String.valueOf(MODE_FOLLOW_GLOBAL) },
        { KEY_RECENTS_RADIUS, PROP_RECENTS_RADIUS, String.valueOf(DEFAULT_RECENTS_RADIUS) },
        { KEY_RECENTS_ACTIONS, PROP_RECENTS_ACTIONS, String.valueOf(DEFAULT_RECENTS_ACTIONS) },
        { KEY_DRAWER_MODE, PROP_DRAWER_MODE, String.valueOf(MODE_FOLLOW_GLOBAL) },
        { KEY_DRAWER_RADIUS, PROP_DRAWER_RADIUS, String.valueOf(DEFAULT_DRAWER_RADIUS) },
        { KEY_DRAWER_OPACITY, PROP_DRAWER_OPACITY, String.valueOf(DEFAULT_DRAWER_OPACITY) },
    };

    private YukiGlassConfig() {}

    public static boolean isEnabled(ContentResolver resolver) {
        return Settings.Secure.getIntForUser(
                resolver, KEY_ENABLED, DEFAULT_ENABLED, UserHandle.USER_CURRENT) != 0;
    }

    public static int getGlobalStyle(ContentResolver resolver) {
        if (!isEnabled(resolver)) {
            return MODE_OFF;
        }
        int style = Settings.Secure.getIntForUser(
                resolver, KEY_STYLE, DEFAULT_STYLE, UserHandle.USER_CURRENT);
        return clamp(style, MODE_OFF, MODE_LIQUID);
    }

    public static int resolveSurfaceMode(ContentResolver resolver, String surfaceKey) {
        if (!isEnabled(resolver)) {
            return MODE_OFF;
        }
        int overrideMode = Settings.Secure.getIntForUser(
                resolver, surfaceKey, MODE_FOLLOW_GLOBAL, UserHandle.USER_CURRENT);
        if (overrideMode >= MODE_OFF && overrideMode <= MODE_LIQUID) {
            return overrideMode;
        }
        return getGlobalStyle(resolver);
    }

    public static int getIntensityPercent(ContentResolver resolver) {
        return clamp(Settings.Secure.getIntForUser(
                resolver, KEY_INTENSITY, DEFAULT_INTENSITY, UserHandle.USER_CURRENT), 0, 200);
    }

    public static int getInt(ContentResolver resolver, String key, int def, int min, int max) {
        return clamp(Settings.Secure.getIntForUser(
                resolver, key, def, UserHandle.USER_CURRENT), min, max);
    }

    public static boolean getBool(ContentResolver resolver, String key, int def) {
        return Settings.Secure.getIntForUser(resolver, key, def, UserHandle.USER_CURRENT) != 0;
    }

    /** Maps a resolved mode + theme to a whole-window panel RenderEngine material ID. */
    public static int panelMaterialFor(int mode, boolean darkTheme) {
        switch (mode) {
            case MODE_LIQUID:
                return MATERIAL_LIQUID_PANEL;
            case MODE_FROSTED:
                return darkTheme ? MATERIAL_COS_DARK_PANEL : MATERIAL_COS_LIGHT_PANEL;
            case MODE_BLUR:
                return MATERIAL_PLAIN_BLUR;
            default:
                return MATERIAL_PLAIN_OPTOUT;
        }
    }

    /** Maps a resolved mode + theme + state to a rounded-slab/tile/card RenderEngine material ID. */
    public static int slabMaterialFor(int mode, boolean darkTheme, boolean active) {
        switch (mode) {
            case MODE_LIQUID:
                return darkTheme ? MATERIAL_LIQUID_SLAB_DARK : MATERIAL_LIQUID_SLAB_LIGHT;
            case MODE_FROSTED:
                if (darkTheme) {
                    return active ? MATERIAL_COS_DARK_ACTIVE : MATERIAL_COS_DARK_INACTIVE;
                }
                return active ? MATERIAL_COS_LIGHT_ACTIVE : MATERIAL_COS_LIGHT_INACTIVE;
            case MODE_BLUR:
                return MATERIAL_PLAIN_BLUR;
            default:
                return MATERIAL_PLAIN_OPTOUT;
        }
    }

    /** Maps a resolved mode + theme to a notification card RenderEngine material ID. */
    public static int cardMaterialFor(int mode, boolean darkTheme, boolean headsUp) {
        switch (mode) {
            case MODE_LIQUID:
                return darkTheme ? MATERIAL_LIQUID_SLAB_DARK : MATERIAL_LIQUID_SLAB_LIGHT;
            case MODE_FROSTED:
                if (headsUp) {
                    return darkTheme ? MATERIAL_COS_DARK_HUN : MATERIAL_COS_LIGHT_HUN;
                }
                return darkTheme ? MATERIAL_COS_DARK_CARD : MATERIAL_COS_LIGHT_CARD;
            case MODE_BLUR:
                return MATERIAL_PLAIN_BLUR;
            default:
                return MATERIAL_PLAIN_OPTOUT;
        }
    }

    /** Mirrors all Yuki's Addons Settings.Secure keys into persist.sys.yuki.glass.* props. */
    public static void syncToSystemProperties(ContentResolver resolver) {
        for (String[] entry : MIRRORED_KEYS) {
            int def = Integer.parseInt(entry[2]);
            int val = Settings.Secure.getIntForUser(
                    resolver, entry[0], def, UserHandle.USER_CURRENT);
            String strVal = String.valueOf(val);
            if (!strVal.equals(SystemProperties.get(entry[1], ""))) {
                try {
                    SystemProperties.set(entry[1], strVal);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
