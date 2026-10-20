/*
 * Flowstead — embedded engine host (see flowstead_host.h).
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Mono in "netcore" mode: monovm_initialize() takes the trusted platform assemblies (the BCL and
 * our own assemblies) like the dotnet host does; DWSIM's assemblies are found by the managed
 * AssemblyResolve handler in EmbeddedHost.Init, exactly as in the server worker.
 */
#include "flowstead_host.h"

#include <dirent.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <mono/jit/jit.h>
#include <mono/jit/mono-private-unstable.h>
#include <mono/metadata/appdomain.h>
#include <mono/metadata/assembly.h>
#include <mono/metadata/class.h>
#include <mono/metadata/object.h>
#include <mono/metadata/threads.h>
#include <mono/utils/mono-publib.h>

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static MonoDomain *domain;
static MonoMethod *m_init, *m_call;

/* Appends every *.dll in dir to buf (':'-separated). */
static void add_dlls(char **buf, size_t *len, size_t *cap, const char *dir) {
    DIR *d = opendir(dir);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d))) {
        size_t n = strlen(e->d_name);
        if (n < 5 || strcmp(e->d_name + n - 4, ".dll") != 0) continue;
        size_t need = strlen(dir) + 1 + n + 2;
        if (*len + need >= *cap) {
            *cap = (*cap + need) * 2;
            *buf = realloc(*buf, *cap);
        }
        *len += (size_t)sprintf(*buf + *len, "%s%s/%s", *len ? ":" : "", dir, e->d_name);
    }
    closedir(d);
}

int fh_start(const char *framework_dir, const char *app_dir, const char *dwsim_dir, const char *native_dirs, const char **error) {
    pthread_mutex_lock(&lock);
    if (domain) {
        pthread_mutex_unlock(&lock);
        return 0;
    }
    size_t len = 0, cap = 1 << 16;
    char *tpa = malloc(cap);
    tpa[0] = 0;
    add_dlls(&tpa, &len, &cap, framework_dir);
    add_dlls(&tpa, &len, &cap, app_dir);

    /* Invariant globalization (no ICU to ship), but named cultures still construct: DWSIM asks for
     * en-US, which then formats like the invariant culture ("." decimals), as DWSIM expects. */
    const char *keys[] = {"TRUSTED_PLATFORM_ASSEMBLIES", "APP_PATHS", "NATIVE_DLL_SEARCH_DIRECTORIES",
                          "System.Globalization.Invariant", "System.Globalization.PredefinedCulturesOnly"};
    char app_paths[8192];
    snprintf(app_paths, sizeof app_paths, "%s:%s", app_dir, dwsim_dir);
    const char *values[] = {tpa, app_paths, native_dirs, "true", "false"};
    /* Inside an Android app ART owns the signal handlers; Mono must chain to them, not replace them. */
    mono_set_signal_chaining(1);
    mono_set_crash_chaining(1);
    if (monovm_initialize(5, keys, values) != 0) {
        *error = "monovm_initialize failed";
        goto fail;
    }
    domain = mono_jit_init_version("Flowstead", "v4.0.30319");
    if (!domain) {
        *error = "mono_jit_init_version failed";
        goto fail;
    }
    char path[4096];
    snprintf(path, sizeof path, "%s/Flowstead.Engine.Worker.dll", app_dir);
    MonoAssembly *asm_ = mono_domain_assembly_open(domain, path);
    if (!asm_) {
        *error = "Flowstead.Engine.Worker.dll not found";
        goto fail_domain;
    }
    MonoClass *k = mono_class_from_name(mono_assembly_get_image(asm_), "Flowstead.Engine.Worker", "EmbeddedHost");
    if (!k) {
        *error = "EmbeddedHost class not found";
        goto fail_domain;
    }
    m_init = mono_class_get_method_from_name(k, "Init", 1);
    m_call = mono_class_get_method_from_name(k, "Call", 1);
    if (!m_init || !m_call) {
        *error = "EmbeddedHost.Init/Call not found";
        goto fail_domain;
    }
    free(tpa);
    pthread_mutex_unlock(&lock);
    return 0;

fail_domain:
    domain = NULL; /* the runtime can't be shut down and restarted in-process; leave it */
fail:
    free(tpa);
    pthread_mutex_unlock(&lock);
    return -1;
}

char *fh_invoke(const char *method, const char *arg) {
    if (!domain) return NULL;
    mono_thread_attach(domain); /* no-op when already attached */
    MonoMethod *m = strcmp(method, "Init") == 0 ? m_init : m_call;
    void *args[1] = {mono_string_new(domain, arg)};
    MonoObject *exc = NULL;
    MonoString *res = (MonoString *)mono_runtime_invoke(m, NULL, args, &exc);
    if (exc) {
        /* EmbeddedHost never throws, so this is a runtime-level failure (type load, missing assembly). */
        MonoObject *inner = NULL;
        MonoString *text = mono_object_to_string(exc, &inner);
        char *msg = text && !inner ? mono_string_to_utf8(text) : NULL;
        fprintf(stderr, "flowstead-host: %s failed: %s\n", method, msg ? msg : "unknown exception");
        if (msg) mono_free(msg);
        return NULL;
    }
    if (!res) return NULL;
    char *utf8 = mono_string_to_utf8(res);
    char *out = strdup(utf8);
    mono_free(utf8);
    return out;
}
