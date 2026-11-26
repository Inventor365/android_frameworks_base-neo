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

import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.GenericRecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
    private static final String TAG = "AmbientMusicShim";

    /** Vendor UUID that makes the QCOM sound trigger HAL open a PAL_STREAM_ACD stream. */
    private static final UUID QC_ACD_VENDOR_UUID =
            UUID.fromString("4e93281b-296e-4d73-9833-2710c3c7c1db");

    /** Vendor UUID of the QTI music detection LSM module on the legacy sound trigger HAL. */
    private static final UUID QTI_MD_VENDOR_UUID =
            UUID.fromString("876c1b46-9d4d-40cc-a4fd-4d5ec7a80e47");

    private static final UUID[] AMBIENT_MUSIC_VENDOR_UUIDS = {
        UUID.fromString("9f6ad62a-1f0b-11e7-87c5-40a8f03d3f15"),
        UUID.fromString("12caddb1-acdb-4dce-8cb0-2e95a2313aee"),
    };

    private static final int ST_PARAM_KEY_CONTEXT_RECOGNITION_INFO = 5;
    private static final int ST_PARAM_KEY_CONTEXT_EVENT_INFO = 6;

    private static final int CONTEXT_ID_AMBIENCE_MUSIC = 0x08001336;
    private static final int AUDIO_CONTEXT_EVENT_STOPPED = 0;
    private static final int AUDIO_CONTEXT_EVENT_STARTED = 1;
    private static final int AUDIO_CONTEXT_EVENT_DETECTED = 2;

    private static final int ACD_RECOGNITION_CFG_SIZE = 8;
    private static final int ACD_PER_CONTEXT_CFG_SIZE = 12;
    private static final int ACD_CONTEXT_EVENT_SIZE = 16;
    private static final int ACD_PER_CONTEXT_EVENT_INFO_SIZE = 20;

    /** A "newsong"/"neg_music" prefixed payload marks the event as handled, so send neither. */
    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    private static final Set<UUID> sTranslatedModels = ConcurrentHashMap.newKeySet();

    /** Model classifier frames are 640 ms, as in ASI's SmoothedLatchingClassifierTfliteEditor. */
    private static final int CLASSIFIER_FRAME_MS = 640;
    private static final byte[] FIRST_CLASSIFIER_KEY =
            "generate_negative_events\0".getBytes(StandardCharsets.US_ASCII);
    private static final String SQUELCH_KEY = "initial_squelch_frames";
    private static final String TIME_BEFORE_EVENT_KEY = "positive_time_before_event_frames";

    private static final Map<UUID, Integer> sLastMusicEvent = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> sArmDelayMs = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> sArmedAt = new ConcurrentHashMap<>();
    private static final Set<UUID> sFired = ConcurrentHashMap.newKeySet();

    private static final Map<UUID, Long> sLastEventSeen = new ConcurrentHashMap<>();

    /** Re-arms a model whose engine has gone quiet. */
    interface Rearmer {
        void rearm(UUID modelUuid);
    }

    /** Whichever session started the model; other sessions have no callback for it. */
    private static final Map<UUID, Rearmer> sRearmers = new ConcurrentHashMap<>();

    /** Delivers a trigger to the client once the squelch ends with music still playing. */
    interface Waker {
        void wake(UUID modelUuid);
    }

    private static final Map<UUID, Waker> sWakers = new ConcurrentHashMap<>();

    /** Re-arming blocks on the HAL, which is wedged whenever this runs. Keep it off main. */
    private static final Handler sHandler = new Handler(BackgroundThread.get().getLooper());

    private static final Runnable sSweep = AmbientMusicShim::sweep;

    private AmbientMusicShim() {
    }

    static boolean isEnabled() {
        return SystemProperties.getBoolean("persist.sys.ambient_music_shim", true);
    }

    /** Legacy HALs without ACD detect music through the QTI music detection module instead. */
    private static boolean isQtiMd() {
        return "qti_md".equals(SystemProperties.get("persist.sys.ambient_music_engine"));
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
            sLastEventSeen.remove(modelUuid);
            sLastMusicEvent.remove(modelUuid);
            sArmDelayMs.remove(modelUuid);
            sArmedAt.remove(modelUuid);
            sFired.remove(modelUuid);
            sRearmers.remove(modelUuid);
            sWakers.remove(modelUuid);
            sHandler.removeCallbacksAndMessages(modelUuid);
        }
        if (sTranslatedModels.isEmpty()) {
            sHandler.removeCallbacks(sSweep);
        }
    }


    /** Every event counts as liveness, including the ones held back by the squelch. */
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
        for (UUID modelUuid : sTranslatedModels) {
            Rearmer rearmer = sRearmers.get(modelUuid);
            if (rearmer != null && isStale(modelUuid)) {
                noteEvent(modelUuid);
                rearmer.rearm(modelUuid);
            }
        }
        if (!sTranslatedModels.isEmpty()) {
            scheduleSweep();
        }
    }

    static GenericSoundModel translateModel(GenericSoundModel soundModel) {
        sTranslatedModels.add(soundModel.getUuid());
        long squelchMs = readClassifierFrames(soundModel.getData(), SQUELCH_KEY)
                * CLASSIFIER_FRAME_MS;
        long timeBeforeEventMs = readClassifierFrames(soundModel.getData(), TIME_BEFORE_EVENT_KEY)
                * CLASSIFIER_FRAME_MS;
        sArmDelayMs.put(soundModel.getUuid(), squelchMs + timeBeforeEventMs);
        Slog.i(TAG, "Model squelch " + squelchMs + "ms, time before event " + timeBeforeEventMs
                + "ms");
        noteEvent(soundModel.getUuid());
        scheduleSweep();
        return new GenericSoundModel(soundModel.getUuid(),
                isQtiMd() ? QTI_MD_VENDOR_UUID : QC_ACD_VENDOR_UUID,
                soundModel.getData(), soundModel.getVersion());
    }

    /** The ACD engine takes its contexts from the recognition config rather than the model. */
    static RecognitionConfig translateConfig(RecognitionConfig config) {
        if (isQtiMd()) {
            return new RecognitionConfig.Builder()
                    .setCaptureRequested(false)
                    .setMultipleTriggersAllowed(true)
                    .build();
        }
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
     * Like the client's one-shot DSP model: the first music event after the squelch the client
     * wrote into the model wakes it, then nothing until the client starts recognition again.
     */
    static boolean shouldNotify(UUID modelUuid, GenericRecognitionEvent event) {
        int musicEvent = getMusicEventType(event);
        Integer previous = sLastMusicEvent.put(modelUuid, musicEvent);
        if (previous == null || previous != musicEvent) {
            Slog.i(TAG, "ACD music " + musicEventName(musicEvent));
        }
        if (!isMusic(musicEvent) || sFired.contains(modelUuid) || isSquelched(modelUuid)) {
            return false;
        }
        fire(modelUuid, "on music " + musicEventName(musicEvent));
        return true;
    }

    /** The client started recognition, which arms the model behind a fresh squelch. */
    static void onStart(UUID modelUuid, Rearmer rearmer, Waker waker) {
        sRearmers.put(modelUuid, rearmer);
        sWakers.put(modelUuid, waker);
        sFired.remove(modelUuid);
        sArmedAt.put(modelUuid, SystemClock.elapsedRealtime());
        sHandler.removeCallbacksAndMessages(modelUuid);
        long armDelayMs = sArmDelayMs.getOrDefault(modelUuid, 0L);
        if (armDelayMs > 0) {
            sHandler.postDelayed(() -> onSquelchEnd(modelUuid), modelUuid, armDelayMs);
        }
    }

    /** A DSP re-evaluates after its squelch, but ACD stays latched, so catch up on its state. */
    private static void onSquelchEnd(UUID modelUuid) {
        Waker waker = sWakers.get(modelUuid);
        Integer last = sLastMusicEvent.get(modelUuid);
        Slog.i(TAG, "Squelch over, ACD music " + (last == null ? "unknown" : musicEventName(last)));
        if (waker != null && last != null && isMusic(last) && !sFired.contains(modelUuid)) {
            waker.wake(modelUuid);
        }
    }

    static boolean isFired(UUID modelUuid) {
        return modelUuid != null && sFired.contains(modelUuid);
    }

    private static boolean isMusic(int musicEvent) {
        return musicEvent == AUDIO_CONTEXT_EVENT_STARTED
                || musicEvent == AUDIO_CONTEXT_EVENT_DETECTED;
    }

    private static boolean isSquelched(UUID modelUuid) {
        Long armedAt = sArmedAt.get(modelUuid);
        return armedAt != null && SystemClock.elapsedRealtime()
                < armedAt + sArmDelayMs.getOrDefault(modelUuid, 0L);
    }

    private static void fire(UUID modelUuid, String reason) {
        sFired.add(modelUuid);
        sHandler.removeCallbacksAndMessages(modelUuid);
        Slog.i(TAG, "Waking client " + reason);
    }

    /** Reads an int from the classifier's flexbuffer options in the tflite model, 0 if absent. */
    private static long readClassifierFrames(byte[] model, String key) {
        byte[] name = (key + "\0").getBytes(StandardCharsets.US_ASCII);
        try {
            int start = indexOf(model, FIRST_CLASSIFIER_KEY);
            if (start < 4) {
                return 0;
            }
            ByteBuffer b = ByteBuffer.wrap(model).order(ByteOrder.LITTLE_ENDIAN);
            int end = start + b.getInt(start - 4);
            int rootWidth = model[end - 1] & 0xff;
            int width = 1 << (model[end - 2] & 3);
            int root = end - 2 - rootWidth;
            int map = root - (int) readUnsigned(b, root, rootWidth);
            int size = (int) readUnsigned(b, map - width, width);
            int keysWidth = (int) readUnsigned(b, map - 2 * width, width);
            int keys = map - 3 * width - (int) readUnsigned(b, map - 3 * width, width);
            for (int i = 0; i < size; i++) {
                int entry = keys + i * keysWidth;
                int keyPos = entry - (int) readUnsigned(b, entry, keysWidth);
                if (regionMatches(model, keyPos, name)) {
                    return readUnsigned(b, map + i * width, width);
                }
            }
        } catch (IndexOutOfBoundsException e) {
            Slog.w(TAG, "Unreadable model config", e);
        }
        return 0;
    }

    private static long readUnsigned(ByteBuffer b, int pos, int width) {
        long value = 0;
        for (int i = width - 1; i >= 0; i--) {
            value = (value << 8) | (b.get(pos + i) & 0xff);
        }
        return value;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        for (int i = 0; i + needle.length <= data.length; i++) {
            if (regionMatches(data, i, needle)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean regionMatches(byte[] data, int pos, byte[] needle) {
        if (pos < 0 || pos + needle.length > data.length) {
            return false;
        }
        for (int i = 0; i < needle.length; i++) {
            if (data[pos + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    private static String musicEventName(int eventType) {
        switch (eventType) {
            case AUDIO_CONTEXT_EVENT_STARTED:
                return "started";
            case AUDIO_CONTEXT_EVENT_DETECTED:
                return "still detected";
            case AUDIO_CONTEXT_EVENT_STOPPED:
                return "stopped";
            default:
                return "event " + eventType;
        }
    }

    /** Returns the music context's event type (STARTED/DETECTED/...), or -1 if absent. */
    private static int getMusicEventType(GenericRecognitionEvent event) {
        if (isQtiMd()) {
            return event.status == SoundTrigger.RECOGNITION_STATUS_SUCCESS
                    ? AUDIO_CONTEXT_EVENT_DETECTED : AUDIO_CONTEXT_EVENT_STOPPED;
        }
        byte[] data = event.data;
        if (data == null) {
            return -1;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() >= 8) {
            int keyId = buffer.getInt();
            int payloadSize = buffer.getInt();
            if (payloadSize < 0 || payloadSize > buffer.remaining()) {
                return -1;
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
                if (contextId == CONTEXT_ID_AMBIENCE_MUSIC) {
                    return eventType;
                }
            }
            return -1;
        }
        return -1;
    }

    /**
     * ACD detects music but cannot replay it, so the client is pointed at a fresh capture session
     * instead of the empty one the HAL reports.
     */
    static GenericRecognitionEvent translateEvent(GenericRecognitionEvent event) {
        return new GenericRecognitionEvent(event.status, event.soundModelHandle,
                true, 0, event.captureDelayMs, event.capturePreambleMs, event.triggerInData,
                captureFormat(), EMPTY_PAYLOAD, event.recognitionStillActive,
                event.halEventReceivedMillis, event.token);
    }

    /** ACD has no getModelState, so the GET_STATE_RESPONSE a DSP would send is made up here. */
    static GenericRecognitionEvent forcedEvent(UUID modelUuid) {
        fire(modelUuid, "on model state request");
        return syntheticEvent(SoundTrigger.RECOGNITION_STATUS_GET_STATE_RESPONSE);
    }

    static GenericRecognitionEvent squelchEndEvent(UUID modelUuid) {
        fire(modelUuid, "after squelch");
        return syntheticEvent(SoundTrigger.RECOGNITION_STATUS_SUCCESS);
    }

    /** Events made here are already in client form and skip the ACD filtering. */
    static boolean isSynthetic(GenericRecognitionEvent event) {
        return event.soundModelHandle == 0 && (event.data == null || event.data.length == 0);
    }

    private static GenericRecognitionEvent syntheticEvent(int status) {
        return new GenericRecognitionEvent(status, 0, true, 0, 0, 0, false, captureFormat(),
                EMPTY_PAYLOAD, SystemClock.elapsedRealtime(), null);
    }

    private static AudioFormat captureFormat() {
        return new AudioFormat.Builder()
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .build();
    }
}
