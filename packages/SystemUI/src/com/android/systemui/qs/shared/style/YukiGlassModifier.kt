/*
 * SPDX-FileCopyrightText: 2026 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.shared.style

import android.graphics.Color
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.statusbar.YukiGlassController
import com.android.systemui.statusbar.YukiGlassSnapshot
import kotlin.math.roundToInt

val LocalYukiGlassSnapshot = compositionLocalOf { YukiGlassSnapshot() }

val LocalYukiShadeBlurRadiusPx = compositionLocalOf { 0 }

@Composable
fun rememberYukiGlassSnapshot(context: android.content.Context): YukiGlassSnapshot {
    val controller = remember(context) { YukiGlassController.get(context) }
    val snapshot by controller.state.collectAsStateWithLifecycle()
    return snapshot
}

/**
 * Attaches a RenderEngine material-tagged [BackgroundBlurDrawable] behind a Compose surface
 * (e.g. QS tile) with RenderThread ancestor alpha & clip tracking so animations, scroll clips,
 * and tile shape morphing never produce ghost blur boxes or desynced borders.
 */
fun Modifier.yukiGlassSurface(
    shape: Shape,
    material: Int,
    blurRadiusPx: Int,
    enabled: Boolean,
): Modifier = composed {
    if (!enabled || blurRadiusPx <= 0) {
        return@composed this
    }
    val view: View = LocalView.current
    val drawable = remember(view) {
        runCatching {
            view.viewRootImpl?.createBackgroundBlurDrawable()?.apply {
                setXfermode(null)
                setColor(Color.TRANSPARENT)
                setApplyRenderNodeAlpha(true)
            }
        }.getOrNull()
    }

    if (drawable == null) {
        return@composed this
    }

    DisposableEffect(drawable) {
        onDispose {
            drawable.setBlurRadius(0)
            drawable.setAlpha(0)
            drawable.setVisible(false, false)
        }
    }

    this.drawBehind {
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        if (w <= 0 || h <= 0) {
            drawable.setVisible(false, false)
            return@drawBehind
        }

        val outline = shape.createOutline(size, layoutDirection, this)
        when (outline) {
            is Outline.Rounded -> {
                val rr = outline.roundRect
                drawable.setCornerRadius(
                    rr.topLeftCornerRadius.x,
                    rr.topLeftCornerRadius.y,
                    rr.topRightCornerRadius.x,
                    rr.topRightCornerRadius.y,
                    rr.bottomLeftCornerRadius.x,
                    rr.bottomLeftCornerRadius.y,
                    rr.bottomRightCornerRadius.x,
                    rr.bottomRightCornerRadius.y,
                )
            }
            is Outline.Rectangle -> {
                drawable.setCornerRadius(0f)
            }
            is Outline.Generic -> {
                val fallback = minOf(size.width, size.height) * 0.5f
                drawable.setCornerRadius(fallback)
            }
        }

        drawable.setMaterial(material)
        drawable.setBlurRadius(blurRadiusPx)
        drawable.setAlpha(255)
        drawable.setVisible(true, false)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}
