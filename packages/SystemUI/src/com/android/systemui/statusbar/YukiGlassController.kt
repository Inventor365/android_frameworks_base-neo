/*
 * SPDX-FileCopyrightText: 2026 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.CrossWindowBlurListeners
import com.android.internal.util.neoteric.YukiGlassConfig
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class YukiGlassSnapshot(
    val enabled: Boolean = true,
    val globalStyle: Int = YukiGlassConfig.DEFAULT_STYLE,
    val qsMode: Int = YukiGlassConfig.DEFAULT_STYLE,
    val qsTiles: Boolean = true,
    val shadeMode: Int = YukiGlassConfig.DEFAULT_STYLE,
    val notifCards: Boolean = true,
    val dualTone: Boolean = false,
    val intensityPercent: Int = YukiGlassConfig.DEFAULT_INTENSITY,
    val frostPercent: Int = YukiGlassConfig.DEFAULT_FROST,
    val rimPercent: Int = YukiGlassConfig.DEFAULT_RIM,
    val blurSupported: Boolean = true,
) {
    val effectiveQsMode: Int
        get() = if (enabled && blurSupported) qsMode else YukiGlassConfig.MODE_OFF

    val effectiveShadeMode: Int
        get() = if (enabled && blurSupported) shadeMode else YukiGlassConfig.MODE_OFF

    val intensityScale: Float
        get() = (intensityPercent.coerceIn(0, 200) / 100f)
}

/**
 * Reactive singleton inside SystemUI observing Yuki's Addons glass/blur settings and
 * CrossWindowBlurListeners without altering Dagger constructor signatures.
 */
class YukiGlassController private constructor(context: Context) {

    fun interface Listener {
        fun onYukiGlassChanged(snapshot: YukiGlassSnapshot)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val blurListeners = CrossWindowBlurListeners.getInstance()

    @Volatile
    private var blurSupported: Boolean =
        CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED &&
            blurListeners.isCrossWindowBlurEnabled()

    private val _state = MutableStateFlow(readSnapshot())
    val state: StateFlow<YukiGlassSnapshot> = _state.asStateFlow()

    val current: YukiGlassSnapshot
        get() = _state.value

    private val observer =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                refresh()
            }
        }

    init {
        val resolver = appContext.contentResolver
        for (key in OBSERVED_KEYS) {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(key),
                false,
                observer,
                UserHandle.USER_ALL,
            )
        }
        if (CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED) {
            blurListeners.addListener(appContext.mainExecutor) { enabled ->
                blurSupported = enabled
                refresh()
            }
        }
        refresh()
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun refresh() {
        val next = readSnapshot()
        if (_state.value != next) {
            _state.value = next
            for (listener in listeners) {
                listener.onYukiGlassChanged(next)
            }
        }
    }

    private fun readSnapshot(): YukiGlassSnapshot {
        val r = appContext.contentResolver
        val enabled = YukiGlassConfig.isEnabled(r)
        val global = YukiGlassConfig.getGlobalStyle(r)
        val qsMode = YukiGlassConfig.resolveSurfaceMode(r, YukiGlassConfig.KEY_QS_MODE)
        val shadeMode = YukiGlassConfig.resolveSurfaceMode(r, YukiGlassConfig.KEY_SHADE_MODE)
        return YukiGlassSnapshot(
            enabled = enabled,
            globalStyle = global,
            qsMode = qsMode,
            qsTiles = YukiGlassConfig.getBool(r, YukiGlassConfig.KEY_QS_TILES, YukiGlassConfig.DEFAULT_QS_TILES),
            shadeMode = shadeMode,
            notifCards = YukiGlassConfig.getBool(r, YukiGlassConfig.KEY_NOTIF_CARDS, YukiGlassConfig.DEFAULT_NOTIF_CARDS),
            dualTone = YukiGlassConfig.getBool(r, YukiGlassConfig.KEY_DUAL_TONE, YukiGlassConfig.DEFAULT_DUAL_TONE),
            intensityPercent = YukiGlassConfig.getIntensityPercent(r),
            frostPercent = YukiGlassConfig.getInt(r, YukiGlassConfig.KEY_FROST, YukiGlassConfig.DEFAULT_FROST, 0, 100),
            rimPercent = YukiGlassConfig.getInt(r, YukiGlassConfig.KEY_RIM, YukiGlassConfig.DEFAULT_RIM, 0, 100),
            blurSupported = blurSupported,
        )
    }

    companion object {
        private val OBSERVED_KEYS =
            arrayOf(
                YukiGlassConfig.KEY_ENABLED,
                YukiGlassConfig.KEY_STYLE,
                YukiGlassConfig.KEY_INTENSITY,
                YukiGlassConfig.KEY_FROST,
                YukiGlassConfig.KEY_RIM,
                YukiGlassConfig.KEY_QS_MODE,
                YukiGlassConfig.KEY_QS_TILES,
                YukiGlassConfig.KEY_SHADE_MODE,
                YukiGlassConfig.KEY_NOTIF_CARDS,
                YukiGlassConfig.KEY_DUAL_TONE,
            )

        @Volatile private var instance: YukiGlassController? = null

        @JvmStatic
        fun get(context: Context): YukiGlassController {
            return instance
                ?: synchronized(this) {
                    instance ?: YukiGlassController(context).also { instance = it }
                }
        }
    }
}
