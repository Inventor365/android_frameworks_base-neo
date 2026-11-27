/*
 * SPDX-FileCopyrightText: 2026 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.neoteric;

import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.Process.THREAD_PRIORITY_DEFAULT;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.os.IUserManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.util.neoteric.HomeLauncherUtils;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Manages enabling/disabling bundled system home launchers (Neoteric Launcher vs Pixel Launcher)
 * according to {@code persist.sys.neoteric.launcher}.
 */
public final class HomeLauncherSwitchService extends SystemService {

    private static final String TAG = "HomeLauncherSwitchService";

    private final Context mContext;
    private final IPackageManager mPM;
    private final IUserManager mUM;
    private final String mOpPackageName;

    private ServiceThread mWorker;
    private Handler mHandler;

    private static volatile List<String> sLauncherPackages = Collections.emptyList();

    public HomeLauncherSwitchService(Context context) {
        super(context);
        mContext = context;
        sLauncherPackages = Collections.unmodifiableList(Arrays.asList(
                context.getResources().getStringArray(
                        com.android.internal.R.array.config_homeLauncherPackages)));
        mPM = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        mUM = IUserManager.Stub.asInterface(ServiceManager.getService(Context.USER_SERVICE));
        mOpPackageName = context.getOpPackageName();
    }

    public static boolean shouldHide(int callingUid, int userId, String packageName) {
        if (UserHandle.getAppId(callingUid) < FIRST_APPLICATION_UID || packageName == null) {
            return false;
        }
        List<String> launcherPackages = sLauncherPackages;
        if (!launcherPackages.contains(packageName)) {
            return false;
        }
        int active = resolveActiveIndex(launcherPackages);
        return !packageName.equals(launcherPackages.get(active));
    }

    private static int resolveActiveIndex(List<String> launcherPackages) {
        int idx = HomeLauncherUtils.getActiveLauncherIndex();
        if (idx < 0 || idx >= launcherPackages.size()) {
            return HomeLauncherUtils.LAUNCHER_NEOTERIC;
        }
        return idx;
    }

    private void updateStateForUser(int userId) {
        List<String> launcherPackages = sLauncherPackages;
        if (launcherPackages.isEmpty()) {
            return;
        }
        int active = resolveActiveIndex(launcherPackages);
        String activePackage = launcherPackages.get(active);
        for (String packageName : launcherPackages) {
            int state = packageName.equals(activePackage)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            try {
                mPM.setApplicationEnabledSetting(packageName, state, 0, userId, mOpPackageName);
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "Skipping unavailable launcher " + packageName + " for user " + userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to update launcher " + packageName + " for user " + userId, e);
            }
        }
    }

    private void initForUser(int userId) {
        if (userId >= 0) {
            updateStateForUser(userId);
        }
    }

    @Override
    public void onStart() {
        mWorker = new ServiceThread(TAG, THREAD_PRIORITY_DEFAULT, false);
        mWorker.start();
        mHandler = new Handler(mWorker.getLooper());

        try {
            for (UserInfo user : mUM.getUsers(false)) {
                initForUser(user.id);
            }
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_USER_ADDED.equals(intent.getAction())) {
                    int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    initForUser(userId);
                }
            }
        }, filter, android.Manifest.permission.MANAGE_USERS, mHandler);
    }
}
