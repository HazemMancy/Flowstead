/*
 * Flowstead — JNI bridge to the embedded engine host (app.flowstead.data.engine.local.NativeEngine).
 * Strings cross as UTF-8 byte arrays: JNI's own string functions use modified UTF-8, which can't
 * carry every character a compound name or a message might contain.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

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

/* Returns null on success, else the reason as UTF-8 bytes. */
JNIEXPORT jbyteArray JNICALL
Java_app_flowstead_data_engine_local_NativeEngine_start(JNIEnv *env, jclass cls, jbyteArray framework, jbyteArray app,
                                                        jbyteArray dwsim, jbyteArray natives) {
    (void)cls;
    char *f = copy_bytes(env, framework), *a = copy_bytes(env, app), *d = copy_bytes(env, dwsim), *n = copy_bytes(env, natives);
    const char *error = NULL;
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
