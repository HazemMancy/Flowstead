/*
 * Flowstead — Linux test host for the embedded engine: speaks the worker's stdin/stdout protocol
 * (ready line, then one JSON request per line), so the same test drivers work against it.
 *   flowstead-embedded <framework_dir> <app_dir> <native_dirs> <dwsim_home>
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "flowstead_host.h"

int main(int argc, char **argv) {
    if (argc != 5) {
        fprintf(stderr, "usage: %s <framework_dir> <app_dir> <native_dirs> <dwsim_home>\n", argv[0]);
        return 2;
    }
    const char *error = NULL;
    if (fh_start(argv[1], argv[2], argv[4], argv[3], &error) != 0) {
        fprintf(stderr, "runtime: %s\n", error);
        return 3;
    }
    char *ready = fh_invoke("Init", argv[4]);
    if (!ready) return 3;
    printf("%s\n", ready);
    fflush(stdout);
    free(ready);
    size_t cap = 1 << 20;
    char *line = malloc(cap);
    ssize_t n;
    while ((n = getline(&line, &cap, stdin)) > 0) {
        if (line[n - 1] == '\n') line[n - 1] = 0;
        if (!line[0]) continue;
        char *r = fh_invoke("Call", line);
        printf("%s\n", r ? r : "{\"id\":-1,\"error\":\"runtime failure\"}");
        fflush(stdout);
        free(r);
    }
    free(line);
    return 0;
}
