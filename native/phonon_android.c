#include <android/log.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include "pause_length.h"
#include "ptts.h"
#include "sonic.h"

#define SAMPLE_RATE PAUSE_SAMPLE_RATE
#define BLOCK_SAMPLES 960

typedef struct {
    JNIEnv *env;
    jobject sink;
    jmethodID accept;
    jbyteArray output;
    sonicStream sonic;
    PauseLength pause;
    float buffered[BLOCK_SAMPLES];
    size_t buffered_count;
} Render;

static bool send(Render *state, const float *samples, int count) {
    jbyte bytes[BLOCK_SAMPLES * 2];
    for (int i = 0; i < count; ++i) {
        float value = samples[i];
        if (!isfinite(value)) value = 0;
        int32_t pcm = (int32_t)(fminf(1.0f, fmaxf(-1.0f, value)) * 32767.f);
        bytes[2 * i] = (jbyte)(pcm & 255);
        bytes[2 * i + 1] = (jbyte)(((uint32_t)pcm >> 8) & 255);
    }
    JNIEnv *env = state->env;
    (*env)->SetByteArrayRegion(env, state->output, 0, count * 2, bytes);
    return !(*env)->ExceptionCheck(env) && (*env)->CallBooleanMethod(
        env, state->sink, state->accept, state->output, count * 2) == JNI_TRUE;
}

static bool emit_buffer(Render *state) {
    int count = (int)state->buffered_count;
    state->buffered_count = 0;
    return !count || send(state, state->buffered, count);
}

static bool enqueue(void *user, const float *pcm, size_t count) {
    Render *state = user;
    while (count) {
        size_t n = BLOCK_SAMPLES - state->buffered_count;
        if (n > count) n = count;
        memcpy(state->buffered + state->buffered_count, pcm, n * sizeof(float));
        state->buffered_count += n;
        if (state->buffered_count == BLOCK_SAMPLES && !emit_buffer(state)) return false;
        pcm += n;
        count -= n;
    }
    return true;
}

static bool output(Render *state, const float *pcm, int count) {
    if (state->pause.mode == PAUSE_DEFAULT) return send(state, pcm, count);
    return pause_push(&state->pause, pcm, (size_t)count, enqueue, state) && emit_buffer(state);
}

static bool drain(Render *state) {
    float buffer[BLOCK_SAMPLES];
    int count;
    while ((count = sonicReadFloatFromStream(state->sonic, buffer, BLOCK_SAMPLES)) > 0) {
        if (!output(state, buffer, count)) return false;
    }
    return true;
}

static bool frames(const float *pcm, size_t samples, void *user) {
    Render *state = user;
    JNIEnv *env = state->env;
    // Quiet audio can be withheld in the opt-in modes; keep cancellation live.
    if (state->pause.mode != PAUSE_DEFAULT &&
        ((*env)->CallBooleanMethod(env, state->sink, state->accept, state->output, 0) != JNI_TRUE ||
         (*env)->ExceptionCheck(env))) return false;
    while (samples) {
        int count = samples > BLOCK_SAMPLES ? BLOCK_SAMPLES : (int)samples;
        if (state->sonic) {
            if (!sonicWriteFloatToStream(state->sonic, pcm, count) || !drain(state)) return false;
        } else if (!output(state, pcm, count)) {
            return false;
        }
        pcm += count;
        samples -= count;
    }
    return true;
}

JNIEXPORT jlong JNICALL Java_me_onj_phonon_android_NativeEngine_open(
    JNIEnv *env, jclass cls, jbyteArray source) {
    (void)cls;
    jbyte *path = (*env)->GetByteArrayElements(env, source, NULL);
    if (!path) return 0;
    PttsHandle *handle = ptts_new((const char *)path, PTTS_UNIT_CPU, "en");
    (*env)->ReleaseByteArrayElements(env, source, path, JNI_ABORT);
    if (!handle) __android_log_write(ANDROID_LOG_ERROR, "Phonon", "Native model load failed");
    return (jlong)(intptr_t)handle;
}

JNIEXPORT jboolean JNICALL Java_me_onj_phonon_android_NativeEngine_render(
    JNIEnv *env, jclass cls, jlong pointer, jbyteArray text, jbyteArray lang,
    jbyteArray voice, jfloat speed, jfloat pitch, jint pause, jobject sink) {
    (void)cls;
    PttsHandle *handle = (PttsHandle *)(intptr_t)pointer;
    jbyte *content = NULL, *language = NULL, *name = NULL;
    Render state = {.env = env, .sink = sink, .pause.mode =
        pause == PAUSE_SHORTER || pause == PAUSE_LONGER ? (PauseMode)pause : PAUSE_DEFAULT};
    bool ok = false;
    if (!handle) return JNI_FALSE;
    content = (*env)->GetByteArrayElements(env, text, NULL);
    if (!content) goto done;
    language = (*env)->GetByteArrayElements(env, lang, NULL);
    if (!language) goto done;
    name = (*env)->GetByteArrayElements(env, voice, NULL);
    if (!name) goto done;
    jclass sink_type = (*env)->GetObjectClass(env, sink);
    state.accept = (*env)->GetMethodID(env, sink_type, "accept", "([BI)Z");
    if (!state.accept) goto done;
    state.output = (*env)->NewByteArray(env, BLOCK_SAMPLES * 2);
    if (!state.output) goto done;
    if (speed != 1.f || pitch != 1.f) {
        state.sonic = sonicCreateStream(SAMPLE_RATE, 1);
        if (!state.sonic) goto done;
        sonicSetSpeed(state.sonic, speed);
        sonicSetPitch(state.sonic, pitch);
    }
    if (!ptts_set_language(handle, (const char *)language) || !ptts_set_voice(handle, (const char *)name)) goto done;
    PttsResult result;
    ok = ptts_speak(handle, (const char *)content, frames, &state, &result);
    if (ok && !(*env)->ExceptionCheck(env)) {
        if (state.sonic) ok = sonicFlushStream(state.sonic) && drain(&state);
        if (ok && state.pause.mode != PAUSE_DEFAULT) {
            ok = pause_finish(&state.pause, enqueue, &state) && emit_buffer(&state);
        }
    }
done:
    if (state.sonic) sonicDestroyStream(state.sonic);
    if (name) (*env)->ReleaseByteArrayElements(env, voice, name, JNI_ABORT);
    if (language) (*env)->ReleaseByteArrayElements(env, lang, language, JNI_ABORT);
    if (content) (*env)->ReleaseByteArrayElements(env, text, content, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_me_onj_phonon_android_NativeEngine_dispose(
    JNIEnv *env, jclass cls, jlong pointer) {
    (void)env; (void)cls;
    ptts_free((PttsHandle *)(intptr_t)pointer);
}
