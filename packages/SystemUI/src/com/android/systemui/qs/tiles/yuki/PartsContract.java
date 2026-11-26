/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles.yuki;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;

/**
 * SystemUI's half of the contract with the device's Parts app (org.lineageos.settings), which
 * owns Power profile and Yuki Charging Service (device/xiaomi/peridot: SystemUiContract, keep
 * both in sync).
 *
 * SystemUI only reads and asks:
 *  - reads Settings.System power_profile / fast_charge_mode / htsr_state and the Settings.Global
 *    display keys below, all written by Parts as user 0 (the profile is device-wide);
 *  - reads battery level, plug, voltage, current and charge time from BatteryManager;
 *  - asks for changes with explicit broadcasts to Parts' receivers, which require
 *    android.permission.DEVICE_POWER (SystemUI holds it, ordinary apps can't).
 * It never writes those settings, vendor properties or sysfs, and never duplicates the logic.
 */
public final class PartsContract {
    public static final String PACKAGE = "org.lineageos.settings";

    private static final ComponentName POWER_RECEIVER = new ComponentName(PACKAGE,
            PACKAGE + ".power.PowerProfileReceiver");
    private static final ComponentName CHARGING_RECEIVER = new ComponentName(PACKAGE,
            PACKAGE + ".charge.ChargingControlReceiver");
    public static final ComponentName POWER_SETTINGS = new ComponentName(PACKAGE,
            PACKAGE + ".power.PowerProfileActivity");
    public static final ComponentName CHARGING_SETTINGS = new ComponentName(PACKAGE,
            PACKAGE + ".charge.ChargingControlActivity");

    // Parts' own settings (Settings.System, user 0)
    public static final String KEY_POWER_PROFILE = "power_profile";
    public static final String KEY_FAST_CHARGE_MODE = "fast_charge_mode";
    public static final String KEY_HTSR = "htsr_state";
    // Display keys Parts publishes on change (Settings.Global)
    public static final String KEY_POWER_ENABLED = "yuki_power_profile_enabled";
    public static final String KEY_AUTO_STATE = "yuki_auto_state";
    public static final String KEY_BYPASS_STATE = "yuki_bypass_state";
    public static final String KEY_NIGHT_CHARGING = "yuki_night_charging";
    public static final String KEY_CARE_LINE = "yuki_care_line";

    // Requests (explicit, DEVICE_POWER)
    private static final String ACTION_SET_POWER_PROFILE =
            "org.lineageos.settings.power.ACTION_SET_POWER_PROFILE";
    private static final String ACTION_SET_HTSR = "org.lineageos.settings.power.ACTION_SET_HTSR";
    private static final String EXTRA_PROFILE = "org.lineageos.settings.power.extra.PROFILE";
    private static final String EXTRA_HTSR = "org.lineageos.settings.power.extra.HTSR_STATE";
    private static final String ACTION_SET_MODE =
            "org.lineageos.settings.charge.ACTION_SET_FAST_CHARGE_MODE";
    private static final String ACTION_SET_BYPASS = "org.lineageos.settings.charge.ACTION_SET_BYPASS";
    private static final String ACTION_SET_NIGHT =
            "org.lineageos.settings.charge.ACTION_SET_NIGHT_CHARGING";
    private static final String EXTRA_MODE = "org.lineageos.settings.charge.extra.MODE";
    private static final String EXTRA_ENABLED = "org.lineageos.settings.charge.extra.ENABLED";

    // Profile ids as Parts stores them
    public static final int PROFILE_AUTO = -3;
    public static final int PROFILE_BALANCED = 0;
    public static final int PROFILE_BATTERY = 1;
    public static final int PROFILE_PERFORMANCE = 6;
    public static final int PROFILE_GAMING = 18;

    public static final String MODE_SLOW = "0";
    public static final String MODE_FAST = "1";
    public static final String MODE_SUPERFAST = "2";

    public static final String BYPASS_UNAVAILABLE = "unavailable";
    public static final String BYPASS_ON = "on";
    public static final String BYPASS_PENDING_ON = "pending_on";
    public static final String BYPASS_PENDING_OFF = "pending_off";

    private PartsContract() {}

