/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.soundtrigger;

import android.hardware.soundtrigger.SoundTrigger.GenericRecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;

import com.android.internal.os.BackgroundThread;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates ambient music sound models onto the QCOM ACD detection engine.
 *
 * <p>Clients ask for a Google ambient music model, which the QCOM sound trigger HAL cannot load.
 * The HAL does however expose an always-on context detector that reports the presence of music on
 * the low power island. This rewrites the model onto that engine and reshapes its events back into
 * what the client expects.
 */
final class AmbientMusicShim {
    /** Vendor UUID that makes the QCOM sound trigger HAL open a PAL_STREAM_ACD stream. */
    private static final UUID QC_ACD_VENDOR_UUID =
            UUID.fromString("4e93281b-296e-4d73-9833-2710c3c7c1db");

    private static final UUID[] AMBIENT_MUSIC_VENDOR_UUIDS = {
        UUID.fromString("9f6ad62a-1f0b-11e7-87c5-40a8f03d3f15"),
        UUID.fromString("12caddb1-acdb-4dce-8cb0-2e95a2313aee"),
    };

    private static final int ST_PARAM_KEY_CONTEXT_RECOGNITION_INFO = 5;
    private static final int ST_PARAM_KEY_CONTEXT_EVENT_INFO = 6;

    private static final int CONTEXT_ID_AMBIENCE_MUSIC = 0x08001336;
    private static final int AUDIO_CONTEXT_EVENT_STARTED = 1;

    private static final int ACD_RECOGNITION_CFG_SIZE = 8;
    private static final int ACD_PER_CONTEXT_CFG_SIZE = 12;
    private static final int ACD_CONTEXT_EVENT_SIZE = 16;
    private static final int ACD_PER_CONTEXT_EVENT_INFO_SIZE = 20;

    /** A "newsong"/"neg_music" prefixed payload marks the event as handled, so send neither. */
    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    private static final Set<UUID> sTranslatedModels = ConcurrentHashMap.newKeySet();

    private static final Map<UUID, Long> sLastNotified = new ConcurrentHashMap<>();

    private static final Map<UUID, Long> sLastEventSeen = new ConcurrentHashMap<>();

    /** Re-arms a model whose engine has gone quiet. */
    interface Rearmer {
        void rearm(UUID modelUuid);
    }

    private static volatile Rearmer sRearmer;

    /** Re-arming blocks on the HAL, which is wedged whenever this runs. Keep it off main. */
    private static final Handler sHandler = new Handler(BackgroundThread.get().getLooper());

    private static final Runnable sSweep = AmbientMusicShim::sweep;

    private AmbientMusicShim() {
    }

    static boolean isEnabled() {
        return SystemProperties.getBoolean("persist.sys.ambient_music_shim", true);
    }

    static boolean matches(GenericSoundModel soundModel) {
        if (!isEnabled() || soundModel == null) {
            return false;
        }
        UUID vendorUuid = soundModel.getVendorUuid();
        if (vendorUuid == null) {
            return false;
        }
        for (UUID candidate : AMBIENT_MUSIC_VENDOR_UUIDS) {
            if (candidate.equals(vendorUuid)) {
                return true;
            }
        }
        return false;
    }

    static boolean isTranslated(UUID modelUuid) {
        return modelUuid != null && sTranslatedModels.contains(modelUuid);
    }

    static void forget(UUID modelUuid) {
        if (modelUuid != null) {
            sTranslatedModels.remove(modelUuid);
            sLastNotified.remove(modelUuid);
            sLastEventSeen.remove(modelUuid);
        }
        if (sTranslatedModels.isEmpty()) {
            sHandler.removeCallbacks(sSweep);
        }
    }

    static void setRearmer(Rearmer rearmer) {
        sRearmer = rearmer;
    }

    /** Every event counts as liveness, including the ones held back by the cooldown. */
    static void noteEvent(UUID modelUuid) {
        if (modelUuid != null) {
            sLastEventSeen.put(modelUuid, SystemClock.elapsedRealtime());
        }
    }

    /**
     * The engine can come back from an error with the model loaded but not listening, and nothing
     * below tells us about it. Silence past the timeout is the only symptom available.
     */
    static boolean isStale(UUID modelUuid) {
        Long last = sLastEventSeen.get(modelUuid);
        if (last == null) {
            return false;
        }
        long timeoutMs = SystemProperties.getInt("persist.sys.ambient_music_stale_ms", 300000);
        return SystemClock.elapsedRealtime() - last > timeoutMs;
    }

