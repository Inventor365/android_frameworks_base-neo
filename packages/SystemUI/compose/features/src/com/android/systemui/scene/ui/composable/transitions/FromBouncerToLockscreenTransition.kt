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

package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.UserActionDistance
import com.android.compose.animation.scene.reveal.ContainerRevealHaptics
import com.android.compose.animation.scene.reveal.verticalContainerReveal
import com.android.mechanics.behavior.VerticalExpandContainerSpec
import com.android.systemui.bouncer.ui.composable.Bouncer
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys
import kotlin.time.Duration.Companion.milliseconds

/** Reverse of [lockscreenToBouncerTransition]: the bouncer drops back down into the bottom edge. */
fun TransitionBuilder.bouncerToLockscreenTransition(
    shadeExpansionMotion: VerticalExpandContainerSpec,
    revealHaptics: ContainerRevealHaptics,
    durationScale: Double = 1.0,
) {
    spec = tween(durationMillis = (DefaultDuration * durationScale).inWholeMilliseconds.toInt())

    verticalContainerReveal(
        container = Bouncer.Elements.Background,
        motionSpec = shadeExpansionMotion,
        haptics = revealHaptics,
        useMechanics = true,
    )
    // Keep the regular bouncer swipe distance rather than the reveal's full panel height.
    distance = UserActionDistance { fromContent, _, _ ->
        checkNotNull(fromContent.targetSize()).height * TO_BOUNCER_SWIPE_DISTANCE_FRACTION
    }
    fractionRange(end = TO_BOUNCER_FADE_FRACTION) { fade(Bouncer.Elements.Content) }

    // Bring the lock screen back while the bouncer drops, instead of only once it's gone.
    fractionRange(start = 0.2f, end = 0.7f) { fade(LockscreenElementKeys.Root) }
    fractionRange(start = 0.95f) { fade(LockscreenElementKeys.StatusBar) }
}

private val DefaultDuration = 500.milliseconds
