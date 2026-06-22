#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "whisper.h"

#define UNUSED(x) (void)(x)

JNIEXPORT jlong JNICALL
Java_com_floto_stt_model_LocalWhisperTranscriber_nativeInitContext(
        JNIEnv *env,
        jclass clazz,
        jstring model_path) {
    UNUSED(clazz);
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context_params params = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_floto_stt_model_LocalWhisperTranscriber_nativeFreeContext(
        JNIEnv *env,
        jclass clazz,
        jlong ptr) {
    UNUSED(env);
    UNUSED(clazz);
    if (ptr != 0) {
        whisper_free((struct whisper_context *) ptr);
    }
}

JNIEXPORT jstring JNICALL
Java_com_floto_stt_model_LocalWhisperTranscriber_nativeFullTranscribe(
        JNIEnv *env,
        jclass clazz,
        jlong ptr,
        jint threads,
        jfloatArray audio_data,
        jstring language) {
    UNUSED(clazz);
    if (ptr == 0 || audio_data == NULL) {
        return (*env)->NewStringUTF(env, "");
    }

    struct whisper_context *ctx = (struct whisper_context *) ptr;
    jfloat *samples = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jsize sample_count = (*env)->GetArrayLength(env, audio_data);

    const char *language_chars = NULL;
    if (language != NULL) {
        language_chars = (*env)->GetStringUTFChars(env, language, NULL);
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.n_threads = threads;
    if (language_chars != NULL && strlen(language_chars) > 0) {
        params.language = language_chars;
    }

    int rc = whisper_full(ctx, params, samples, sample_count);
    (*env)->ReleaseFloatArrayElements(env, audio_data, samples, JNI_ABORT);
    if (language_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, language, language_chars);
    }
    if (rc != 0) {
        return (*env)->NewStringUTF(env, "");
    }

    int segments = whisper_full_n_segments(ctx);
    size_t total_len = 1;
    for (int i = 0; i < segments; i++) {
        const char *segment = whisper_full_get_segment_text(ctx, i);
        if (segment != NULL) {
            total_len += strlen(segment) + 1;
        }
    }

    char *text = (char *) calloc(total_len, sizeof(char));
    if (text == NULL) {
        return (*env)->NewStringUTF(env, "");
    }

    for (int i = 0; i < segments; i++) {
        const char *segment = whisper_full_get_segment_text(ctx, i);
        if (segment != NULL) {
            strcat(text, segment);
            if (i + 1 < segments) {
                strcat(text, " ");
            }
        }
    }

    jstring result = (*env)->NewStringUTF(env, text);
    free(text);
    return result;
}