    private static void scheduleSweep() {
        long periodMs = SystemProperties.getInt("persist.sys.ambient_music_sweep_ms", 60000);
        sHandler.removeCallbacks(sSweep);
        sHandler.postDelayed(sSweep, periodMs);
    }

    private static void sweep() {
        Rearmer rearmer = sRearmer;
        if (rearmer != null) {
            for (UUID modelUuid : sTranslatedModels) {
                if (isStale(modelUuid)) {
                    noteEvent(modelUuid);
                    rearmer.rearm(modelUuid);
                }
            }
        }
        if (!sTranslatedModels.isEmpty()) {
            scheduleSweep();
        }
    }

    static GenericSoundModel translateModel(GenericSoundModel soundModel) {
        sTranslatedModels.add(soundModel.getUuid());
        noteEvent(soundModel.getUuid());
        scheduleSweep();
        return new GenericSoundModel(soundModel.getUuid(), QC_ACD_VENDOR_UUID,
                soundModel.getData(), soundModel.getVersion());
    }

    /** The ACD engine takes its contexts from the recognition config rather than the model. */
    static RecognitionConfig translateConfig(RecognitionConfig config) {
        return new RecognitionConfig.Builder()
                .setCaptureRequested(false)
                .setMultipleTriggersAllowed(true)
                .setData(buildAcdRecognitionConfig())
                .build();
    }

    private static byte[] buildAcdRecognitionConfig() {
        int threshold = SystemProperties.getInt("persist.sys.ambient_music_threshold", 50);
        int stepSize = SystemProperties.getInt("persist.sys.ambient_music_step_size", 10);

        int configSize = ACD_RECOGNITION_CFG_SIZE + ACD_PER_CONTEXT_CFG_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(8 + configSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(ST_PARAM_KEY_CONTEXT_RECOGNITION_INFO);
        buffer.putInt(configSize);
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putInt(CONTEXT_ID_AMBIENCE_MUSIC);
        buffer.putInt(threshold);
        buffer.putInt(stepSize);
        return buffer.array();
    }

    /**
     * Clients ask the detection model to stay quiet for a while after a song is identified. The
     * ACD engine has no such control, so the events are held back here instead.
     */
    static boolean shouldNotify(UUID modelUuid, GenericRecognitionEvent event) {
        if (!isMusicStart(event)) {
            return false;
        }
        long cooldownMs = SystemProperties.getInt("persist.sys.ambient_music_cooldown_ms", 45000);
        long now = SystemClock.elapsedRealtime();
        Long last = sLastNotified.get(modelUuid);
        if (last != null && now - last < cooldownMs) {
            return false;
        }
        sLastNotified.put(modelUuid, now);
        return true;
    }

    /**
     * The engine reports music continuously once it latches on. Only the transition into music is
     * worth waking the client for.
     */
    private static boolean isMusicStart(GenericRecognitionEvent event) {
        byte[] data = event.data;
        if (data == null) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() >= 8) {
            int keyId = buffer.getInt();
            int payloadSize = buffer.getInt();
            if (payloadSize < 0 || payloadSize > buffer.remaining()) {
                return false;
            }
            if (keyId != ST_PARAM_KEY_CONTEXT_EVENT_INFO
                    || payloadSize < ACD_CONTEXT_EVENT_SIZE) {
                buffer.position(buffer.position() + payloadSize);
                continue;
            }
            buffer.getInt();
            buffer.getLong();
            int numContexts = buffer.getInt();
            for (int i = 0; i < numContexts
                    && buffer.remaining() >= ACD_PER_CONTEXT_EVENT_INFO_SIZE; i++) {
                int contextId = buffer.getInt();
                int eventType = buffer.getInt();
                buffer.getInt();
                buffer.getLong();
                if (contextId == CONTEXT_ID_AMBIENCE_MUSIC
                        && eventType == AUDIO_CONTEXT_EVENT_STARTED) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * ACD detects music but cannot replay it, so the client is pointed at a fresh capture session
     * instead of the empty one the HAL reports.
     */
    static GenericRecognitionEvent translateEvent(GenericRecognitionEvent event) {
        AudioFormat captureFormat = new AudioFormat.Builder()
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .build();
        return new GenericRecognitionEvent(event.status, event.soundModelHandle,
                true, 0, event.captureDelayMs, event.capturePreambleMs, event.triggerInData,
                captureFormat, EMPTY_PAYLOAD, event.recognitionStillActive,
                event.halEventReceivedMillis, event.token);
    }
}
