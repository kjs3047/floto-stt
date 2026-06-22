package com.floto.stt.model;

import android.content.Context;

import com.floto.stt.audio.Recording;
import com.floto.stt.settings.AppSettings;

import java.io.File;

public final class LocalWhisperTranscriber implements SpeechTranscriber {
    private static final Object LOCK = new Object();
    private static String loadedModelPath;
    private static long contextPtr;

    private final Context context;

    static {
        System.loadLibrary("floto_whisper");
    }

    public LocalWhisperTranscriber(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String transcribe(Recording recording) {
        String modelPath = AppSettings.getLocalModelPath(context);
        if (modelPath.isEmpty()) {
            throw new IllegalStateException("Local Whisper model file is not selected");
        }
        File modelFile = new File(modelPath);
        if (!modelFile.isFile()) {
            throw new IllegalStateException("Local Whisper model file does not exist: " + modelPath);
        }
        synchronized (LOCK) {
            ensureContext(modelPath);
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
            String result = nativeFullTranscribe(contextPtr, threads, recording.getMonoSamples(), AppSettings.getLanguage(context));
            return result == null ? "" : result.trim();
        }
    }

    private static void ensureContext(String modelPath) {
        if (contextPtr != 0L && modelPath.equals(loadedModelPath)) {
            return;
        }
        if (contextPtr != 0L) {
            nativeFreeContext(contextPtr);
            contextPtr = 0L;
        }
        contextPtr = nativeInitContext(modelPath);
        if (contextPtr == 0L) {
            throw new IllegalStateException("Could not load local Whisper model");
        }
        loadedModelPath = modelPath;
    }

    private static native long nativeInitContext(String modelPath);

    private static native void nativeFreeContext(long ptr);

    private static native String nativeFullTranscribe(long ptr, int threads, float[] audioData, String language);
}
