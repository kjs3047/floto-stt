package com.floto.stt.settings;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_LOCAL_WHISPER = "local_whisper";
    public static final String PROVIDER_SYSTEM_ON_DEVICE = "system_on_device";

    public static final String[] PROVIDER_LABELS = {
            "System on-device (recommended)",
            "OpenAI API",
            "Local Whisper.cpp (slow, experimental)"
    };

    public static final String[] PROVIDER_VALUES = {
            PROVIDER_SYSTEM_ON_DEVICE,
            PROVIDER_OPENAI,
            PROVIDER_LOCAL_WHISPER
    };

    public static final String[] OPENAI_MODELS = {
            "gpt-4o-transcribe",
            "gpt-4o-mini-transcribe",
            "whisper-1"
    };

    public static final LocalModelProfile[] LOCAL_MODEL_PROFILES = {
            new LocalModelProfile(
                    "Whisper tiny",
                    "ggml-tiny.bin",
                    "Fastest Whisper.cpp option, but still CPU-bound and slower than system on-device recognition.",
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
            ),
            new LocalModelProfile(
                    "Whisper base",
                    "ggml-base.bin",
                    "More accurate than tiny, but too slow for most live dictation on Android CPU.",
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
            ),
            new LocalModelProfile(
                    "Whisper small",
                    "ggml-small.bin",
                    "High latency on mobile CPU. Keep this for experiments, not everyday dictation.",
                    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
            )
    };

    private static final String PREFS = "floto_settings";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_OPENAI_MODEL = "openai_model";
    private static final String KEY_OPENAI_KEY = "openai_key";
    private static final String KEY_LOCAL_MODEL_PATH = "local_model_path";
    private static final String KEY_LOCAL_PROFILE = "local_profile";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_APPEND_SPACE = "append_space";
    private static final String KEY_OVERLAY_X = "overlay_x";
    private static final String KEY_OVERLAY_Y = "overlay_y";

    private AppSettings() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getProvider(Context context) {
        return prefs(context).getString(KEY_PROVIDER, PROVIDER_SYSTEM_ON_DEVICE);
    }

    public static void setProvider(Context context, String provider) {
        prefs(context).edit().putString(KEY_PROVIDER, provider).apply();
    }

    public static String getOpenAiModel(Context context) {
        return prefs(context).getString(KEY_OPENAI_MODEL, OPENAI_MODELS[0]);
    }

    public static void setOpenAiModel(Context context, String model) {
        prefs(context).edit().putString(KEY_OPENAI_MODEL, model).apply();
    }

    public static String getOpenAiKey(Context context) {
        return prefs(context).getString(KEY_OPENAI_KEY, "");
    }

    public static void setOpenAiKey(Context context, String value) {
        prefs(context).edit().putString(KEY_OPENAI_KEY, value == null ? "" : value.trim()).apply();
    }

    public static String getLocalModelPath(Context context) {
        return prefs(context).getString(KEY_LOCAL_MODEL_PATH, "");
    }

    public static void setLocalModelPath(Context context, String path) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_PATH, path == null ? "" : path).apply();
    }

    public static int getLocalProfile(Context context) {
        return prefs(context).getInt(KEY_LOCAL_PROFILE, 1);
    }

    public static void setLocalProfile(Context context, int index) {
        prefs(context).edit().putInt(KEY_LOCAL_PROFILE, index).apply();
    }

    public static String getLanguage(Context context) {
        return prefs(context).getString(KEY_LANGUAGE, "ko");
    }

    public static void setLanguage(Context context, String language) {
        prefs(context).edit().putString(KEY_LANGUAGE, normalize(language, "ko")).apply();
    }

    public static String getPrompt(Context context) {
        return prefs(context).getString(KEY_PROMPT, "");
    }

    public static void setPrompt(Context context, String prompt) {
        prefs(context).edit().putString(KEY_PROMPT, prompt == null ? "" : prompt.trim()).apply();
    }

    public static boolean shouldAppendSpace(Context context) {
        return prefs(context).getBoolean(KEY_APPEND_SPACE, true);
    }

    public static void setAppendSpace(Context context, boolean appendSpace) {
        prefs(context).edit().putBoolean(KEY_APPEND_SPACE, appendSpace).apply();
    }

    public static int getOverlayX(Context context) {
        return prefs(context).getInt(KEY_OVERLAY_X, 24);
    }

    public static int getOverlayY(Context context) {
        return prefs(context).getInt(KEY_OVERLAY_Y, 240);
    }

    public static void setOverlayPosition(Context context, int x, int y) {
        prefs(context).edit().putInt(KEY_OVERLAY_X, x).putInt(KEY_OVERLAY_Y, y).apply();
    }

    public static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    public static final class LocalModelProfile {
        public final String label;
        public final String fileName;
        public final String description;
        public final String downloadUrl;

        public LocalModelProfile(String label, String fileName, String description, String downloadUrl) {
            this.label = label;
            this.fileName = fileName;
            this.description = description;
            this.downloadUrl = downloadUrl;
        }

        @Override
        public String toString() {
            return label + " (" + fileName + ")";
        }
    }
}
