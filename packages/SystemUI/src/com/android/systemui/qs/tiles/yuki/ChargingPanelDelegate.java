/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles.yuki;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.concurrent.Executor;

/**
 * Charging panel: the live line (level, power, time to full), Slow / Fast / Superfast as a
 * connected group, and the Bypass and Night charging switches. Parts owns every value; the
 * battery numbers come from BatteryManager, read at most once a second on the background
 * executor and only while the panel is visible.
 */
public final class ChargingPanelDelegate implements SystemUIDialog.Delegate {
    private static final long LIVE_MS = 1000;
    /** How long a tap on Bypass waits for Parts to publish the switch before showing state. */
    private static final long BYPASS_REQUEST_MS = 3000;
    private static final String[] MODES = {
            PartsContract.MODE_SLOW, PartsContract.MODE_FAST, PartsContract.MODE_SUPERFAST};

    private final Context mContext;
    private final SystemUIDialog.Factory mDialogFactory;
    private final ActivityStarter mActivityStarter;
    private final KeyguardStateController mKeyguard;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final Handler mMainHandler;
    private final Executor mBackground;

    private final PanelChoice[] mSegments = new PanelChoice[3];
    private SystemUIDialog mDialog;
    private TextView mSubtitle;
    private ImageView mHeaderIcon;
    private PanelSwitchRow mBypass, mNight;
    private String mShownMode;
    private boolean mStarted;
    /** Bypass state when the user tapped, until Parts publishes a different one; else null. */
    private String mBypassStateAtRequest;
    private boolean mBypassRequested;
    private long mBypassRequestedAt;
    private ChargingStatus mStatus;

    private final ContentObserver mObserver;
    private final Runnable mTick = this::tick;

