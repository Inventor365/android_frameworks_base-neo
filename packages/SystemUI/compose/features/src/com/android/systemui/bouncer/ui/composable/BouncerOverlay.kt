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

package com.android.systemui.bouncer.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.overscroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.layout.ContainerConfig
import com.android.compose.layout.containerize
import com.android.mechanics.behavior.verticalExpandContainerBackground
import com.android.systemui.bouncer.ui.viewmodel.BouncerOverlayContentViewModel
import com.android.systemui.bouncer.ui.viewmodel.BouncerUserActionsViewModel
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.ui.composable.Overlay
import com.android.systemui.shade.ui.composable.OverlayShade
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

object Bouncer {
    object Elements {
        val Root = ElementKey("BouncerRoot")
        val Background = ElementKey("BouncerBackground")
        val Content = ElementKey("BouncerContent")
    }

    object TestTags {
        const val ROOT = "bouncer_root"
    }
}

/** The bouncer overlay displays authentication challenges like PIN, password, or pattern. */
@SysUISingleton
class BouncerOverlay
@Inject
constructor(
    private val actionsViewModelFactory: BouncerUserActionsViewModel.Factory,
    private val contentViewModelFactory: BouncerOverlayContentViewModel.Factory,
    private val dialogFactory: SystemUIDialog.Factory,
) : Overlay {
    override val key = Overlays.Bouncer

    private val actionsViewModel: BouncerUserActionsViewModel by lazy {
        actionsViewModelFactory.create()
    }

    override val userActions: Flow<Map<UserAction, UserActionResult>> = actionsViewModel.actions

    override val alwaysCompose: Boolean = false

    override suspend fun activate(): Nothing {
        actionsViewModel.activate()
    }

    @Composable
    override fun ContentScope.Content(modifier: Modifier) =
        BouncerOverlay(
            viewModel = rememberViewModel("BouncerOverlay") { contentViewModelFactory.create() },
            dialogFactory = dialogFactory,
            modifier = modifier.element(Bouncer.Elements.Root),
        )
}

@Composable
private fun ContentScope.BouncerOverlay(
    viewModel: BouncerOverlayContentViewModel,
    dialogFactory: SystemUIDialog.Factory,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = viewModel.backgroundColor

    DisposableEffect(Unit) { onDispose { viewModel.onUiDestroyed() } }
    val containerLayout = calculateContainerLayout()
    val isContainerized = containerLayout != null
    val insets = WindowInsets.navigationBars.union(WindowInsets.ime)
    Box(
        modifier
            .fillMaxSize()
            // Block pointer events from reaching overlay framework and dismissing Bouncer
            .pointerInput(Unit) { detectTapGestures() }
    ) {
        Box(
            Modifier.then(
                if (isContainerized) {
                    Modifier.containerize(containerConfig(), containerLayout, insets)
                } else {
                    Modifier.fillMaxSize()
                }
            ),
            // The background is revealed from the bottom edge, so keep it anchored there.
            contentAlignment = Alignment.BottomCenter,
        ) {

            // Background is defined in a separate Composable from BouncerContent to be able to
            // animate it differently.
            Background(
                Modifier.element(Bouncer.Elements.Background)
                    .testTag(Bouncer.Elements.Background.testTag),
                color = backgroundColor,
                isContainerized = isContainerized,
                isRevealing = layoutState.isTransitioning(),
            )

            // Separate the bouncer content into a reusable composable that doesn't have any
            // ContentScope dependencies
            BouncerContent(
                viewModel,
                dialogFactory,
                Modifier.element(Bouncer.Elements.Content)
                    // TODO(b/393516240): Use the same sysuiResTag() as views instead.
                    .testTag(Bouncer.Elements.Content.testTag)
                    .overscroll(verticalOverscrollEffect)
                    .sysuiResTag(Bouncer.TestTags.ROOT)
                    .fillMaxSize()
                    // No-op if containerization is enabled as insets will be already consumed.
                    .windowInsetsPadding(insets),
            )
        }
    }
}

@Composable
private fun Background(
    modifier: Modifier,
    color: Color,
    isContainerized: Boolean,
    isRevealing: Boolean,
) {
    // Containerized bouncers keep their own shape, so this is only used full width.
    val revealSpec = OverlayShade.rememberShadeExpansionMotion(isFullWidth = true)
    Box(
        modifier
            .fillMaxSize()
            .then(
                when {
                    isContainerized ->
                        Modifier.background(color = color, shape = MaterialTheme.shapes.extraLarge)
                    // Unfold like the shade panel. The shade grows down from the top, so draw its
                    // background upside down for the bouncer growing up from the bottom.
                    isRevealing ->
                        Modifier.graphicsLayer { scaleY = -1f }
                            .verticalExpandContainerBackground(color, revealSpec)
                    else -> Modifier.background(color = color)
                }
            )
    )
}

@Composable
private fun containerConfig(): ContainerConfig {
    val sizeFraction =
        LocalContext.current.resources.getFloat(R.dimen.bouncer_container_size_fraction)
    val minLongEdge = dimensionResource(R.dimen.bouncer_container_min_long_edge)
    val minShortEdge = dimensionResource(R.dimen.bouncer_container_min_short_edge)
    val maxLongEdge = dimensionResource(R.dimen.bouncer_container_max_long_edge)
    val maxShortEdge = dimensionResource(R.dimen.bouncer_container_max_short_edge)
    return ContainerConfig(sizeFraction, minLongEdge, minShortEdge, maxLongEdge, maxShortEdge)
}
