package com.floto.stt.model;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

public final class SystemOnDeviceRecognizer {
    private static final String TAG = "FlotoSystemRecognizer";
    private static final long MIN_SESSION_MILLIS = 4500L;
    private static final long COMPLETE_SILENCE_MILLIS = 1500L;
    private static final long POSSIBLY_COMPLETE_SILENCE_MILLIS = 2500L;
    private static final long RESTART_DELAY_MILLIS = 180L;

    public interface Callback {
        void onReady();

        void onPartialResult(String text);

        void onFinalResult(String text);

        void onError(String message);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder transcript = new StringBuilder();
    private SpeechRecognizer recognizer;
    private Callback activeCallback;
    private String activeLanguage = "";
    private String currentPartial = "";
    private boolean listening;
    private boolean stopRequested;
    private boolean finished;

    public SystemOnDeviceRecognizer(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isListening() {
        return listening;
    }

    public void start(String language, Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            callback.onError("System on-device recognition requires Android 12 or newer");
            return;
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            callback.onError("System on-device recognition is not available on this device");
            return;
        }
        destroy();
        stopRequested = false;
        finished = false;
        transcript.setLength(0);
        currentPartial = "";
        activeCallback = callback;
        String normalizedLanguage = normalizeLanguageTag(language);
        activeLanguage = normalizedLanguage;
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
        recognizer.setRecognitionListener(new RecognitionListener() {
            private boolean retriedWithDeviceDefault;
            private boolean speechStarted;
            private long waitStartedAt = SystemClock.elapsedRealtime();
            private long sessionStartedAt;

            @Override
            public void onReadyForSpeech(Bundle params) {
                if (finished) {
                    return;
                }
                listening = true;
                sessionStartedAt = SystemClock.elapsedRealtime();
                Log.d(TAG, "onReadyForSpeech waitMs=" + elapsedSince(waitStartedAt));
                callback.onReady();
            }

            @Override
            public void onBeginningOfSpeech() {
                if (finished) {
                    return;
                }
                speechStarted = true;
                Log.d(TAG, "onBeginningOfSpeech waitMs=" + elapsedSince(waitStartedAt));
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                if (finished) {
                    return;
                }
                Log.d(TAG, "onEndOfSpeech sessionMs=" + elapsedSince(sessionStartedAt));
            }

            @Override
            public void onError(int error) {
                if (finished) {
                    return;
                }
                Log.d(TAG, "onError error=" + error
                        + " speechStarted=" + speechStarted
                        + " hasTranscript=" + hasTranscript()
                        + " stopRequested=" + stopRequested
                        + " waitMs=" + elapsedSince(waitStartedAt)
                        + " sessionMs=" + elapsedSince(sessionStartedAt));
                if (stopRequested) {
                    finishWithTranscript();
                    return;
                }
                if (isLanguageError(error) && !retriedWithDeviceDefault && !normalizedLanguage.isEmpty()) {
                    retriedWithDeviceDefault = true;
                    activeLanguage = "";
                    Log.d(TAG, "Retrying with device default language after language error " + error);
                    restartListening(activeLanguage);
                    return;
                }
                if (isManualDictationRecoverableError(error)) {
                    if (isNoTextError(error)) {
                        currentPartial = "";
                    }
                    Log.d(TAG, "Recovering manual dictation after recognizer error " + error);
                    restartListening(activeLanguage);
                    return;
                }
                failWithError(errorMessage(error));
            }

            @Override
            public void onResults(Bundle results) {
                if (finished) {
                    return;
                }
                String result = bestResult(results);
                if (!result.isEmpty()) {
                    appendSegment(result);
                }
                Log.d(TAG, "onResults empty=" + result.isEmpty()
                        + " speechStarted=" + speechStarted
                        + " hasTranscript=" + hasTranscript()
                        + " stopRequested=" + stopRequested
                        + " waitMs=" + elapsedSince(waitStartedAt)
                        + " sessionMs=" + elapsedSince(sessionStartedAt));
                if (stopRequested) {
                    finishWithTranscript();
                    return;
                }
                Log.d(TAG, "Segment completed; restarting manual dictation");
                restartListening(activeLanguage);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (finished) {
                    return;
                }
                String partial = bestResult(partialResults);
                if (!partial.isEmpty()) {
                    speechStarted = true;
                    currentPartial = partial;
                }
                callback.onPartialResult(combinedTranscript());
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
        listening = true;
        recognizer.startListening(createIntent(normalizedLanguage));
    }

    public void stop() {
        stopRequested = true;
        if (recognizer != null && listening) {
            recognizer.stopListening();
            mainHandler.postDelayed(() -> {
                if (stopRequested && !finished) {
                    Log.d(TAG, "Stop callback timed out; finishing with current transcript");
                    finishWithTranscript();
                }
            }, 1500L);
        } else {
            finishWithTranscript();
        }
    }

    public void destroy() {
        finished = true;
        releaseRecognizer();
        resetSession();
    }

    private void releaseRecognizer() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        listening = false;
    }

    private void resetSession() {
        stopRequested = false;
        activeCallback = null;
        activeLanguage = "";
        currentPartial = "";
        transcript.setLength(0);
    }

    private void restartListening(String language) {
        if (recognizer == null || stopRequested || finished) {
            return;
        }
        listening = true;
        mainHandler.postDelayed(() -> {
            if (recognizer != null && !stopRequested && !finished) {
                recognizer.startListening(createIntent(language));
            }
        }, RESTART_DELAY_MILLIS);
    }

    private void appendSegment(String segment) {
        String value = segment == null ? "" : segment.trim();
        if (value.isEmpty()) {
            return;
        }
        if (transcript.length() > 0) {
            transcript.append(' ');
        }
        transcript.append(value);
        currentPartial = "";
    }

    private String combinedTranscript() {
        String base = transcript.toString().trim();
        String partial = currentPartial.trim();
        if (partial.isEmpty()) {
            return base;
        }
        if (base.isEmpty() || base.endsWith(partial)) {
            return base.isEmpty() ? partial : base;
        }
        return base + " " + partial;
    }

    private boolean hasTranscript() {
        return !combinedTranscript().isEmpty();
    }

    private void finishWithTranscript() {
        if (finished) {
            return;
        }
        finished = true;
        String result = combinedTranscript();
        Callback callback = activeCallback;
        releaseRecognizer();
        resetSession();
        if (callback != null) {
            callback.onFinalResult(result);
        }
    }

    private void failWithError(String message) {
        if (finished) {
            return;
        }
        finished = true;
        Callback callback = activeCallback;
        releaseRecognizer();
        resetSession();
        if (callback != null) {
            callback.onError(message);
        }
    }

    private Intent createIntent(String language) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_SESSION_MILLIS);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MILLIS);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, POSSIBLY_COMPLETE_SILENCE_MILLIS);
        if (language != null && !language.trim().isEmpty()) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.trim());
        }
        return intent;
    }

    private String normalizeLanguageTag(String language) {
        String value = language == null ? "" : language.trim();
        if (value.isEmpty()) {
            return Locale.getDefault().toLanguageTag();
        }
        String lower = value.toLowerCase(Locale.US);
        switch (lower) {
            case "ko":
                return "ko-KR";
            case "en":
                return "en-US";
            case "ja":
                return "ja-JP";
            case "zh":
                return "zh-CN";
            case "de":
                return "de-DE";
            case "fr":
                return "fr-FR";
            case "es":
                return "es-ES";
            default:
                return value;
        }
    }

    private boolean isLanguageError(int error) {
        return error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE;
    }

    private boolean isManualDictationRecoverableError(int error) {
        return isNoTextError(error)
                || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
    }

    private boolean isNoTextError(int error) {
        return error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_NO_MATCH;
    }

    private long elapsedSince(long startedAt) {
        if (startedAt == 0L) {
            return 0L;
        }
        return SystemClock.elapsedRealtime() - startedAt;
    }

    private String bestResult(Bundle results) {
        if (results == null) {
            return "";
        }
        ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts == null || texts.isEmpty() || texts.get(0) == null) {
            return "";
        }
        return texts.get(0).trim();
    }

    private String errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Speech recognizer client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Microphone permission is required";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "No speech recognized";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Speech recognizer is busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "Speech recognizer server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech detected";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                return "Too many speech recognition requests";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "Speech recognizer disconnected";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                return "Selected language is not supported by the system on-device recognizer";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "Selected language is not downloaded or available for system on-device recognition";
            case SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT:
                return "Could not check system speech recognition support";
            case SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS:
                return "Could not listen for speech model download events";
            default:
                return "Speech recognizer error " + error;
        }
    }
}
