/*
 * ThermoDock — embedded engine host: runs the ThermoDock engine (and DWSIM) inside the calling
 * process on the .NET Mono runtime. Shared by the Linux test host and the Android JNI bridge.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef THERMODOCK_HOST_H
#define THERMODOCK_HOST_H

/*
 * Boots the runtime once. framework_dir holds the .NET base class library (System.*.dll,
 * System.Private.CoreLib.dll); app_dir holds ThermoDock.Engine.*.dll; dwsim_dir holds DWSIM's
 * assemblies and data (probed after the framework, so the BCL always wins); native_dirs is a
 * ':'-separated list searched for native libraries (runtime natives, libSkiaSharp...).
 * Returns 0, or -1 with a message in *error (static string).
 */
int fh_start(const char *framework_dir, const char *app_dir, const char *dwsim_dir, const char *native_dirs, const char **error);

/*
 * Calls ThermoDock.Engine.Worker.EmbeddedHost.<method>(arg), method "Init" or "Call", from any
 * thread. Returns a malloc'd UTF-8 JSON string (WorkerResponse) the caller frees, or NULL.
 */
char *fh_invoke(const char *method, const char *arg);

#endif
