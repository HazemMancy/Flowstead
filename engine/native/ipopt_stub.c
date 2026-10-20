/*
 * Flowstead — a stand-in for the IPOPT 3.9 C interface (libIpopt39) that DWSIM's Cureos.Numerics binds to.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * IPOPT is not shipped with the engine. Without any libIpopt39, a DWSIM code path that creates an IPOPT
 * problem (some flash fallbacks, the Gibbs-minimisation flash) leaves a half-built object whose finalizer
 * calls FreeIpoptProblem, fails to load the library, and takes the whole process down from the finalizer
 * thread. With this library every solve reports Internal_Error, which DWSIM handles as an ordinary
 * "did not converge", and nothing crashes. Flowstead configures DWSIM's managed optimisers instead.
 */
#include <stdlib.h>

typedef double Number;
typedef int Index;
typedef int Int;
typedef int Bool;
typedef void *UserDataPtr;
typedef struct { int unused; } Problem;
typedef Problem *IpoptProblem;

#if defined(_WIN32)
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif

enum { Internal_Error = -199 };

EXPORT IpoptProblem CreateIpoptProblem(Index n, Number *x_L, Number *x_U, Index m, Number *g_L, Number *g_U,
                                       Index nele_jac, Index nele_hess, Index index_style,
                                       void *eval_f, void *eval_g, void *eval_grad_f, void *eval_jac_g, void *eval_h) {
    (void)n; (void)x_L; (void)x_U; (void)m; (void)g_L; (void)g_U; (void)nele_jac; (void)nele_hess;
    (void)index_style; (void)eval_f; (void)eval_g; (void)eval_grad_f; (void)eval_jac_g; (void)eval_h;
    return calloc(1, sizeof(Problem));
}

EXPORT void FreeIpoptProblem(IpoptProblem p) { free(p); }

EXPORT Bool AddIpoptStrOption(IpoptProblem p, char *keyword, char *val) { (void)p; (void)keyword; (void)val; return 1; }
EXPORT Bool AddIpoptNumOption(IpoptProblem p, char *keyword, Number val) { (void)p; (void)keyword; (void)val; return 1; }
EXPORT Bool AddIpoptIntOption(IpoptProblem p, char *keyword, Int val) { (void)p; (void)keyword; (void)val; return 1; }
EXPORT Bool OpenIpoptOutputFile(IpoptProblem p, char *file_name, Int print_level) { (void)p; (void)file_name; (void)print_level; return 1; }
EXPORT Bool SetIpoptProblemScaling(IpoptProblem p, Number obj_scaling, Number *x_scaling, Number *g_scaling) {
    (void)p; (void)obj_scaling; (void)x_scaling; (void)g_scaling; return 1;
}
EXPORT Bool SetIntermediateCallback(IpoptProblem p, void *cb) { (void)p; (void)cb; return 1; }

EXPORT int IpoptSolve(IpoptProblem p, Number *x, Number *g, Number *obj_val, Number *mult_g, Number *mult_x_L,
                      Number *mult_x_U, UserDataPtr user_data) {
    (void)p; (void)x; (void)g; (void)obj_val; (void)mult_g; (void)mult_x_L; (void)mult_x_U; (void)user_data;
    return Internal_Error;
}
