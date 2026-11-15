/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.statusbar.notification.stack

import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.compose.ui.platform.ComposeView
import com.android.systemui.media.remedia.ui.compose.Media
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.row.ExpandableView

/** Root view to insert Lock screen media controls into the notification stack. */
class MediaContainerView(context: Context, attrs: AttributeSet?) : ExpandableView(context, attrs) {

    override var clipHeight = 0
    var cornerRadius = 0f
    var clipRect = RectF()
    var clipPath = Path()

    init {
        setWillNotDraw(false) // Run onDraw after invalidate.
        updateResources()
    }

    override fun getIntrinsicHeight(): Int {
        if (visibility == GONE) {
            return 0
        }
        val composeChildHeight =
            if (childCount == 1 && getChildAt(0) is ComposeView) {
                getChildAt(0).measuredHeight
            } else {
                0
            }
        val defaultHeight =
            (Media.DEFAULT_HEIGHT.value * context.resources.displayMetrics.density).toInt()
        val fallbackHeight =
            if (childCount == 1 && getChildAt(0) is ComposeView) defaultHeight else 0
        return maxOf(super.getIntrinsicHeight(), composeChildHeight, fallbackHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        if (visibility != GONE && childCount == 1 && getChildAt(0) is ComposeView) {
            val composeChild = getChildAt(0) as ComposeView
            val minHeight = maxOf(composeChild.measuredHeight, intrinsicHeight)
            if (minHeight > 0) {
                if (composeChild.measuredHeight < minHeight) {
                    val childWidthSpec =
                        MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
                    val childHeightSpec =
                        MeasureSpec.makeMeasureSpec(minHeight, MeasureSpec.EXACTLY)
                    composeChild.measure(childWidthSpec, childHeightSpec)
                }
                if (measuredHeight < minHeight) {
                    setMeasuredDimension(measuredWidth, minHeight)
                }
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        if (visibility != GONE && childCount == 1 && getChildAt(0) is ComposeView) {
            val composeChild = getChildAt(0) as ComposeView
            val minHeight = maxOf(composeChild.measuredHeight, intrinsicHeight)
            if (minHeight > 0) {
                val width = right - left
                if (composeChild.width != width || composeChild.height != minHeight) {
                    composeChild.layout(0, 0, width, minHeight)
                }
                if (actualHeight < minHeight) {
                    setActualHeight(minHeight, false)
                }
            }
        }
    }

    override fun requestLayout() {
        super.requestLayout()

        if (visibility != GONE && childCount == 1 && getChildAt(0) is ComposeView) {
            val targetHeight = intrinsicHeight
            if (targetHeight > 0 && actualHeight != targetHeight) {
                setActualHeight(targetHeight, false)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    private fun updateResources() {
        cornerRadius =
            context.resources.getDimensionPixelSize(R.dimen.notification_corner_radius).toFloat()
    }

    public override fun updateClipping() {
        val targetClip = if (actualHeight > 0) actualHeight else intrinsicHeight
        if (clipHeight != targetClip) {
            clipHeight = targetClip
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val effectiveClipHeight =
            if (clipHeight > 0) clipHeight else actualHeight.takeIf { it > 0 } ?: intrinsicHeight
        if (effectiveClipHeight > 0) {
            val bounds = canvas.clipBounds
            bounds.bottom = effectiveClipHeight
            clipRect.set(bounds)

            clipPath.reset()
            clipPath.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)
        }
    }

    override fun performRemoveAnimation(
        duration: Long,
        delay: Long,
        translationDirection: Float,
        isHeadsUpAnimation: Boolean,
        isHeadsUpCycling: Boolean,
        onStartedRunnable: Runnable?,
        onFinishedRunnable: Runnable?,
        animationListener: AnimatorListenerAdapter?,
        clipSide: ClipSide,
    ): Long {
        return 0
    }

    override fun performAddAnimation(
        delay: Long,
        duration: Long,
        isHeadsUpAppear: Boolean,
        isHeadsUpCycling: Boolean,
        onEnd: Runnable?,
    ) {
        // No animation, it doesn't need it, this would be local
    }

    override fun setVisibility(visibility: Int) {
        if (isVisibilityValid(visibility)) {
            super.setVisibility(visibility)
        }

        assertMediaContainerVisibility(visibility)
    }

    /** visibility should be aligned with MediaContainerView visibility on the keyguard. */
    private fun isVisibilityValid(visibility: Int): Boolean {
        val currentViewState = viewState as? MediaContainerViewState ?: return true
        val shouldBeGone = !currentViewState.shouldBeVisible
        return if (shouldBeGone) visibility == GONE else visibility != GONE
    }

    /**
     * b/298213983 MediaContainerView's visibility is changed to VISIBLE when it should be GONE.
     * This method check this state and logs.
     */
    private fun assertMediaContainerVisibility(visibility: Int) {
        val currentViewState = viewState

        if (currentViewState is MediaContainerViewState) {
            if (!currentViewState.shouldBeVisible && visibility == VISIBLE) {
                Log.wtf(
                    "MediaContainerView",
                    "MediaContainerView should be GONE " + "but its visibility changed to VISIBLE",
                )
            }
        }
    }

    fun setKeyguardVisibility(isVisible: Boolean) {
        val currentViewState = viewState
        if (currentViewState is MediaContainerViewState) {
            currentViewState.shouldBeVisible = isVisible
        }

        visibility = if (isVisible) VISIBLE else GONE
    }

    override fun createExpandableViewState(): ExpandableViewState = MediaContainerViewState()

    class MediaContainerViewState : ExpandableViewState() {
        var shouldBeVisible: Boolean = false

        override fun copyFrom(viewState: ViewState) {
            super.copyFrom(viewState)
            if (viewState is MediaContainerViewState) {
                shouldBeVisible = viewState.shouldBeVisible
            }
        }
    }
}
