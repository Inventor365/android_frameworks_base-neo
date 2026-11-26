/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles.yuki;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import com.android.app.animation.Interpolators;
import com.android.systemui.res.R;

/**
 * One choice of a Power profile or Charging panel: a profile button or a segment of a connected
 * group. Selecting it morphs its corners from the grouped shape to fully rounded while the fill
 * crossfades from the tonal surface to primary, with SystemUI's emphasized easing. Honours
 * "Remove animations"; TalkBack reads it as a radio button with its state.
 */
final class PanelChoice {
    private static final long DURATION_MS = 350;

    private final View mView;
    private final ImageView mIcon;
    private final TextView mLabel;
    private final View mCheck;
    private final GradientDrawable mShape = new GradientDrawable();
    private final float[] mRestRadii;
    private final int mBg, mBgSelected, mFg, mFgSelected;
    private final android.graphics.Typeface mTypeface;
    private ValueAnimator mAnimator;
    private float mProgress = -1f;
    private boolean mSelected;

    /**
     * @param restRadii the grouped corners (top-left, top-right, bottom-right, bottom-left), px.
     */
    PanelChoice(View view, float[] restRadii) {
        mView = view;
        mIcon = view.findViewById(R.id.yuki_choice_icon);
        mLabel = view.findViewById(R.id.yuki_choice_label);
        mCheck = view.findViewById(R.id.yuki_choice_check);
        mRestRadii = restRadii;
        mTypeface = mLabel.getTypeface();
        Context c = view.getContext();
        mBg = c.getColor(com.android.internal.R.color.materialColorSurfaceContainerHighest);
        mBgSelected = c.getColor(com.android.internal.R.color.materialColorPrimary);
        mFg = c.getColor(com.android.internal.R.color.materialColorOnSurface);
        mFgSelected = c.getColor(com.android.internal.R.color.materialColorOnPrimary);

        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.colorControlHighlight, tv, true);
        ColorStateList ripple = tv.resourceId != 0 ? c.getColorStateList(tv.resourceId)
                : ColorStateList.valueOf(tv.data);
        view.setBackground(new RippleDrawable(ripple, mShape, null));
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(RadioButton.class.getName());
                info.setCheckable(true);
                info.setChecked(mSelected);
            }
        });
        apply(0f);
        // "Fully rounded" depends on the height, known only after layout.
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((b - t) != (ob - ot) && (mAnimator == null || !mAnimator.isRunning())) {
                apply(Math.max(0f, mProgress));
            }
        });
    }

    View getView() {
        return mView;
    }

    void bind(int iconRes, CharSequence label) {
        if (mIcon != null) mIcon.setImageResource(iconRes);
        mLabel.setText(label);
    }

    void setOnClickListener(View.OnClickListener listener) {
        mView.setOnClickListener(listener);
    }

    void setEnabled(boolean enabled) {
        mView.setEnabled(enabled);
        mView.setAlpha(enabled ? 1f : 0.38f);
    }

    boolean isSelected() {
        return mSelected;
    }

    void setSelected(boolean selected, boolean animate) {
        if (selected == mSelected && mProgress >= 0f) return;
        mSelected = selected;
        mView.setSelected(selected);
        mLabel.setTypeface(mTypeface, selected ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        if (mCheck != null) mCheck.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        float target = selected ? 1f : 0f;
        if (mAnimator != null) mAnimator.cancel();
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || !mView.isAttachedToWindow()) {
            apply(target);
            return;
        }
        mAnimator = ValueAnimator.ofFloat(Math.max(0f, mProgress), target);
        mAnimator.setDuration(DURATION_MS);
        mAnimator.setInterpolator(Interpolators.EMPHASIZED);
        mAnimator.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
        mAnimator.start();
    }

    private void apply(float f) {
        mProgress = f;
        // Fully rounded is half the height; before layout the resting shape stands in.
        float full = mView.getHeight() > 0 ? mView.getHeight() / 2f : mRestRadii[0];
        float[] radii = new float[8];
        for (int i = 0; i < 4; i++) {
            float r = mRestRadii[i] + (full - mRestRadii[i]) * f;
            radii[i * 2] = r;
            radii[i * 2 + 1] = r;
        }
        mShape.setCornerRadii(radii);
        ArgbEvaluator e = new ArgbEvaluator();
        mShape.setColor((int) e.evaluate(f, mBg, mBgSelected));
        int fg = (int) e.evaluate(f, mFg, mFgSelected);
        mLabel.setTextColor(fg);
        if (mIcon != null) mIcon.setImageTintList(ColorStateList.valueOf(fg));
        if (mCheck instanceof ImageView check) check.setImageTintList(ColorStateList.valueOf(fg));
    }
}
