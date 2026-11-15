package com.android.systemui.statusbar.notification.stack

import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for {@link MediaContainView}.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class MediaContainerViewTest : SysuiTestCase() {

    lateinit var mediaContainerView : MediaContainerView

    @Before
    fun setUp() {
        mediaContainerView = LayoutInflater.from(context).inflate(
                R.layout.keyguard_media_container, null, false) as MediaContainerView
    }

    @Test
    fun testUpdateClipping_updatesClipHeight() {
        assertTrue(mediaContainerView.clipHeight == 0)

        mediaContainerView.setFinalActualHeight(10)
        mediaContainerView.updateClipping()
        assertTrue(mediaContainerView.clipHeight == 10)
    }

    @Test
    fun testIntrinsicHeight_whenGone_returnsZero() {
        val composeView = ComposeView(context)
        mediaContainerView.addView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        mediaContainerView.visibility = View.GONE
        assertThat(mediaContainerView.intrinsicHeight).isEqualTo(0)
    }

    @Test
    fun testIntrinsicHeight_whenVisibleWithComposeChild_returnsAtLeastDefaultHeight() {
        val composeView = ComposeView(context)
        mediaContainerView.addView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        mediaContainerView.visibility = View.VISIBLE
        val defaultHeight =
            (com.android.systemui.media.remedia.ui.compose.Media.DEFAULT_HEIGHT.value *
                context.resources.displayMetrics.density)
                .toInt()
        assertThat(mediaContainerView.intrinsicHeight).isAtLeast(defaultHeight)
    }

    @Test
    fun testOnMeasure_withComposeViewChild_enforcesIntrinsicHeight() {
        val composeView = ComposeView(context)
        mediaContainerView.addView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        mediaContainerView.visibility = View.VISIBLE
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        mediaContainerView.measure(widthSpec, heightSpec)

        assertThat(mediaContainerView.measuredHeight)
            .isAtLeast(mediaContainerView.intrinsicHeight)
        assertThat(composeView.measuredHeight)
            .isAtLeast(mediaContainerView.intrinsicHeight)
    }

    @Test
    fun testOnLayout_withComposeViewChild_updatesActualHeight() {
        val composeView = ComposeView(context)
        mediaContainerView.addView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        mediaContainerView.visibility = View.VISIBLE
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        mediaContainerView.measure(widthSpec, heightSpec)
        mediaContainerView.layout(0, 0, 1000, mediaContainerView.measuredHeight)

        assertThat(mediaContainerView.actualHeight).isAtLeast(mediaContainerView.intrinsicHeight)
        assertThat(mediaContainerView.clipHeight).isAtLeast(mediaContainerView.intrinsicHeight)
        assertThat(composeView.height).isAtLeast(mediaContainerView.intrinsicHeight)
    }
}
