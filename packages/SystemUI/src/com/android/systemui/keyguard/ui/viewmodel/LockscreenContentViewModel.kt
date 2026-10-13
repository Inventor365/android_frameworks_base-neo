/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.keyguard.shared.transition.KeyguardTransitionAnimationCallback
import com.android.systemui.keyguard.shared.transition.KeyguardTransitionAnimationCallbackDelegator
import com.android.systemui.lifecycle.HydratedActivatable
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope

class LockscreenContentViewModel
@AssistedInject
constructor(
    private val keyguardTransitionAnimationCallbackDelegator:
        KeyguardTransitionAnimationCallbackDelegator,
    private val lockscreenAlphaViewModelFactory: LockscreenAlphaViewModel.Factory,
    @Assisted private val keyguardTransitionAnimationCallback: KeyguardTransitionAnimationCallback,
    @Assisted private val viewStateAccessor: ViewStateAccessor,
) : HydratedActivatable() {
    /** Alpha value applied to all LockscreenElements. */
    val alpha: Float
        get() = lockscreenAlphaViewModel.alpha

    private val lockscreenAlphaViewModel: LockscreenAlphaViewModel by lazy {
        lockscreenAlphaViewModelFactory.create(viewStateAccessor)
    }

    /** Alpha value applied to all nonAuthUI LockscreenElements. */
    val nonAuthUIAlpha: Float
        get() = lockscreenAlphaViewModel.nonAuthUIAlpha

    override suspend fun onActivated() {
        coroutineScope {
            launch { lockscreenAlphaViewModel.activate() }

            keyguardTransitionAnimationCallbackDelegator.delegate =
                keyguardTransitionAnimationCallback
        }
    }

    override suspend fun onDeactivated() {
        keyguardTransitionAnimationCallbackDelegator.delegate = null
    }

    @AssistedFactory
    interface Factory {
        fun create(
            keyguardTransitionAnimationCallback: KeyguardTransitionAnimationCallback,
            viewState: ViewStateAccessor,
        ): LockscreenContentViewModel
    }
}
