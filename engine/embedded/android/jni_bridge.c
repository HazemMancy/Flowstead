/*
 * Flowstead — JNI bridge to the embedded engine host (app.flowstead.data.engine.local.NativeEngine).
 * Strings cross as UTF-8 byte arrays: JNI's own string functions use modified UTF-8, which can't
 * carry every character a compound name or a message might contain.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "flowstead_host.h"

static char *copy_bytes(JNIEnv *env, jbyteArray a) {
    jsize n = (*env)->GetArrayLength(env, a);
    char *s = malloc((size_t)n + 1);
    (*env)->GetByteArrayRegion(env, a, 0, n, (jbyte *)s);
    s[n] = 0;
    return s;
}

static jbyteArray to_bytes(JNIEnv *env, const char *s) {
    jsize n = (jsize)strlen(s);
    jbyteArray a = (*env)->NewByteArray(env, n);
    (*env)->SetByteArrayRegion(env, a, 0, n, (const jbyte *)s);
    return a;
}

/* stdout/stderr (the engine's progress lines, Mono's own log) go nowhere in an app: copy them to logcat. */
static void *pump(void *arg) {
    FILE *in = fdopen((int)(intptr_t)arg, "r");
    char line[2048];
    while (in && fgets(line, sizeof line, in)) __android_log_write(ANDROID_LOG_INFO, "FlowsteadEngine", line);
    return NULL;
}

static void logcat_stdio(void) {
    int fds[2];
    if (pipe(fds) != 0) return;
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    dup2(fds[1], 1);
    dup2(fds[1], 2);
    pthread_t t;
    if (pthread_create(&t, NULL, pump, (void *)(intptr_t)fds[0]) == 0) pthread_detach(t);
}

/* Returns null on success, else the reason as UTF-8 bytes. */
JNIEXPORT jbyteArray JNICALL
Java_app_flowstead_data_engine_local_NativeEngine_start(JNIEnv *env, jclass cls, jbyteArray framework, jbyteArray app,
                                                        jbyteArray dwsim, jbyteArray natives) {
    (void)cls;
    char *f = copy_bytes(env, framework), *a = copy_bytes(env, app), *d = copy_bytes(env, dwsim), *n = copy_bytes(env, natives);
    const char *error = NULL;
    logcat_stdio();
    /* Assembly loading in the log: the first thing to read when the engine fails on a device. */
    setenv("MONO_LOG_LEVEL", "info", 0);
    setenv("MONO_LOG_MASK", "asm", 0);
    int rc = fh_start(f, a, d, n, &error);
    free(f), free(a), free(d), free(n);
    return rc == 0 ? NULL : to_bytes(env, error ? error : "runtime failed to start");
}

/* EmbeddedHost.<method>(arg): the WorkerResponse JSON as UTF-8 bytes, or null on a runtime failure. */
JNIEXPORT jbyteArray JNICALL
Java_app_flowstead_data_engine_local_NativeEngine_invoke(JNIEnv *env, jclass cls, jbyteArray method, jbyteArray arg) {
    (void)cls;
    char *m = copy_bytes(env, method), *a = copy_bytes(env, arg);
    char *r = fh_invoke(m, a);
    free(m), free(a);
    if (!r) return NULL;
    jbyteArray out = to_bytes(env, r);
    free(r);
    return out;
}