    /** The tiles exist only where Parts provides these features. */
    public static boolean isAvailable(Context context) {
        try {
            context.getPackageManager().getReceiverInfo(POWER_RECEIVER,
                    PackageManager.MATCH_SYSTEM_ONLY);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---- reads (user 0: Parts runs there and the state is device-wide) -------------------

    public static int getProfile(Context context) {
        int value = Settings.System.getIntForUser(context.getContentResolver(),
                KEY_POWER_PROFILE, PROFILE_AUTO, UserHandle.USER_SYSTEM);
        switch (value) {
            case PROFILE_AUTO:
            case PROFILE_BALANCED:
            case PROFILE_BATTERY:
            case PROFILE_PERFORMANCE:
            case PROFILE_GAMING:
                return value;
            default:
                return PROFILE_AUTO;
        }
    }

    public static boolean isPowerEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), KEY_POWER_ENABLED, 1) != 0;
    }

    public static String getAutoState(Context context) {
        String state = Settings.Global.getString(context.getContentResolver(), KEY_AUTO_STATE);
        return state != null ? state : "off";
    }

    public static boolean isHtsrOn(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(), KEY_HTSR, 0,
                UserHandle.USER_SYSTEM) == 1;
    }

    public static String getChargingMode(Context context) {
        String mode = Settings.System.getStringForUser(context.getContentResolver(),
                KEY_FAST_CHARGE_MODE, UserHandle.USER_SYSTEM);
        if (MODE_SLOW.equals(mode) || MODE_SUPERFAST.equals(mode)) return mode;
        return MODE_FAST;
    }

    public static String getBypassState(Context context) {
        String state = Settings.Global.getString(context.getContentResolver(), KEY_BYPASS_STATE);
        return state != null ? state : "off";
    }

    public static boolean isNightCharging(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), KEY_NIGHT_CHARGING, 0) != 0;
    }

    public static String getCareLine(Context context) {
        return Settings.Global.getString(context.getContentResolver(), KEY_CARE_LINE);
    }

    /** Observes the Power profile keys (or the charging ones) for the tile and its panel. */
    public static void observePower(ContentResolver resolver, ContentObserver observer) {
        resolver.registerContentObserver(Settings.System.getUriFor(KEY_POWER_PROFILE), false,
                observer, UserHandle.USER_SYSTEM);
        resolver.registerContentObserver(Settings.System.getUriFor(KEY_HTSR), false,
                observer, UserHandle.USER_SYSTEM);
        resolver.registerContentObserver(Settings.Global.getUriFor(KEY_POWER_ENABLED), false,
                observer, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Global.getUriFor(KEY_AUTO_STATE), false,
                observer, UserHandle.USER_ALL);
    }

    public static void observeCharging(ContentResolver resolver, ContentObserver observer) {
        resolver.registerContentObserver(Settings.System.getUriFor(KEY_FAST_CHARGE_MODE), false,
                observer, UserHandle.USER_SYSTEM);
        resolver.registerContentObserver(Settings.Global.getUriFor(KEY_BYPASS_STATE), false,
                observer, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Global.getUriFor(KEY_NIGHT_CHARGING), false,
                observer, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Global.getUriFor(KEY_CARE_LINE), false,
                observer, UserHandle.USER_ALL);
    }

    // ---- requests ------------------------------------------------------------------------

    public static void setProfile(Context context, int profile) {
        send(context, new Intent(ACTION_SET_POWER_PROFILE).setComponent(POWER_RECEIVER)
                .putExtra(EXTRA_PROFILE, profile));
    }

    public static void setHtsr(Context context, boolean on) {
        send(context, new Intent(ACTION_SET_HTSR).setComponent(POWER_RECEIVER)
                .putExtra(EXTRA_HTSR, on));
    }

    public static void setChargingMode(Context context, String mode) {
        send(context, new Intent(ACTION_SET_MODE).setComponent(CHARGING_RECEIVER)
                .putExtra(EXTRA_MODE, mode));
    }

    public static void setBypass(Context context, boolean on) {
        send(context, new Intent(ACTION_SET_BYPASS).setComponent(CHARGING_RECEIVER)
                .putExtra(EXTRA_ENABLED, on));
    }

    public static void setNightCharging(Context context, boolean on) {
        send(context, new Intent(ACTION_SET_NIGHT).setComponent(CHARGING_RECEIVER)
                .putExtra(EXTRA_ENABLED, on));
    }

    private static void send(Context context, Intent intent) {
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
    }
}
