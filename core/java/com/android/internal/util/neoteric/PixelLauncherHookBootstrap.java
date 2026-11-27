/*
 * SPDX-FileCopyrightText: 2026 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.neoteric;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;

import dalvik.system.PathClassLoader;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Smart ROM-level bootstrap for built-in PixelLauncherEnhanced mods without LSPosed.
 *
 * <p>Activates strictly in {@code com.google.android.apps.nexuslauncher}'s main process and
 * only when Pixel Launcher is selected via YukiVerse Launcher settings
 * ({@code persist.sys.neoteric.launcher == 1}).
 *
 * @hide
 */
public final class PixelLauncherHookBootstrap {
    private static final String TAG = "PixelLauncherHook";
    private static final String PIXEL_LAUNCHER_PACKAGE = HomeLauncherUtils.PACKAGE_PIXEL_LAUNCHER;
    private static final String PLE_PACKAGE = "com.drdisagree.pixellauncherenhanced";
    private static final String RUNTIME_JAR = "/system_ext/framework/ple-hook-runtime.jar";
    private static final String ENTRY_CLASS = "com.neoteric.ple.runtime.PixelLauncherHookEntry";

    private static volatile boolean sAttempted = false;

    private PixelLauncherHookBootstrap() {}

    public static void maybeInit(
            Context appContext,
            ApplicationInfo targetAppInfo,
            String processName,
            ClassLoader targetClassLoader) {
        if (targetAppInfo == null || processName == null) {
            return;
        }
        if (!PIXEL_LAUNCHER_PACKAGE.equals(targetAppInfo.packageName)
                || !PIXEL_LAUNCHER_PACKAGE.equals(processName)) {
            return;
        }
        if (Process.isIsolated() || sAttempted) {
            return;
        }
        if (!HomeLauncherUtils.isPixelLauncherSelected()) {
            Log.i(TAG, "Skipping PixelLauncherEnhanced hooks: Pixel Launcher is not selected");
            return;
        }
        sAttempted = true;

        try {
            File runtimeJar = new File(RUNTIME_JAR);
            if (!runtimeJar.isFile()) {
                Log.w(TAG, "Hook runtime jar missing: " + RUNTIME_JAR);
                return;
            }

            PackageManager pm = appContext.getPackageManager();
            if (pm == null) {
                return;
            }
            ApplicationInfo moduleAppInfo;
            try {
                moduleAppInfo = pm.getApplicationInfo(PLE_PACKAGE, 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.d(TAG, "PixelLauncherEnhanced package not installed");
                return;
            }
            if (moduleAppInfo == null || !moduleAppInfo.enabled || moduleAppInfo.sourceDir == null) {
                Log.d(TAG, "PixelLauncherEnhanced package disabled");
                return;
            }

            ClassLoader bootParent = Object.class.getClassLoader();
            PathClassLoader runtimeLoader = new PathClassLoader(RUNTIME_JAR, bootParent);
            Class<?> entryClass = runtimeLoader.loadClass(ENTRY_CLASS);
            Method initMethod = entryClass.getMethod(
                    "init",
                    Context.class,
                    String.class,
                    String.class,
                    ApplicationInfo.class,
                    ApplicationInfo.class,
                    ClassLoader.class);
            initMethod.invoke(
                    null,
                    appContext,
                    targetAppInfo.packageName,
                    processName,
                    targetAppInfo,
                    moduleAppInfo,
                    targetClassLoader);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to bootstrap PixelLauncherEnhanced runtime", t);
        }
    }
}
