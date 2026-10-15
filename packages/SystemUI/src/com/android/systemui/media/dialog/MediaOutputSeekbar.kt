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

package com.android.systemui.media.dialog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.android.systemui.res.R
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * A custom seekbar extending [Slider] for the media output dialog device list.
 *
 * Matches the visual design and continuous traveling icon behavior of the volume and
 * brightness sliders:
 * - Inactive track max width adds thumb width (constraints.maxWidth + thumbWidth)
 * - Rounded pill inactive track with full corner radius aligned with dialog margins
 * - Rounded pill active track with matching corner radius (no flat inside corners)
 * - At lowest value (mute), active track retains thumb width pill and active icon color
 * - Traveling icon that moves continuously along the track and never disappears at low volume levels
 * - Correct active / mute icon drawables and tints
 */
class MediaOutputSeekbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.sliderStyle,
) : Slider(context, attrs, defStyleAttr) {

    private val inactiveTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val activeTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val inactiveTrackRect = RectF()
    private val activeTrackRect = RectF()

    var deviceDrawable: Drawable? = null
        set(value) {
            field = value
            invalidate()
        }

    var muteDrawable: Drawable? = null
        set(value) {
            field = value
            invalidate()
        }

    fun setCustomIcons(deviceDrawable: Drawable?, muteDrawable: Drawable?) {
        if (deviceDrawable != null) {
            this.deviceDrawable = deviceDrawable
        }
        if (muteDrawable != null) {
            this.muteDrawable = muteDrawable
        }
        invalidate()
    }

    override fun setTrackIconActiveEnd(icon: Drawable?) {
        super.setTrackIconActiveEnd(icon)
        if (icon != null) {
            deviceDrawable = icon
        }
    }

    override fun setTrackIconInactiveStart(icon: Drawable?) {
        super.setTrackIconInactiveStart(icon)
        if (icon != null) {
            muteDrawable = icon
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        val w = width.toFloat()
        if (w <= 0f) return super.onTouchEvent(event)

        val marginHorizontal = resources.getDimensionPixelSize(R.dimen.media_output_dialog_margin_horizontal).toFloat()
        val trkHeight = if (trackHeight > 0) trackHeight.toFloat() else 48f * resources.displayMetrics.density
        val thumbWidth = trkHeight
        val totalTrackWidth = maxOf(1f, w - 2f * marginHorizontal)
        val travelWidth = maxOf(1f, totalTrackWidth - thumbWidth)
        val trackLeft = marginHorizontal

        val touchX = event.x
        val isRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val rawFraction = (touchX - (trackLeft + thumbWidth / 2f)) / travelWidth
        val fraction = if (isRtl) (1f - rawFraction).coerceIn(0f, 1f) else rawFraction.coerceIn(0f, 1f)

        // Map touch position to BaseSlider's coordinate system
        val baseSidePadding = trackSidePadding.toFloat()
        val baseTrackWidth = if (trackWidth > 0) trackWidth.toFloat() else maxOf(1f, w - 2f * baseSidePadding)
        val mappedX = if (isRtl) {
            baseSidePadding + (1f - fraction) * baseTrackWidth
        } else {
            baseSidePadding + fraction * baseTrackWidth
        }

        val mappedEvent = MotionEvent.obtain(event)
        mappedEvent.setLocation(mappedX, event.y)
        try {
            return super.onTouchEvent(mappedEvent)
        } finally {
            mappedEvent.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val marginHorizontal = resources.getDimensionPixelSize(R.dimen.media_output_dialog_margin_horizontal).toFloat()
        val trkHeight = if (trackHeight > 0) trackHeight.toFloat() else 48f * resources.displayMetrics.density
        val thumbWidth = trkHeight
        val cornerSize = if (trackCornerSize > 0) trackCornerSize.toFloat() else trkHeight * (34f / 49f)
        val icSize = if (trackIconSize > 0) trackIconSize else (24f * resources.displayMetrics.density).roundToInt()

        // Available track width and usable travel distance (constraints.maxWidth on brightness/volume slider)
        val totalTrackWidth = maxOf(0f, w - 2f * marginHorizontal)
        val travelWidth = maxOf(0f, totalTrackWidth - thumbWidth)
        // Max width adds thumb width like on brightness slider: width = constraints.maxWidth + thumbWidthPx
        val trackWidthMax = travelWidth + thumbWidth

        val trackLeft = marginHorizontal
        val trackRight = trackLeft + trackWidthMax
        val yCenter = h / 2f
        val trackTop = yCenter - trkHeight / 2f
        val trackBottom = yCenter + trkHeight / 2f

        // Draw Inactive Track (Full rounded pill with max width)
        val inactiveColor = trackInactiveTintList?.getColorForState(
            drawableState,
            trackInactiveTintList?.defaultColor ?: Color.GRAY
        ) ?: Color.GRAY
        inactiveTrackPaint.color = inactiveColor
        inactiveTrackRect.set(trackLeft, trackTop, trackRight, trackBottom)
        canvas.drawRoundRect(inactiveTrackRect, cornerSize, cornerSize, inactiveTrackPaint)

        // Calculate progress fraction
        val range = valueTo - valueFrom
        val fraction = if (range > 0f) ((value - valueFrom) / range).coerceIn(0f, 1f) else 0f
        val isRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL

        // Active track width: adds thumb width like on volume and brightness sliders
        val progressWidth = fraction * travelWidth
        val activeTrackWidth = progressWidth + thumbWidth

        // Draw Active Track (Rounded pill from start up to icon, at least thumbWidth wide)
        val activeColor = trackActiveTintList?.getColorForState(
            drawableState,
            trackActiveTintList?.defaultColor ?: Color.BLUE
        ) ?: Color.BLUE
        activeTrackPaint.color = activeColor

        if (isRtl) {
            activeTrackRect.set(trackRight - activeTrackWidth, trackTop, trackRight, trackBottom)
        } else {
            activeTrackRect.set(trackLeft, trackTop, trackLeft + activeTrackWidth, trackBottom)
        }
        canvas.drawRoundRect(activeTrackRect, cornerSize, cornerSize, activeTrackPaint)

        // Draw Traveling Icon at the leading end of active track
        val iconBoxLeft = if (isRtl) {
            trackRight - activeTrackWidth
        } else {
            trackLeft + progressWidth
        }
        val iconBoxRight = iconBoxLeft + thumbWidth

        val iconDrawable = if (fraction == 0f) {
            muteDrawable ?: trackIconInactiveStart ?: deviceDrawable ?: trackIconActiveEnd
        } else {
            deviceDrawable ?: trackIconActiveEnd
        }

        if (iconDrawable != null) {
            val iconColor = trackIconActiveColor?.getColorForState(
                drawableState,
                trackIconActiveColor?.defaultColor ?: Color.WHITE
            ) ?: Color.WHITE

            val iconLeft = (iconBoxLeft + (thumbWidth - icSize) / 2f).roundToInt()
            val iconTop = (trackTop + (trkHeight - icSize) / 2f).roundToInt()
            val iconRight = iconLeft + icSize
            val iconBottom = iconTop + icSize

            val mutated = iconDrawable.mutate()
            mutated.setTint(iconColor)
            mutated.setBounds(iconLeft, iconTop, iconRight, iconBottom)
            mutated.draw(canvas)
        }
    }
}
