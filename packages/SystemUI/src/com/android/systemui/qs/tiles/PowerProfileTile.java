/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.yuki.PartsContract;
import com.android.systemui.qs.tiles.yuki.PowerProfilePanelDelegate;
import com.android.systemui.qs.tiles.yuki.PowerProfileTileText;
import com.android.systemui.res.R;
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

/**
 * Power profile. The device's Parts app owns the profile (PartsContract); this tile shows it and
 * opens a panel that asks Parts for changes. Exists only where Parts provides Power profile.
 */
public class PowerProfileTile extends QSTileImpl<QSTile.State> {

    public static final String TILE_SPEC = "power_profile";
    private static final String INTERACTION_JANK_TAG = "power_profile";

    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final KeyguardStateController mKeyguard;
    private final SystemUIDialog.Factory mDialogFactory;
    private final ShadeDialogContextInteractor mShadeDialogContext;
    private final ContentObserver mObserver;
    private SystemUIDialog mDialog;
    private int mLastProfile = Integer.MIN_VALUE;
    private boolean mListening;

    @Inject
    public PowerProfileTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            DialogTransitionAnimator dialogTransitionAnimator,
            KeyguardStateController keyguardStateController,
            SystemUIDialog.Factory dialogFactory,
            ShadeDialogContextInteractor shadeDialogContextInteractor) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager,
                metricsLogger, statusBarStateController, activityStarter, qsLogger);
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mKeyguard = keyguardStateController;
        mDialogFactory = dialogFactory;
        mShadeDialogContext = shadeDialogContextInteractor;
        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                refreshState();
            }
        };
    }

    @Override
    public boolean isAvailable() {
        return PartsContract.isAvailable(mContext);
    }

    @Override
    public QSTile.State newTileState() {
        return new QSTile.State();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.yuki_power_title);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent().setComponent(PartsContract.POWER_SETTINGS);
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        if (!PartsContract.isPowerEnabled(mContext)) {
            // Nothing to pick while it is off: the switch lives in Settings.
            mActivityStarter.postStartActivityDismissingKeyguard(getLongClickIntent(), 0);
            return;
        }
        mUiHandler.post(() -> showPanel(expandable));
    }

    private void showPanel(@Nullable Expandable expandable) {
        if (mDialog != null && mDialog.isShowing()) return;
        PowerProfilePanelDelegate delegate = new PowerProfilePanelDelegate(
                mShadeDialogContext.getContext(), mDialogFactory, mActivityStarter, mKeyguard,
                mDialogTransitionAnimator, mUiHandler);
        mDialog = delegate.createDialog();
        if (expandable != null && !mKeyguard.isShowing()) {
            DialogTransitionAnimator.Controller controller = expandable.dialogTransitionController(
                    new DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                            INTERACTION_JANK_TAG));
            if (controller != null) {
                mDialogTransitionAnimator.show(mDialog, controller);
                return;
            }
        }
        mDialog.show();
    }

    @Override
    protected void handleUpdateState(QSTile.State state, Object arg) {
        state.label = mContext.getString(R.string.yuki_power_title);
        if (!PartsContract.isPowerEnabled(mContext)) {
            state.state = Tile.STATE_UNAVAILABLE;
            state.secondaryLabel = mContext.getString(R.string.yuki_power_off);
            state.icon = ResourceIcon.get(R.drawable.ic_yuki_profile_balanced);
            state.contentDescription = state.label;
            state.stateDescription = state.secondaryLabel;
            mLastProfile = Integer.MIN_VALUE;
            return;
        }
        int profile = PartsContract.getProfile(mContext);
        state.state = Tile.STATE_ACTIVE;
        state.secondaryLabel = PowerProfileTileText.subtitle(mContext, profile,
                PartsContract.getAutoState(mContext), false);
        // The sparkle turns in when Auto takes over; otherwise the profile's own icon.
        boolean animate = profile == PartsContract.PROFILE_AUTO
                && mLastProfile != Integer.MIN_VALUE && mLastProfile != profile;
        state.icon = animate
                ? new AnimationIcon(R.drawable.ic_yuki_profile_auto_anim,
                        R.drawable.ic_yuki_profile_auto)
                : ResourceIcon.get(PowerProfileTileText.icon(profile));
        state.contentDescription = state.label;
        state.stateDescription = state.secondaryLabel;
        mLastProfile = profile;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            PartsContract.observePower(mContext.getContentResolver(), mObserver);
            refreshState();
        } else {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
        }
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        if (mListening) {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
            mListening = false;
        }
    }
}
