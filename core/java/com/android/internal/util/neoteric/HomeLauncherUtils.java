/*
 * SPDX-FileCopyrightText: 2026 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.neoteric;

import android.content.ComponentName;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.text.TextUtils;

import com.android.internal.R;

/**
 * Resolves the active home/recents launcher configured via YukiVerse Launcher settings.
 *
 * @hide
 */
public final class HomeLauncherUtils {

    public static final String PROP_ACTIVE_LAUNCHER = "persist.sys.neoteric.launcher";
    public static final String PROP_PIXEL_LAUNCHER_SHIPPED = "persist.sys.neoteric.pixel_launcher_shipped";

    public static final int LAUNCHER_NEOTERIC = 0;
    public static final int LAUNCHER_PIXEL = 1;

    public static final String PACKAGE_NEOTERIC_LAUNCHER = "com.android.launcher3";
    public static final String PACKAGE_PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher";

    private HomeLauncherUtils() {}

    public static int getActiveLauncherIndex() {
        return SystemProperties.getInt(PROP_ACTIVE_LAUNCHER, LAUNCHER_NEOTERIC);
    }

    public static boolean isPixelLauncherShipped() {
        return SystemProperties.getInt(PROP_PIXEL_LAUNCHER_SHIPPED, 0) != 0;
    }

    public static boolean isPixelLauncherSelected() {
        return getActiveLauncherIndex() == LAUNCHER_PIXEL;
    }

    public static String resolveRecentsComponentString(Resources res) {
        String[] components = res.getStringArray(R.array.config_homeLauncherComponents);
        int idx = getActiveLauncherIndex();
        if (components != null && components.length > 0) {
            if (idx < 0 || idx >= components.length) {
                idx = LAUNCHER_NEOTERIC;
            }
            if (!TextUtils.isEmpty(components[idx])) {
                return components[idx];
            }
        }
        return res.getString(R.string.config_recentsComponentName);
    }

    public static ComponentName resolveRecentsComponent(Resources res) {
        String raw = resolveRecentsComponentString(res);
        return TextUtils.isEmpty(raw) ? null : ComponentName.unflattenFromString(raw);
    }

    public static String resolveLauncherPackage(Resources res, String fallback) {
        String[] packages = res.getStringArray(R.array.config_homeLauncherPackages);
        int idx = getActiveLauncherIndex();
        if (packages != null && packages.length > 0) {
            if (idx < 0 || idx >= packages.length) {
                idx = LAUNCHER_NEOTERIC;
            }
            if (!TextUtils.isEmpty(packages[idx])) {
                return packages[idx];
            }
        }
        return fallback;
    }
}
