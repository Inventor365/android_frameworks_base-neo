/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.view.ViewGroup;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.AlphaOptimizedButton;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;

public class FooterViewButton extends AlphaOptimizedButton {
    private Drawable mSurfaceBackground;
    private BackgroundBlurDrawable mBlurDrawable;
    private LayerDrawable mBlurBackground;
    private int mBlurColor;
    private boolean mBlurRequested;
    private float mBlurRegionAlpha = 1f;

    public FooterViewButton(Context context) {
        this(context, null);
    }

    public FooterViewButton(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FooterViewButton(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public FooterViewButton(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /** Uses the notification surface color on a blur region while preserving the button ripple. */
    public void setBackgroundWithBlur(Drawable surfaceBackground, boolean blurRequested,
            int blurColor) {
        mSurfaceBackground = surfaceBackground;
        mBlurRequested = blurRequested;
        mBlurColor = blurColor;
        updateBlurBackground();
    }

    /** Background blur regions ignore ancestor alpha, so apply it directly. */
    public void setBlurRegionAlpha(float alpha) {
        float constrainedAlpha = Math.max(0f, Math.min(1f, alpha));
        if (mBlurRegionAlpha != constrainedAlpha) {
            mBlurRegionAlpha = constrainedAlpha;
            updateBlurVisibility();
        }
    }

    private void updateBlurBackground() {
        if (mBlurDrawable != null) {
            mBlurDrawable.setAlpha(0);
            mBlurDrawable = null;
            mBlurBackground = null;
        }
        if (!mBlurRequested || !isAttachedToWindow() || getViewRootImpl() == null) {
            setBackground(mSurfaceBackground);
            return;
        }

        mBlurDrawable = getViewRootImpl().createBackgroundBlurDrawable();
        mBlurDrawable.setXfermode(null);
        mBlurDrawable.setColor(mBlurColor);
        mBlurDrawable.setBlurRadius(getResources().getDimensionPixelSize(
                R.dimen.notification_background_blur_radius));
        mBlurDrawable.setCornerRadius(getResources().getDimension(
                R.dimen.notification_footer_button_background_radius));
        mBlurDrawable.setAlpha(0);

        mBlurBackground = new LayerDrawable(new Drawable[] {
                mBlurDrawable, getContext().getDrawable(R.drawable.notif_footer_btn_blur_ripple)
        });
        mBlurBackground.setPaddingMode(LayerDrawable.PADDING_MODE_STACK);
        int inset = getResources().getDimensionPixelSize(
                R.dimen.notification_footer_button_background_inset);
        mBlurBackground.setLayerInset(0, 0, inset, 0, inset);
        updateBlurVisibility();
    }

    private void updateBlurVisibility() {
        if (mBlurDrawable != null) {
            int blurAlpha = isShown() ? Math.round(255 * mBlurRegionAlpha) : 0;
            if (blurAlpha > 0) {
                if (getBackground() != mBlurBackground) {
                    setBackground(mBlurBackground);
                }
                mBlurDrawable.setAlpha(blurAlpha);
            } else {
                mBlurDrawable.setAlpha(0);
                if (getBackground() != mSurfaceBackground) {
                    setBackground(mSurfaceBackground);
                }
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mBlurRequested) {
            updateBlurBackground();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mBlurDrawable != null) {
            mBlurDrawable.setAlpha(0);
            mBlurDrawable = null;
            mBlurBackground = null;
            setBackground(mSurfaceBackground);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        updateBlurVisibility();
    }

    /**
     * This method returns the drawing rect for the view which is different from the regular
     * drawing rect, since we layout all children in the {@link NotificationStackScrollLayout} at
     * position 0 and usually the translation is neglected. The standard implementation doesn't
     * account for translation.
     *
     * @param outRect The (scrolled) drawing bounds of the view.
     */
    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        float translationX = ((ViewGroup) mParent).getTranslationX();
        float translationY = ((ViewGroup) mParent).getTranslationY();
        outRect.left += translationX;
        outRect.right += translationX;
        outRect.top += translationY;
        outRect.bottom += translationY;
    }
}
