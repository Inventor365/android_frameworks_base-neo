/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.shade.ui

import android.content.Context
import com.android.internal.graphics.ColorUtils
import com.android.systemui.res.R

object ShadeColors {
    /**
     * Calculate notification shade panel color.
     *
     * @param context Context to resolve colors.
     * @param blurSupported Whether blur is enabled (can be off due to battery saver)
     * @param withScrim Whether to composite a scrim when blur is enabled (used by legacy shade).
     * @return color for the shade panel.
     */
    @JvmStatic
    fun shadePanel(context: Context, blurSupported: Boolean, withScrim: Boolean): Int {
        val glass = com.android.systemui.statusbar.YukiGlassController.get(context).current
        val mode =
            if (blurSupported) glass.effectiveShadeMode
            else com.android.internal.util.neoteric.YukiGlassConfig.MODE_OFF
        if (mode == com.android.internal.util.neoteric.YukiGlassConfig.MODE_OFF) {
            return shadePanelFallback(context)
        }
        val dark =
            (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val frostFactor = (glass.frostPercent / 100f).coerceIn(0f, 1f)
        if (mode == com.android.internal.util.neoteric.YukiGlassConfig.MODE_LIQUID) {
            val alpha =
                if (dark) {
                    ((0.18f + 0.24f * frostFactor) * 255f).toInt().coerceIn(28, 128)
                } else {
                    ((0.16f + 0.22f * frostFactor) * 255f).toInt().coerceIn(24, 118)
                }
            val base = if (dark) 0xFF0A0D12.toInt() else 0xFFF2F5FA.toInt()
            return ColorUtils.setAlphaComponent(base, alpha)
        }
        if (mode == com.android.internal.util.neoteric.YukiGlassConfig.MODE_FROSTED) {
            val alpha = ((0.06f + 0.14f * frostFactor) * 255f).toInt().coerceIn(12, 64)
            val base = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            return ColorUtils.setAlphaComponent(base, alpha)
        }
        return if (withScrim) {
            ColorUtils.compositeColors(
                shadePanelStandard(context),
                shadePanelScrimBehind(context),
            )
        } else {
            shadePanelStandard(context)
        }
    }

    @JvmStatic
    fun notificationScrim(context: Context, blurSupported: Boolean): Int {
        val glass = com.android.systemui.statusbar.YukiGlassController.get(context).current
        val mode =
            if (blurSupported) glass.effectiveShadeMode
            else com.android.internal.util.neoteric.YukiGlassConfig.MODE_OFF
        if (mode == com.android.internal.util.neoteric.YukiGlassConfig.MODE_OFF) {
            return notificationScrimFallback(context)
        }
        if (mode == com.android.internal.util.neoteric.YukiGlassConfig.MODE_LIQUID) {
            val alpha = if (glass.dualTone) (0.14f * 255f).toInt() else (0.18f * 255f).toInt()
            return ColorUtils.setAlphaComponent(
                context.getColor(R.color.notification_scrim_base),
                alpha,
            )
        }
        if (mode == com.android.internal.util.neoteric.YukiGlassConfig.MODE_FROSTED) {
            return ColorUtils.setAlphaComponent(
                context.getColor(R.color.notification_scrim_base),
                (0.12f * 255f).toInt(),
            )
        }
        return notificationScrimStandard(context)
    }

    @JvmStatic
    fun shadePanelScrimBehind(context: Context): Int {
        return context.resources.getColor(
            com.android.internal.R.color.shade_panel_scrim,
            context.theme,
        )
    }

    /**
     * Background color of the rounded cornered rect behind Notifications in Single- or SplitShade.
     */
    @JvmStatic
    fun classicShadeNotificationScrimBg(
        context: Context,
        blurSupported: Boolean,
        composited: Boolean,
    ): Int {
        val surfaceEffect0Color =
            context.resources.getColor(
                com.android.internal.R.color.customColorSurfaceEffect0,
                context.theme,
            )
        if (!composited) {
            // SplitShade (avoid adding the shade panel with scrim)
            return surfaceEffect0Color
        }
        // SingleShade
        val shadePanelWithScrim = shadePanel(context, blurSupported, withScrim = true)
        return ColorUtils.compositeColors(surfaceEffect0Color, shadePanelWithScrim)
    }

    @JvmStatic
    private fun shadePanelStandard(context: Context): Int {
        val layerAbove =
            context.resources.getColor(com.android.internal.R.color.shade_panel_fg, context.theme)
        val layerBelow =
            context.resources.getColor(com.android.internal.R.color.shade_panel_bg, context.theme)
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    @JvmStatic
    private fun shadePanelFallback(context: Context): Int {
        return context.getColor(R.color.shade_panel_fallback)
    }

    @JvmStatic
    private fun notificationScrimStandard(context: Context): Int {
        return ColorUtils.setAlphaComponent(
            context.getColor(R.color.notification_scrim_base),
            (0.5f * 255).toInt(),
        )
    }

    @JvmStatic
    private fun notificationScrimFallback(context: Context): Int {
        return context.getColor(R.color.notification_scrim_fallback)
    }
}
