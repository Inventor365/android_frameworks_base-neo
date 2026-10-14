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

package com.android.systemui.volume.dialog.sliders.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
fun SliderTrack(
    sliderState: SliderState,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
    colors: SliderColors = SliderDefaults.colors(),
    thumbTrackGapSize: Dp = 0.dp,
    trackCornerSize: Dp = 33.3.dp,
    trackInsideCornerSize: Dp = 2.dp,
    trackSize: Dp = 48.dp,
    isVertical: Boolean = false,
    isReverseDirection: Boolean = isVertical,
    icon: (@Composable BoxScope.() -> Unit)? = null,
    activeTrackStartIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
    activeTrackEndIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
    inactiveTrackStartIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
    inactiveTrackEndIcon: (@Composable BoxScope.(iconsState: SliderIconsState) -> Unit)? = null,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current

    val activeTrackColor =
        if (isEnabled) colors.activeTrackColor else colors.disabledActiveTrackColor
    val inactiveTrackColor =
        if (isEnabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor
    val iconColor =
        if (isEnabled) colors.activeTickColor else colors.disabledActiveTickColor

    val resolvedIcon: (@Composable BoxScope.() -> Unit)? =
        icon ?: activeTrackEndIcon?.let { { it(DefaultSliderIconsState) } }

    val trackModifier =
        modifier
            .then(
                if (isVertical) {
                    Modifier.fillMaxHeight().width(trackSize)
                } else {
                    Modifier.fillMaxWidth().height(trackSize)
                }
            )
            .clip(RoundedCornerShape(trackCornerSize))

    val iconAlignment =
        when {
            isVertical && isReverseDirection -> Alignment.TopCenter
            isVertical && !isReverseDirection -> Alignment.BottomCenter
            !isVertical && isRtl -> Alignment.CenterStart
            else -> Alignment.CenterEnd
        }

    Layout(
        modifier = trackModifier,
        content = {
            // Measurable 0: Inactive track
            Box(
                Modifier
                    .clip(RoundedCornerShape(trackCornerSize))
                    .background(inactiveTrackColor)
            )

            // Measurable 1: Active track with icon at leading edge
            Box(
                Modifier
                    .clip(RoundedCornerShape(trackCornerSize))
                    .background(activeTrackColor),
                contentAlignment = iconAlignment,
            ) {
                if (resolvedIcon != null) {
                    Box(
                        modifier = Modifier.size(trackSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides iconColor) {
                            resolvedIcon()
                        }
                    }
                }
            }
        },
        measurePolicy = { measurables, constraints ->
            val trackSizePx = with(density) { trackSize.roundToPx() }
            val thumbSizePx = trackSizePx

            if (isVertical) {
                val height = constraints.maxHeight + thumbSizePx
                val trackHeight =
                    (sliderState.coercedValueAsFraction * constraints.maxHeight).toInt()
                val activeTrackHeight =
                    trackHeight + thumbSizePx - if (trackHeight == 0) trackHeight else 0

                val inactiveTrackPlaceable =
                    measurables[0].measure(Constraints.fixed(trackSizePx, height))
                val activeTrackPlaceable =
                    measurables[1].measure(Constraints.fixed(trackSizePx, activeTrackHeight))

                layout(trackSizePx, height) {
                    inactiveTrackPlaceable.place(0, 0)
                    val activeY = if (isReverseDirection) height - activeTrackHeight else 0
                    activeTrackPlaceable.place(0, activeY)
                }
            } else {
                val width = constraints.maxWidth + thumbSizePx
                val trackWidth =
                    (sliderState.coercedValueAsFraction * constraints.maxWidth).toInt()
                val activeTrackWidth =
                    trackWidth + thumbSizePx - if (trackWidth == 0) trackWidth else 0

                val inactiveTrackPlaceable =
                    measurables[0].measure(Constraints.fixed(width, trackSizePx))
                val activeTrackPlaceable =
                    measurables[1].measure(Constraints.fixed(activeTrackWidth, trackSizePx))

                layout(width, trackSizePx) {
                    inactiveTrackPlaceable.place(0, 0)
                    val activeX = if (isRtl) width - activeTrackWidth else 0
                    activeTrackPlaceable.place(activeX, 0)
                }
            }
        },
    )
}

/** Provides visibility state for each of the Slider's icons for backward compatibility. */
interface SliderIconsState {
    val isActiveTrackStartIconVisible: Boolean
    val isActiveTrackEndIconVisible: Boolean
    val isInactiveTrackStartIconVisible: Boolean
    val isInactiveTrackEndIconVisible: Boolean
}

private object DefaultSliderIconsState : SliderIconsState {
    override val isActiveTrackStartIconVisible: Boolean = false
    override val isActiveTrackEndIconVisible: Boolean = true
    override val isInactiveTrackStartIconVisible: Boolean = false
    override val isInactiveTrackEndIconVisible: Boolean = false
}