    public ChargingPanelDelegate(Context context, SystemUIDialog.Factory dialogFactory,
            ActivityStarter activityStarter, KeyguardStateController keyguard,
            DialogTransitionAnimator dialogTransitionAnimator, Handler mainHandler,
            Executor background) {
        mContext = context;
        mDialogFactory = dialogFactory;
        mActivityStarter = activityStarter;
        mKeyguard = keyguard;
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mMainHandler = mainHandler;
        mBackground = background;
        mObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                bindParts(true);
            }
        };
    }

    @Override
    public SystemUIDialog createDialog() {
        SystemUIDialog dialog = mDialogFactory.create(this, mContext);
        SystemUIDialog.setShowForAllUsers(dialog, true);
        SystemUIDialog.registerDismissListener(dialog);
        SystemUIDialog.setWindowOnTop(dialog, mKeyguard.isShowing());
        return dialog;
    }

    @Override
    public void onCreate(SystemUIDialog dialog, Bundle savedInstanceState) {
        mDialog = dialog;
        Context c = dialog.getContext();
        View root = LayoutInflater.from(c).inflate(R.layout.qs_yuki_charging_panel, null);
        root.setAccessibilityPaneTitle(c.getString(R.string.yuki_charging_panel_title));
        dialog.setContentView(root);

        mSubtitle = root.findViewById(R.id.yuki_panel_subtitle);
        mHeaderIcon = root.findViewById(R.id.yuki_panel_icon);

        float density = c.getResources().getDisplayMetrics().density;
        float outer = 20 * density;
        float inner = 8 * density;
        boolean rtl = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                || c.getResources().getConfiguration().getLayoutDirection()
                        == View.LAYOUT_DIRECTION_RTL;
        // tl, tr, br, bl: the ends of the group keep their outer corners.
        float[] start = rtl ? new float[] {inner, outer, outer, inner}
                : new float[] {outer, inner, inner, outer};
        float[] end = rtl ? new float[] {outer, inner, inner, outer}
                : new float[] {inner, outer, outer, inner};
        int[] ids = {R.id.yuki_speed_slow, R.id.yuki_speed_fast, R.id.yuki_speed_superfast};
        float[][] radii = {start, new float[] {inner, inner, inner, inner}, end};
        for (int i = 0; i < 3; i++) {
            String mode = MODES[i];
            mSegments[i] = new PanelChoice(root.findViewById(ids[i]), radii[i]);
            mSegments[i].bind(ChargingTileText.modeIcon(mode),
                    c.getString(ChargingTileText.modeName(mode)));
            mSegments[i].setOnClickListener(v -> onModeClicked(v, mode));
        }

        mBypass = new PanelSwitchRow(root.findViewById(R.id.yuki_bypass_row),
                R.drawable.qs_yuki_row_top, R.drawable.ic_yuki_bypass, R.string.yuki_bypass,
                this::onBypassToggled);
        mNight = new PanelSwitchRow(root.findViewById(R.id.yuki_night_row),
                R.drawable.qs_yuki_row_bottom, R.drawable.ic_yuki_night, R.string.yuki_night,
                checked -> runUnlocked(() -> PartsContract.setNightCharging(mContext, checked)));

        root.findViewById(R.id.yuki_panel_settings).setOnClickListener(this::openSettings);
        root.findViewById(R.id.yuki_panel_done).setOnClickListener(v -> dialog.dismiss());
    }

    private void onModeClicked(View view, String mode) {
        if (mode.equals(mShownMode)) return;
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK);
        selectMode(mode, true);
        runUnlocked(() -> {
            PartsContract.setChargingMode(mContext, mode);
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        });
    }

    private void onBypassToggled(boolean enable) {
        mBypassStateAtRequest = PartsContract.getBypassState(mContext);
        mBypassRequested = enable;
        mBypassRequestedAt = SystemClock.uptimeMillis();
        mBypass.setSummary(mContext.getString(R.string.yuki_bypass_pending));
        runUnlocked(() -> PartsContract.setBypass(mContext, enable));
    }

    private void selectMode(String mode, boolean animate) {
        mShownMode = mode;
        for (int i = 0; i < 3; i++) mSegments[i].setSelected(MODES[i].equals(mode), animate);
        mHeaderIcon.setImageResource(ChargingTileText.modeIcon(mode));
    }

    private void runUnlocked(Runnable action) {
        if (!mKeyguard.isShowing() || mKeyguard.isUnlocked()) {
            action.run();
            return;
        }
        if (mDialog != null) mDialog.dismiss();
        mActivityStarter.dismissKeyguardThenExecute(() -> {
            action.run();
            return false;
        }, null /* cancelAction */, true /* afterKeyguardGone */);
    }

    private void openSettings(View view) {
        Intent intent = new Intent().setComponent(PartsContract.CHARGING_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityTransitionAnimator.Controller controller =
                mDialogTransitionAnimator.createActivityTransitionController(view);
        if (controller == null && mDialog != null) mDialog.dismiss();
        mActivityStarter.postStartActivityDismissingKeyguard(intent, 0, controller);
    }

    @Override
    public void onStart(SystemUIDialog dialog) {
        mStarted = true;
        PartsContract.observeCharging(mContext.getContentResolver(), mObserver);
        bindParts(false);
        tick();
    }

    @Override
    public void onStop(SystemUIDialog dialog) {
        mStarted = false;
        mMainHandler.removeCallbacks(mTick);
        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    /** Battery numbers: read on the background executor, bound on main, once a second. */
    private void tick() {
        if (!mStarted) return;
        mBackground.execute(() -> {
            ChargingStatus status = ChargingStatus.read(mContext);
            mMainHandler.post(() -> {
                if (!mStarted) return;
                mStatus = status;
                bindLive();
                mMainHandler.removeCallbacks(mTick);
                mMainHandler.postDelayed(mTick, LIVE_MS);
            });
        });
    }

    private void bindLive() {
        if (mStatus == null || mSubtitle == null) return;
        String bypass = PartsContract.getBypassState(mContext);
        PanelText.crossfade(mSubtitle, ChargingTileText.liveLine(mContext, mStatus,
                PartsContract.BYPASS_ON.equals(bypass)));
        bindBypass(bypass);
    }

    private void bindParts(boolean animate) {
        if (mSubtitle == null) return;
        String mode = PartsContract.getChargingMode(mContext);
        if (!mode.equals(mShownMode)) selectMode(mode, animate);
        mNight.setChecked(PartsContract.isNightCharging(mContext));
        // The switch already says off; the care line only adds something while it is on.
        mNight.setSummary(PartsContract.isNightCharging(mContext)
                ? PartsContract.getCareLine(mContext) : null);
        bindLive();
    }

    private void bindBypass(String state) {
        if (mBypassStateAtRequest != null) {
            boolean waiting = state.equals(mBypassStateAtRequest)
                    && SystemClock.uptimeMillis() - mBypassRequestedAt < BYPASS_REQUEST_MS;
            if (waiting) {
                // Parts has not published the request yet: keep showing what was asked for.
                mBypass.setBlocked(true);
                mBypass.setChecked(mBypassRequested);
                mBypass.setSummary(mContext.getString(R.string.yuki_bypass_pending));
                return;
            }
            mBypassStateAtRequest = null;
        }
        boolean plugged = mStatus != null && mStatus.plugged;
        boolean pending = PartsContract.BYPASS_PENDING_ON.equals(state)
                || PartsContract.BYPASS_PENDING_OFF.equals(state);
        boolean on = PartsContract.BYPASS_ON.equals(state)
                || PartsContract.BYPASS_PENDING_ON.equals(state);
        int summary;
        if (PartsContract.BYPASS_UNAVAILABLE.equals(state)) {
            summary = R.string.yuki_bypass_unsupported;
        } else if (pending) {
            summary = R.string.yuki_bypass_pending;
        } else if (on) {
            summary = R.string.yuki_bypass_on;
        } else if (!plugged) {
            summary = R.string.yuki_bypass_unplugged;
        } else {
            summary = R.string.yuki_bypass_off;
        }
        // Blocked: unsupported, mid-switch, or turning on without a charger.
        mBypass.setBlocked(PartsContract.BYPASS_UNAVAILABLE.equals(state) || pending
                || (!on && !plugged));
        mBypass.setChecked(on);
        mBypass.setSummary(mContext.getString(summary));
    }
}
