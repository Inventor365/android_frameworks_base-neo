/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.android.systemui.broadcast.BroadcastDispatcher;
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
import com.android.systemui.qs.tiles.yuki.ChargingTileState;
import com.android.systemui.qs.tiles.yuki.ChargingPanelDelegate;
import com.android.systemui.qs.tiles.yuki.PartsContract;
import com.android.systemui.res.R;
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Charging (Yuki Charging Service). Parts owns the charging mode, bypass and battery care
 * (PartsContract); the tile shows the mode and live power and opens a panel that asks Parts for
 * changes. Exists only where Parts provides charging control.
 */
public class ChargingTile extends QSTileImpl<QSTile.State> {

    public static final String TILE_SPEC = "charging_control";
    private static final String INTERACTION_JANK_TAG = "charging_control";

    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final KeyguardStateController mKeyguard;
    private final SystemUIDialog.Factory mDialogFactory;
    private final ShadeDialogContextInteractor mShadeDialogContext;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Executor mBackgroundExecutor;
    private final ContentObserver mObserver;
    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState();
        }
    };
    private SystemUIDialog mDialog;
    private String mLastMode;
    private boolean mListening;

    @Inject
    public ChargingTile(
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
            ShadeDialogContextInteractor shadeDialogContextInteractor,
            BroadcastDispatcher broadcastDispatcher,
            @Background Executor backgroundExecutor) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager,
                metricsLogger, statusBarStateController, activityStarter, qsLogger);
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mKeyguard = keyguardStateController;
        mDialogFactory = dialogFactory;
        mShadeDialogContext = shadeDialogContextInteractor;
        mBroadcastDispatcher = broadcastDispatcher;
        mBackgroundExecutor = backgroundExecutor;
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
        return mContext.getString(R.string.yuki_charging_title);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent().setComponent(PartsContract.CHARGING_SETTINGS);
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        mUiHandler.post(() -> showPanel(expandable));
    }

    private void showPanel(@Nullable Expandable expandable) {
        if (mDialog != null && mDialog.isShowing()) return;
        ChargingPanelDelegate delegate = new ChargingPanelDelegate(
                mShadeDialogContext.getContext(), mDialogFactory, mActivityStarter, mKeyguard,
                mDialogTransitionAnimator, mUiHandler, mBackgroundExecutor);
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
        // Runs on the background looper: the battery reading makes binder calls.
        ChargingTileState tile = ChargingTileState.read(mContext, mLastMode);
        state.label = mContext.getString(R.string.yuki_charging_title);
        state.secondaryLabel = tile.secondaryLabel;
        state.state = tile.active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.icon = tile.animatedIconRes != 0
                ? new AnimationIcon(tile.animatedIconRes, tile.iconRes)
                : ResourceIcon.get(tile.iconRes);
        state.contentDescription = state.label;
        state.stateDescription = state.secondaryLabel;
        mLastMode = tile.mode;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            PartsContract.observeCharging(mContext.getContentResolver(), mObserver);
            // Delivered on main; refreshState() only posts to the tile's background looper.
            mBroadcastDispatcher.registerReceiver(mBatteryReceiver,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            refreshState();
        } else {
            stopListening();
        }
    }

    private void stopListening() {
        mContext.getContentResolver().unregisterContentObserver(mObserver);
        mBroadcastDispatcher.unregisterReceiver(mBatteryReceiver);
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        if (mListening) {
            stopListening();
            mListening = false;
        }
    }
}
