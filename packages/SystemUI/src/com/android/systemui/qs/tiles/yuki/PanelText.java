/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles.yuki;

import android.animation.ValueAnimator;
import android.text.TextUtils;
import android.widget.TextView;

/** Live text in the panels: a short crossfade when it changes, none with animations off. */
final class PanelText {
    private static final long FADE_MS = 150;

    private PanelText() {}

    static void crossfade(TextView view, CharSequence text) {
        if (TextUtils.equals(view.getText(), text)) return;
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow()
                || TextUtils.isEmpty(view.getText())) {
            view.setText(text);
            return;
        }
        view.animate().cancel();
        view.animate().alpha(0.3f).setDuration(FADE_MS / 2).withEndAction(() -> {
            view.setText(text);
            view.animate().alpha(1f).setDuration(FADE_MS / 2).start();
        }).start();
    }
}
