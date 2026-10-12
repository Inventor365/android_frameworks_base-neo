package com.android.systemui.scene.ui.composable.transitions

import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.UserActionDistance
import com.android.compose.animation.scene.reveal.ContainerRevealHaptics
import com.android.compose.animation.scene.reveal.verticalContainerReveal
import com.android.mechanics.behavior.VerticalExpandContainerSpec
import com.android.systemui.bouncer.ui.composable.Bouncer
import com.android.systemui.scene.ui.viewmodel.ToBouncerTransitionViewModel

/**
 * Same motion as expanding quick settings on the lock screen, with the bouncer growing up from the
 * bottom edge instead of down from the top.
 */
fun TransitionBuilder.lockscreenToBouncerTransition(
    durationScale: Double = 1.0,
    toBouncerTransitionViewModel: ToBouncerTransitionViewModel,
    shadeExpansionMotion: VerticalExpandContainerSpec,
    revealHaptics: ContainerRevealHaptics,
) {
    lockscreenFadeOutTransition(durationScale = durationScale)

    verticalContainerReveal(
        container = Bouncer.Elements.Background,
        motionSpec = shadeExpansionMotion,
        haptics = revealHaptics,
        useMechanics = true,
    )
    // The reveal measures the swipe against the bouncer, which isn't laid out yet when a swipe
    // starts. Use the screen being swiped on instead, like the regular bouncer transitions.
    distance = UserActionDistance { fromContent, _, _ ->
        checkNotNull(fromContent.targetSize()).height * TO_BOUNCER_SWIPE_DISTANCE_FRACTION
    }
    // The PIN rows slide up on their own, so only fade the content.
    bouncerContentInTransition(viewModel = toBouncerTransitionViewModel, translate = false)
}
