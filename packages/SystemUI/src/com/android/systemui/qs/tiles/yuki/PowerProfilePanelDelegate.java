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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Power profile panel: Auto across the top, a 2x2 grid of the manual profiles, the high touch
 * sampling switch, and Settings / Done. Everything shown comes from Parts' settings
 * (PartsContract) and updates through observers while the panel is visible; every change is a
 * request to Parts. On the lock screen the panel shows, but a change first dismisses it and asks
 * for an unlock, then applies.
 */
public final class PowerProfilePanelDelegate implements SystemUIDialog.Delegate {
    private final Context mContext;
    private final SystemUIDialog.Factory mDialogFactory;
    private final ActivityStarter mActivityStarter;
    private final KeyguardStateController mKeyguard;
    private final DialogTransitionAnimator mDialogTransitionAnimator;

    private final Map<Integer, PanelChoice> mChoices = new LinkedHashMap<>();
    private SystemUIDialog mDialog;
    private TextView mSubtitle;
    private ImageView mHeaderIcon;
    private PanelSwitchRow mHtsr;
    private int mShownProfile = Integer.MIN_VALUE;

    private final ContentObserver mObserver;

    public PowerProfilePanelDelegate(Context context, SystemUIDialog.Factory dialogFactory,
            ActivityStarter activityStarter, KeyguardStateController keyguard,
            DialogTransitionAnimator dialogTransitionAnimator, Handler mainHandler) {
        mContext = context;
        mDialogFactory = dialogFactory;
        mActivityStarter = activityStarter;
        mKeyguard = keyguard;
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                bind(true);
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
        View root = LayoutInflater.from(c).inflate(R.layout.qs_yuki_power_panel, null);
        root.setAccessibilityPaneTitle(c.getString(R.string.yuki_power_title));
        dialog.setContentView(root);

        mSubtitle = root.findViewById(R.id.yuki_panel_subtitle);
        mHeaderIcon = root.findViewById(R.id.yuki_panel_icon);
        float r = c.getResources().getDisplayMetrics().density * 16;
        float[] rest = {r, r, r, r};
        addChoice(root, R.id.yuki_profile_auto, PartsContract.PROFILE_AUTO, rest);
        addChoice(root, R.id.yuki_profile_balanced, PartsContract.PROFILE_BALANCED, rest);
        addChoice(root, R.id.yuki_profile_battery, PartsContract.PROFILE_BATTERY, rest);
        addChoice(root, R.id.yuki_profile_performance, PartsContract.PROFILE_PERFORMANCE, rest);
        addChoice(root, R.id.yuki_profile_gaming, PartsContract.PROFILE_GAMING, rest);

        mHtsr = new PanelSwitchRow(root.findViewById(R.id.yuki_htsr_row),
                R.drawable.qs_yuki_row_single, R.drawable.ic_yuki_touch, R.string.yuki_power_htsr,
                checked -> runUnlocked(() -> PartsContract.setHtsr(mContext, checked)));

        root.findViewById(R.id.yuki_panel_settings).setOnClickListener(this::openSettings);
        root.findViewById(R.id.yuki_panel_done).setOnClickListener(v -> dialog.dismiss());
    }

    private void addChoice(View root, int id, int profile, float[] rest) {
        PanelChoice choice = new PanelChoice(root.findViewById(id), rest);
        choice.bind(PowerProfileTileText.icon(profile),
                mContext.getString(PowerProfileTileText.name(profile)));
        choice.setOnClickListener(v -> onProfileClicked(v, profile));
        mChoices.put(profile, choice);
    }

    private void onProfileClicked(View view, int profile) {
        if (profile == mShownProfile) return;
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK);
        selectProfile(profile, true);
        runUnlocked(() -> {
            PartsContract.setProfile(mContext, profile);
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        });
    }

    private void selectProfile(int profile, boolean animate) {
        mShownProfile = profile;
        for (Map.Entry<Integer, PanelChoice> e : mChoices.entrySet()) {
            e.getValue().setSelected(e.getKey() == profile, animate);
        }
        mHeaderIcon.setImageResource(PowerProfileTileText.icon(profile));
    }

    /** Applies right away when unlocked; on the lock screen asks for an unlock first. */
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
        Intent intent = new Intent().setComponent(PartsContract.POWER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityTransitionAnimator.Controller controller =
                mDialogTransitionAnimator.createActivityTransitionController(view);
        if (controller == null && mDialog != null) mDialog.dismiss();
        mActivityStarter.postStartActivityDismissingKeyguard(intent, 0, controller);
    }

    @Override
    public void onStart(SystemUIDialog dialog) {
        PartsContract.observePower(mContext.getContentResolver(), mObserver);
        bind(false);
    }

    @Override
    public void onStop(SystemUIDialog dialog) {
        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    private void bind(boolean animate) {
        if (mSubtitle == null) return;
        boolean enabled = PartsContract.isPowerEnabled(mContext);
        int profile = PartsContract.getProfile(mContext);
        if (profile != mShownProfile) selectProfile(profile, animate);
        for (PanelChoice choice : mChoices.values()) choice.setEnabled(enabled);
        PanelText.crossfade(mSubtitle, enabled
                ? PowerProfileTileText.subtitle(mContext, profile,
                        PartsContract.getAutoState(mContext), true)
                : mContext.getString(R.string.yuki_power_off));
        mHtsr.setChecked(PartsContract.isHtsrOn(mContext));
        mHtsr.setEnabled(enabled);
    }
}
