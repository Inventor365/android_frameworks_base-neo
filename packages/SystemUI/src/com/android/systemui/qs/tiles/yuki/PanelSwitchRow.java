/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles.yuki;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import com.android.systemui.res.R;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * A switch row of a panel (qs_yuki_switch_row). The whole row is the switch: one touch target,
 * one TalkBack node read as a switch with its title, summary and state. A blocked row (for
 * example Bypass without a charger) answers a tap with the reject haptic instead of toggling.
 */
final class PanelSwitchRow {
    interface OnToggleListener {
        void onToggle(boolean checked);
    }

    private final View mRow;
    private final TextView mSummary;
    private final MaterialSwitch mSwitch;
    private boolean mBlocked;

    PanelSwitchRow(View row, int backgroundRes, int iconRes, int titleRes,
            OnToggleListener listener) {
        mRow = row;
        Context c = row.getContext();
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.colorControlHighlight, tv, true);
        ColorStateList ripple = tv.resourceId != 0 ? c.getColorStateList(tv.resourceId)
                : ColorStateList.valueOf(tv.data);
        row.setBackground(new RippleDrawable(ripple, c.getDrawable(backgroundRes), null));
        ((ImageView) row.findViewById(R.id.yuki_row_icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.yuki_row_title)).setText(titleRes);
        mSummary = row.findViewById(R.id.yuki_row_summary);
        mSwitch = row.findViewById(R.id.yuki_row_switch);
        mSwitch.setClickable(false);
        mSwitch.setFocusable(false);
        mSwitch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            if (mBlocked) {
                v.performHapticFeedback(HapticFeedbackConstants.REJECT);
                return;
            }
            boolean checked = !mSwitch.isChecked();
            mSwitch.setChecked(checked);
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            listener.onToggle(checked);
        });
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(mSwitch.isChecked());
            }
        });
    }

    /** Shows Parts' state; never calls the toggle listener. */
    void setChecked(boolean checked) {
        mSwitch.setChecked(checked);
    }

    boolean isChecked() {
        return mSwitch.isChecked();
    }

    /** Hidden when empty. */
    void setSummary(CharSequence summary) {
        boolean show = summary != null && summary.length() > 0;
        mSummary.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) mSummary.setText(summary);
    }

    /** A blocked row stays focusable and explains itself; the switch shows as disabled. */
    void setBlocked(boolean blocked) {
        mBlocked = blocked;
        mSwitch.setEnabled(!blocked);
    }

    void setEnabled(boolean enabled) {
        mRow.setEnabled(enabled);
        mSwitch.setEnabled(enabled && !mBlocked);
        mRow.setAlpha(enabled ? 1f : 0.38f);
    }
}
