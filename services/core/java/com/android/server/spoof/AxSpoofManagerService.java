/*
 * Copyright (C) 2025-2026 AxionOS
 * Copyright (C) 2026 Lunaris AOSP
 * Copyright (C) 2026 Neoteric OS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.spoof;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.pif.PlayIntegritySpoofService;
import android.security.trickystore.TrickyStoreService;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.util.custom.PixelDeviceRepository;
import com.android.server.SystemService;
import com.android.server.security.rkp.DuckRkpManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public final class AxSpoofManagerService extends SystemService {
    private static final String TAG = "AxSpoofManager";

    private static final String[] WATCHED_KEYS = {
            Settings.Secure.SPOOF_PIF_CONFIG,
            Settings.Secure.SPOOF_PIF_PHOTOS,
            Settings.Secure.PI_PHOTOS_SPOOF,
            Settings.Secure.SPOOF_GAMEPROPS_CONFIG,
            Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
            Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
            Settings.Secure.SPOOF_TRICKYSTORE_PATCH,
            Settings.Secure.SPOOF_DUCK_RKP_ENABLED,
    };

    private static final long REFRESH_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);
    private static final long BOOTSTRAP_INITIAL_DELAY_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long BOOTSTRAP_RETRY_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int BOOTSTRAP_MAX_RETRIES = 6;
    private static final long REFRESH_STEP_DEADLINE_MS = TimeUnit.SECONDS.toMillis(20);
    private static final long REFRESH_AFTER_CLEAR_DELAY_MS = TimeUnit.SECONDS.toMillis(5);

    private static final String ACTIVATED_KEY = "spoof_activated";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String PIF_ENABLED_KEY = "spoof_pif_enabled";
    private static final String TRICKYSTORE_ENABLED_KEY = "spoof_trickystore_enabled";
    private static final String LAST_AUTO_FETCH_KEY = "spoof_pif_last_auto_fetch";
    private static final long AUTO_FETCH_COOLDOWN_MS = TimeUnit.DAYS.toMillis(1);
    private static final long REFETCH_WINDOW_DAYS = 15L;
    private static final Pattern PLAIN_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final String VENDING_PACKAGE = "com.android.vending";
    private static final String PHOTOS_PACKAGE = "com.google.android.apps.photos";
    private static final String[] GMS_FAMILY = {
            VENDING_PACKAGE,
            "com.google.android.gms.unstable",
            "com.google.android.gms",
            "com.google.android.gms.persistent",
            "com.google.android.rkpdapp",
            "com.google.android.gsf",
            "com.google.android.contactkeys",
            "com.google.android.safetycore",
            "com.google.android.googlequicksearchbox",
    };

    // Staging props for bionic's custom_rom_hide_get_prop_override(), served
    // exclusively inside DroidGuard (com.google.android.gms.unstable).
    private static final String PIF_PRODUCT_PROP_PREFIX = "persist.sys.pif.product.";
    private static final String[] PIF_PRODUCT_PROP_NAMES = {
            "manufacturer", "brand", "model", "device", "name",
    };
    private static final String PIF_BUILD_ID_PROP = "persist.sys.pif.build.id";
    private static final String PIF_SECURITY_PATCH_PROP = "persist.sys.pif.security_patch";
    private static final String PIF_API_LEVEL_PROP = "persist.sys.pif.api_level";
    private static final String PIF_BUILD_TYPE_PROP = "persist.sys.pif.build.type";
    private static final String PIF_BUILD_TAGS_PROP = "persist.sys.pif.build.tags";
    private static final String PIF_BUILD_DESCRIPTION_PROP = "persist.sys.pif.build.description";
    private static final String PIF_BUILD_FLAVOR_PROP = "persist.sys.pif.build.flavor";
    private static final String PIF_BUILD_FINGERPRINT_PROP = "persist.sys.pif.build.fingerprint";
    private static final String PIF_BUILD_INCREMENTAL_PROP = "persist.sys.pif.build.incremental";
    private static final String PIF_CUSTOM_COUNT_PROP = "persist.sys.pif.x.count";
    private static final String PIF_CUSTOM_SLOT_PREFIX = "persist.sys.pif.x.";
    private static final int PIF_CUSTOM_SLOT_MAX = 32;
    private static final int PROP_VALUE_MAX_CHARS = 91;

    private final ContentResolver mResolver;
    private final Map<String, String> mCache = new ConcurrentHashMap<>();
    private final AxSpoofManagerInternal mLocalService = new LocalService();
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final ExecutorService mFingerprintExecutor = Executors.newSingleThreadExecutor();
    private final Runnable mRefreshAfterClear = this::refreshNow;

    private ContentObserver mObserver;
    private BroadcastReceiver mPackageAddedReceiver;
    private TaskStackListener mTaskListener;
    private int mBootstrapRetries;
    private volatile boolean mReady;
    private volatile boolean mActivated;
    private volatile DuckRkpManager mDuckRkp;
    private volatile int mUserId = UserHandle.USER_SYSTEM;

    public AxSpoofManagerService(Context context) {
        super(context);
        mResolver = context.getContentResolver();
        mHandlerThread = new HandlerThread("AxSpoofManager");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    @Override
    public void onStart() {
        publishLocalService(AxSpoofManagerInternal.class, mLocalService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            systemReady();
        }
    }

    @Override
    public void onUserSwitching(TargetUser from, TargetUser to) {
        final int userId = to.getUserIdentifier();
        mHandler.post(() -> {
            mUserId = userId;
            refreshAll();
            if (mActivated) {
                maybeSeedDefaultPif();
                seedDefaultTargetsIfUnset();
                syncPifStaging();
                killGmsFamily(false);
                restartPhotos();
            }
            Slog.i(TAG, "Spoof config reloaded for user " + userId);
        });
    }

    private void systemReady() {
        if (mReady) return;
        mUserId = ActivityManager.getCurrentUser();
        mDuckRkp = new DuckRkpManager(getContext(), mHandler, () -> mHandler.post(() -> {
            refreshAll();
            TrickyStoreService.getInstance().initialize();
            if (!mActivated) return;
            seedDefaultTargetsIfUnset();
            killGmsFamily(false);
        }));

        refreshAll();
        registerObserver();

        boolean activated = Settings.Secure.getIntForUser(
                mResolver, ACTIVATED_KEY, 0, UserHandle.USER_SYSTEM) != 0;
        if (!activated && isDeviceProvisioned()) {
            // Device is already past Setup Wizard (dirty flash or existing install):
            // activate immediately so spoofing works without requiring a manual Settings visit.
            activated = true;
            Settings.Secure.putIntForUser(mResolver, ACTIVATED_KEY, 1, UserHandle.USER_SYSTEM);
        }
        mActivated = activated;

        if (mActivated) {
            maybeSeedDefaultPif();
            seedDefaultTargetsIfUnset();
            syncPifStaging();
            scheduleFirstRefresh();
        } else {
            Slog.i(TAG, "Spoofing deferred until Setup Wizard completes and Settings is opened");
            registerSettingsWatcher();
        }

        registerPackageAddedReceiver();
        mReady = true;
        Slog.i(TAG, "AxSpoofManager ready");
    }

    private void registerObserver() {
        if (mObserver != null) return;
        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int userId) {
                if (uri == null) return;
                final String key = uri.getLastPathSegment();
                if (key == null) return;

                if (PIF_ENABLED_KEY.equals(key)) {
                    syncPifStaging();
                    killGmsFamily(false);
                    Slog.i(TAG, "PIF master toggle changed");
                    return;
                }
                if (TRICKYSTORE_ENABLED_KEY.equals(key)) {
                    if (mDuckRkp != null) mDuckRkp.onSettingsChanged(true);
                    TrickyStoreService.getInstance().initialize();
                    killGmsFamily(false);
                    Slog.i(TAG, "TrickyStore master toggle changed");
                    return;
                }
                if ((userId == UserHandle.USER_SYSTEM || userId == UserHandle.USER_ALL)
                        && (Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX.equals(key)
                            || Settings.Secure.SPOOF_DUCK_RKP_ENABLED.equals(key))) {
                    if (mDuckRkp != null) mDuckRkp.onSettingsChanged(false);
                }
                if (userId != UserHandle.USER_ALL && userId != mUserId) return;

                refreshKey(key);
                Slog.i(TAG, "Spoof config refreshed: " + key);
                if (Settings.Secure.SPOOF_PIF_CONFIG.equals(key)) {
                    onPifConfigChanged();
                } else if (Settings.Secure.SPOOF_PIF_PHOTOS.equals(key)
                        || Settings.Secure.PI_PHOTOS_SPOOF.equals(key)) {
                    restartPhotos();
                } else if (Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX.equals(key)) {
                    seedDefaultTargetsIfUnset();
                }
            }
        };
        for (String key : WATCHED_KEYS) {
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }
        mResolver.registerContentObserver(Settings.System.getUriFor(PIF_ENABLED_KEY),
                false, mObserver, UserHandle.USER_SYSTEM);
        mResolver.registerContentObserver(Settings.System.getUriFor(TRICKYSTORE_ENABLED_KEY),
                false, mObserver, UserHandle.USER_SYSTEM);
    }

    private void registerPackageAddedReceiver() {
        mPackageAddedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                Uri data = intent.getData();
                String pkg = data != null ? data.getSchemeSpecificPart() : null;
                if (pkg != null) {
                    mHandler.post(() -> onPackageAdded(pkg));
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        getContext().registerReceiverAsUser(
                mPackageAddedReceiver, UserHandle.ALL, filter, null, mHandler);
    }

    private void registerSettingsWatcher() {
        mTaskListener = new TaskStackListener() {
            @Override
            public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
                if (mActivated || taskInfo == null) return;
                final String pkg = taskInfo.topActivity != null
                        ? taskInfo.topActivity.getPackageName()
                        : (taskInfo.baseActivity != null
                                ? taskInfo.baseActivity.getPackageName() : null);
                if (!SETTINGS_PACKAGE.equals(pkg)) return;
                mHandler.post(() -> {
                    if (!mActivated && isDeviceProvisioned()) enableSpoofing();
                });
            }
        };
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mTaskListener);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to watch for Settings launch, spoofing stays off", e);
            mTaskListener = null;
        }
    }

    private void unregisterSettingsWatcher() {
        if (mTaskListener == null) return;
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(mTaskListener);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to unregister Settings watcher", e);
        }
        mTaskListener = null;
    }

    private void enableSpoofing() {
        if (mActivated) return;
        mActivated = true;
        Settings.Secure.putIntForUser(mResolver, ACTIVATED_KEY, 1, UserHandle.USER_SYSTEM);
        unregisterSettingsWatcher();
        Slog.i(TAG, "Settings opened after setup, enabling spoofing");
        maybeSeedDefaultPif();
        seedDefaultTargetsIfUnset();
        PlayIntegritySpoofService.getInstance().loadConfig();
        TrickyStoreService.getInstance().initialize();
        syncPifStaging();
        scheduleFirstRefresh();
        killGmsFamily(true);
    }

    private boolean isPifMasterEnabled() {
        return Settings.System.getIntForUser(
                mResolver, PIF_ENABLED_KEY, 1, UserHandle.USER_SYSTEM) != 0;
    }

    private boolean isTrickyStoreMasterEnabled() {
        return Settings.System.getIntForUser(
                mResolver, TRICKYSTORE_ENABLED_KEY, 1, UserHandle.USER_SYSTEM) != 0;
    }

    private void syncPifStaging() {
        if (!mActivated || !isPifMasterEnabled()) {
            clearPifPropOverrides();
            return;
        }
        stagePifPropOverrides(getCached(Settings.Secure.SPOOF_PIF_CONFIG));
    }

    private void onPifConfigChanged() {
        final String config = getCached(Settings.Secure.SPOOF_PIF_CONFIG);
        if (config != null && !config.trim().isEmpty()) {
            if (mActivated && isPifMasterEnabled()) {
                stagePifPropOverrides(config);
            } else {
                clearPifPropOverrides();
            }
            killGmsFamily(false);
            return;
        }

        clearPifPropOverrides();
        killGmsFamily(true);
        Slog.i(TAG, "PIF config cleared, dropped staged PIF props");

        mHandler.removeCallbacks(mRefreshAfterClear);
        mHandler.postDelayed(mRefreshAfterClear, REFRESH_AFTER_CLEAR_DELAY_MS);
    }

    private void maybeSeedDefaultPif() {
        try {
            if (readSetting(Settings.Secure.SPOOF_PIF_CONFIG) != null) return;
            String[] entries = getContext().getResources().getStringArray(
                    com.android.internal.R.array.config_certifiedBuildProperties);
            if (entries == null || entries.length == 0) return;
            JSONObject json = new JSONObject();
            for (String entry : entries) {
                if (TextUtils.isEmpty(entry)) continue;
                String[] kv = entry.split(":", 2);
                if (kv.length != 2 || kv[0].isEmpty() || kv[1].isEmpty()) continue;
                String key = kv[0];
                if (key.startsWith("VERSION.")) key = key.substring(8);
                json.put(key, kv[1]);
            }
            if (json.length() == 0) return;
            Settings.Secure.putStringForUser(
                    mResolver, Settings.Secure.SPOOF_PIF_CONFIG, json.toString(2), mUserId);
            refreshKey(Settings.Secure.SPOOF_PIF_CONFIG);
            Slog.i(TAG, "Seeded default PIF config from certified overlay (user " + mUserId + ")");
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to seed default PIF config", t);
        }
    }

    private void seedDefaultTargetsIfUnset() {
        try {
            String current = readSetting(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
            if (current != null) return;
            Settings.Secure.putStringForUser(
                    mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
                    TrickyStoreService.DEFAULT_TARGET_LIST, mUserId);
            refreshKey(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
            Slog.i(TAG, "Seeded default TrickyStore targets (user " + mUserId + ")");
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to seed default TrickyStore targets", t);
        }
    }

    private void onPackageAdded(String pkg) {
        if (!mActivated || !isTrickyStoreMasterEnabled()) return;
        if (TrickyStoreService.XPOSED_PACKAGES.contains(pkg)
                || TrickyStoreService.UNNECESSARY_PACKAGES.contains(pkg)) {
            return;
        }
        String current = Settings.Secure.getStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET, mUserId);
        String existing = current != null ? current : "";
        String trimmed = existing.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("[") || trimmed.startsWith("{")) return;

        for (String raw : existing.split("\n")) {
            String line = raw.trim();
            String bare = line.endsWith("!") || line.endsWith("?") || line.endsWith("-")
                    ? line.substring(0, line.length() - 1).trim() : line;
            if (pkg.equals(bare)) return;
        }

        String updated = existing.endsWith("\n") ? existing + pkg : existing + "\n" + pkg;
        Settings.Secure.putStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET, updated, mUserId);
        Slog.i(TAG, "Auto-targeted newly installed package: " + pkg);
    }

    private void scheduleFirstRefresh() {
        mHandler.postDelayed(this::performScheduledRefresh,
                isBootstrapPending() ? BOOTSTRAP_INITIAL_DELAY_MS : REFRESH_INTERVAL_MS);
    }

    private void performScheduledRefresh() {
        if (!mActivated) return;
        refreshNow();
        long delay = REFRESH_INTERVAL_MS;
        if (isBootstrapPending() && mBootstrapRetries < BOOTSTRAP_MAX_RETRIES) {
            mBootstrapRetries++;
            delay = BOOTSTRAP_RETRY_MS;
        }
        mHandler.postDelayed(this::performScheduledRefresh, delay);
    }

    private boolean isBootstrapPending() {
        return isEmpty(Settings.Secure.getStringForUser(
                        mResolver, Settings.Secure.SPOOF_PIF_CONFIG, UserHandle.USER_SYSTEM))
                && isPifMasterEnabled();
    }

    private void refreshNow() {
        if (!mActivated) return;
        try {
            refreshPixelFingerprintIfStale();
        } catch (Exception e) {
            Slog.e(TAG, "Fingerprint refresh failed", e);
        }
    }

    private <T> T runBounded(ExecutorService pool, Callable<T> task) throws Exception {
        Future<T> future = pool.submit(task);
        try {
            return future.get(REFRESH_STEP_DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private void refreshPixelFingerprintIfStale() throws Exception {
        if (!isPifMasterEnabled()) return;

        long lastFetch = Settings.Secure.getLongForUser(
                mResolver, LAST_AUTO_FETCH_KEY, 0L, UserHandle.USER_SYSTEM);
        if (lastFetch > 0L
                && System.currentTimeMillis() - lastFetch < AUTO_FETCH_COOLDOWN_MS) {
            return;
        }

        String content = getCached(Settings.Secure.SPOOF_PIF_CONFIG);
        JSONObject existing = (content != null && !content.trim().isEmpty())
                ? new JSONObject(content) : new JSONObject();
        if (existing.optBoolean("manually_imported", false)) return;

        String canaryMonth = existing.optString("_canary_month", "");
        String releaseDate = existing.has("_canary_release_date")
                ? existing.optString("_canary_release_date") : null;
        Long daysLeft = canaryMonth.isEmpty() ? null
                : PixelDeviceRepository.getDaysUntilExpiry(canaryMonth, releaseDate);
        if (daysLeft != null && daysLeft > REFETCH_WINDOW_DAYS) return;

        PixelDeviceRepository.PixelProfile matched = runBounded(mFingerprintExecutor, () -> {
            List<PixelDeviceRepository.PixelProfile> profiles =
                    PixelDeviceRepository.getProfiles(getContext(), true);
            String defaultCodename = PixelDeviceRepository.getDefaultPifCodename(profiles);
            return PixelDeviceRepository.getProfileByCodename(
                    getContext(), defaultCodename, false);
        });
        if (matched == null || !PixelDeviceRepository.isValidFingerprint(matched.fingerprint)) {
            return;
        }
        if (matched.fingerprint.equals(existing.optString("FINGERPRINT", ""))) {
            Settings.Secure.putLongForUser(
                    mResolver, LAST_AUTO_FETCH_KEY, System.currentTimeMillis(),
                    UserHandle.USER_SYSTEM);
            return;
        }

        JSONObject toSave = new JSONObject(existing.toString());
        for (String stale : new String[] {"ID", "INCREMENTAL", "TYPE", "TAGS", "RELEASE",
                "_canary_month", "_canary_release_date"}) {
            toSave.remove(stale);
        }
        toSave.put("MANUFACTURER", capitalize(matched.brand));
        toSave.put("BRAND", matched.brand);
        toSave.put("MODEL", matched.model);
        toSave.put("PRODUCT", matched.product);
        toSave.put("DEVICE", matched.device);
        toSave.put("FINGERPRINT", matched.fingerprint);
        toSave.put("SECURITY_PATCH", matched.securityPatch);
        toSave.put("DEVICE_INITIAL_SDK_INT",
                PixelDeviceRepository.getInitialSdkInt(matched.device));
        toSave.put("manually_imported", false);
        if (matched.isCanary) {
            String newCanaryMonth = matched.securityPatch != null
                    && matched.securityPatch.length() >= 7
                    ? matched.securityPatch.substring(0, 7) : null;
            if (newCanaryMonth != null) toSave.put("_canary_month", newCanaryMonth);
            if (matched.releaseDate != null) {
                toSave.put("_canary_release_date", matched.releaseDate);
            }
        }

        Settings.Secure.putStringForUser(
                mResolver, Settings.Secure.SPOOF_PIF_CONFIG, toSave.toString(2),
                UserHandle.USER_SYSTEM);
        Settings.Secure.putLongForUser(
                mResolver, LAST_AUTO_FETCH_KEY, System.currentTimeMillis(), UserHandle.USER_SYSTEM);
        updatePatchIfSimple(matched.securityPatch);

        stagePifPropOverrides(toSave.toString(2));
        killGmsFamily(true);
        Slog.i(TAG, "Auto-refreshed Pixel fingerprint: " + matched.model);
    }

    private void updatePatchIfSimple(String patch) {
        if (patch == null || !PLAIN_DATE.matcher(patch).matches()) return;
        String existing = Settings.Secure.getStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_PATCH, UserHandle.USER_SYSTEM);
        String trimmed = existing != null ? existing.trim() : "";
        if (!trimmed.isEmpty() && !PLAIN_DATE.matcher(trimmed).matches()) return;
        if (patch.equals(trimmed)) return;
        Settings.Secure.putStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_PATCH, patch, UserHandle.USER_SYSTEM);
    }

    private void clearPifPropOverrides() {
        for (String name : PIF_PRODUCT_PROP_NAMES) {
            setStagedProp(PIF_PRODUCT_PROP_PREFIX + name, null);
        }
        setStagedProp(PIF_BUILD_ID_PROP, null);
        setStagedProp(PIF_SECURITY_PATCH_PROP, null);
        setStagedProp(PIF_API_LEVEL_PROP, null);
        setStagedProp(PIF_BUILD_TYPE_PROP, null);
        setStagedProp(PIF_BUILD_TAGS_PROP, null);
        setStagedProp(PIF_BUILD_DESCRIPTION_PROP, null);
        setStagedProp(PIF_BUILD_FLAVOR_PROP, null);
        setStagedProp(PIF_BUILD_FINGERPRINT_PROP, null);
        setStagedProp(PIF_BUILD_INCREMENTAL_PROP, null);
        stageCustomProps(null);
    }

    private void stagePifPropOverrides(String config) {
        final Map<String, String> fields = parsePifConfig(config);
        if (fields.isEmpty() || !isSpoofPropsEnabled(fields)) {
            clearPifPropOverrides();
            return;
        }
        final String[] head = fingerprintHead(fields.get("FINGERPRINT"));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "manufacturer", fields.get("MANUFACTURER"));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "brand", firstNonEmpty(
                fields.get("BRAND"), head != null ? head[0] : null));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "model", fields.get("MODEL"));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "device", firstNonEmpty(
                fields.get("DEVICE"), head != null ? head[2] : null));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "name", firstNonEmpty(
                fields.get("PRODUCT"), head != null ? head[1] : null));
        final String[] tail = fingerprintTail(fields.get("FINGERPRINT"));
        final String buildId = firstNonEmpty(
                fields.get("*.build.id"), fields.get("ID"),
                buildIdFromFingerprint(fields.get("FINGERPRINT")));
        setStagedProp(PIF_BUILD_ID_PROP, buildId);
        final String type = firstNonEmpty(fields.get("*.build.type"), fields.get("TYPE"),
                tail != null ? tail[3] : null);
        final String tags = firstNonEmpty(fields.get("*.build.tags"), fields.get("TAGS"),
                tail != null ? tail[4] : null);
        setStagedProp(PIF_BUILD_TYPE_PROP, type);
        setStagedProp(PIF_BUILD_TAGS_PROP, tags);
        final String product = firstNonEmpty(
                fields.get("PRODUCT"), head != null ? head[1] : null);
        final String release = firstNonEmpty(
                fields.get("RELEASE"),
                PixelDeviceRepository.releaseFromFingerprint(fields.get("FINGERPRINT")));
        final String incremental = firstNonEmpty(
                fields.get("INCREMENTAL"), tail != null ? tail[2] : null);
        final boolean haveDescription = product != null && type != null && release != null
                && buildId != null && incremental != null && tags != null;
        setStagedProp(PIF_BUILD_DESCRIPTION_PROP, haveDescription
                ? product + "-" + type + " " + release + " " + buildId + " "
                        + incremental + " " + tags
                : null);
        setStagedProp(PIF_BUILD_FLAVOR_PROP,
                product != null && type != null ? product + "-" + type : null);
        final String fingerprint = fields.get("FINGERPRINT");
        setStagedProp(PIF_BUILD_FINGERPRINT_PROP,
                fingerprint != null && fingerprint.length() <= PROP_VALUE_MAX_CHARS
                        ? fingerprint : null);
        setStagedProp(PIF_BUILD_INCREMENTAL_PROP, incremental);
        setStagedProp(PIF_SECURITY_PATCH_PROP, firstNonEmpty(
                fields.get("*.security_patch"), fields.get("SECURITY_PATCH"),
                PixelDeviceRepository.securityPatchFromBuildId(buildId)));
        setStagedProp(PIF_API_LEVEL_PROP, firstNonEmpty(
                fields.get("*api_level"),
                PixelDeviceRepository.resolveInitialSdkInt(
                        firstNonEmpty(fields.get("DEVICE"), head != null ? head[2] : null),
                        fields.get("DEVICE_INITIAL_SDK_INT"))));
        stageCustomProps(fields);
    }

    private static void stageCustomProps(Map<String, String> fields) {
        final TreeMap<String, String> entries = new TreeMap<>();
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                final String key = e.getKey();
                final String value = e.getValue();
                if (key.indexOf('.') < 0 && key.indexOf('*') < 0) continue;
                if (key.equals("*") || key.startsWith("persist.sys.pif.")) continue;
                if (key.equals("*.build.id") || key.equals("*.security_patch")
                        || key.equals("*api_level") || key.equals("*.build.type")
                        || key.equals("*.build.tags")) continue;
                if (value == null || value.isEmpty()) continue;
                final String entry = key + "=" + value;
                if (entry.length() > PROP_VALUE_MAX_CHARS) {
                    Slog.w(TAG, "PIF property " + key + " is too long to stage, skipping");
                    continue;
                }
                entries.put(key, entry);
            }
        }
        final int previous = Math.min(
                SystemProperties.getInt(PIF_CUSTOM_COUNT_PROP, 0), PIF_CUSTOM_SLOT_MAX);
        int count = 0;
        for (String entry : entries.values()) {
            if (count >= PIF_CUSTOM_SLOT_MAX) {
                Slog.w(TAG, "More than " + PIF_CUSTOM_SLOT_MAX
                        + " custom PIF properties, ignoring the rest");
                break;
            }
            setStagedProp(PIF_CUSTOM_SLOT_PREFIX + count, entry);
            count++;
        }
        setStagedProp(PIF_CUSTOM_COUNT_PROP, count > 0 ? Integer.toString(count) : null);
        for (int i = count; i < previous; i++) {
            setStagedProp(PIF_CUSTOM_SLOT_PREFIX + i, null);
        }
    }

    private static boolean isSpoofPropsEnabled(Map<String, String> fields) {
        final String value = fields.get("spoofProps");
        return value == null || "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static void setStagedProp(String name, String value) {
        final String target = value != null ? value : "";
        try {
            if (target.equals(SystemProperties.get(name, ""))) return;
            SystemProperties.set(name, target);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to stage " + name, e);
        }
    }

    private void killGmsFamily(boolean includeRkpd) {
        if (!mActivated) return;
        try {
            ActivityManager am = getContext().getSystemService(ActivityManager.class);
            if (am == null) return;
            for (String pkg : GMS_FAMILY) {
                if (!includeRkpd && "com.google.android.rkpdapp".equals(pkg)) continue;
                am.forceStopPackageAsUser(pkg, mUserId);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to restart GMS family", t);
        }
    }

    private void restartPhotos() {
        try {
            ActivityManager am = getContext().getSystemService(ActivityManager.class);
            if (am != null) am.forceStopPackageAsUser(PHOTOS_PACKAGE, mUserId);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to restart Photos", t);
        }
    }

    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(mResolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0
                && Settings.Secure.getIntForUser(mResolver,
                        Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_SYSTEM) != 0;
    }

    private void refreshAll() {
        for (String key : WATCHED_KEYS) refreshKey(key);
    }

    private void refreshKey(String key) {
        String value = readSetting(key);
        if (value == null) mCache.remove(key);
        else mCache.put(key, value);
    }

    private String getCached(String key) {
        return mReady ? mCache.get(key) : readSetting(key);
    }

    private String readSetting(String key) {
        final int userId = mReady ? mUserId : UserHandle.USER_CURRENT;
        return Settings.Secure.getStringForUser(mResolver, key, userId);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    private static String[] fingerprintHead(String fingerprint) {
        if (fingerprint == null) return null;
        final int colon = fingerprint.indexOf(':');
        if (colon < 0) return null;
        final String[] head = fingerprint.substring(0, colon).split("/");
        return head.length == 3 ? head : null;
    }

    private static String[] fingerprintTail(String fingerprint) {
        if (fingerprint == null) return null;
        final int first = fingerprint.indexOf(':');
        final int second = first < 0 ? -1 : fingerprint.indexOf(':', first + 1);
        if (second < 0) return null;
        final String[] mid = fingerprint.substring(first + 1, second).split("/");
        final String[] end = fingerprint.substring(second + 1).split("/");
        if (mid.length != 3 || end.length != 2) return null;
        return new String[] {mid[0], mid[1], mid[2], end[0], end[1]};
    }

    private static String buildIdFromFingerprint(String fingerprint) {
        if (fingerprint == null) return null;
        final int colon = fingerprint.indexOf(':');
        if (colon < 0) return null;
        final String[] rest = fingerprint.substring(colon + 1).split("/");
        return rest.length >= 4 ? rest[1] : null;
    }

    private static Map<String, String> parsePifConfig(String config) {
        final Map<String, String> out = new HashMap<>();
        if (config == null || config.trim().isEmpty()) return out;
        try {
            if (config.trim().startsWith("{")) {
                final JSONObject json = new JSONObject(config);
                final Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    if (!json.isNull(key)) out.put(key, json.get(key).toString());
                }
            } else {
                for (String line : config.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    final int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String value = line.substring(eq + 1);
                    final int comment = value.indexOf('#');
                    if (comment >= 0) value = value.substring(0, comment);
                    value = value.trim();
                    if (value.isEmpty()) continue;
                    out.put(line.substring(0, eq).trim(), value);
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Couldn't read PIF config for staging", e);
        }
        return out;
    }

    private static String capitalize(String s) {
        return (s == null || s.isEmpty()) ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int currentForegroundUserId() {
        long token = Binder.clearCallingIdentity();
        try {
            return ActivityManager.getCurrentUser();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean canReadTrickyStoreKeybox() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.SYSTEM_UID || uid == android.os.Process.ROOT_UID) return true;
        final int callingUserId = UserHandle.getUserId(uid);
        if (callingUserId != currentForegroundUserId()
                && callingUserId != UserHandle.USER_SYSTEM) return false;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String pkg : packages) {
            if (TrickyStoreService.XPOSED_PACKAGES.contains(pkg)) return false;
        }
        String targets = getCached(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
        if (targets == null) return false;
        boolean allowed = false;
        try {
            if (targets.trim().startsWith("[")) {
                JSONArray entries = new JSONArray(targets);
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    for (String pkg : packages) {
                        if (pkg.equals(entry.optString("package"))) {
                            if ("SKIP".equalsIgnoreCase(entry.optString("mode"))) return false;
                            allowed = true;
                        }
                    }
                }
            } else {
                for (String raw : targets.split("\n")) {
                    String target = raw.trim();
                    if (target.isEmpty() || target.startsWith("#")) continue;
                    boolean skipped = target.endsWith("-");
                    if (skipped || target.endsWith("!") || target.endsWith("?")) {
                        target = target.substring(0, target.length() - 1).trim();
                    }
                    for (String pkg : packages) {
                        if (pkg.equals(target)) {
                            if (skipped) return false;
                            allowed = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return allowed;
    }

    private final class LocalService implements AxSpoofManagerInternal {
        @Override
        public String getPifConfig() {
            if (!mActivated || !isPifMasterEnabled()) return null;
            return getCached(Settings.Secure.SPOOF_PIF_CONFIG);
        }

        @Override
        public String getPifSpoofPhotos() {
            String v = getCached(Settings.Secure.PI_PHOTOS_SPOOF);
            return v != null ? v : getCached(Settings.Secure.SPOOF_PIF_PHOTOS);
        }

        @Override
        public String getGamePropsConfig() {
            if (!mActivated) return null;
            return getCached(Settings.Secure.SPOOF_GAMEPROPS_CONFIG);
        }

        @Override
        public String getTrickyStoreTarget() {
            if (!mActivated || !isTrickyStoreMasterEnabled()) return null;
            return getCached(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
        }

        @Override
        public String getTrickyStoreKeyBox() {
            if (!mActivated || !isTrickyStoreMasterEnabled()
                    || mDuckRkp == null || !canReadTrickyStoreKeybox()) {
                return null;
            }
            long token = Binder.clearCallingIdentity();
            try {
                return mDuckRkp.getKeybox();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public DuckRkpManager getDuckRkpManager() {
            if (mDuckRkp == null) throw new IllegalStateException("RKP service is starting");
            return mDuckRkp;
        }

        @Override
        public String getTrickyStorePatch() {
            if (!mActivated || !isTrickyStoreMasterEnabled()) return null;
            return getCached(Settings.Secure.SPOOF_TRICKYSTORE_PATCH);
        }
    }
}
