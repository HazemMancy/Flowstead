// Flowstead engine API — ASP.NET Core minimal API in front of the DWSIM worker pool.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// This program is free software: you can redistribute it and/or modify it under the terms of
// the GNU General Public License as published by the Free Software Foundation, either version 3
// of the License, or (at your option) any later version. See LICENSE.
//
// Usage:
//   Flowstead.Engine.Api                      serve (HTTP; put Caddy or another TLS proxy in front)
//   Flowstead.Engine.Api keys add <device>    create an API key for a device (printed once)
//   Flowstead.Engine.Api keys revoke <device> revoke all keys of a device
//   Flowstead.Engine.Api keys list

using System.Threading.RateLimiting;
using Flowstead.Engine.Api;
using Flowstead.Engine.Contracts;
using Microsoft.AspNetCore.Http.Json;
using Microsoft.AspNetCore.RateLimiting;

var builder = WebApplication.CreateBuilder(args);
var options = builder.Configuration.GetSection("Engine").Get<EngineOptions>() ?? new EngineOptions();

if (args.Length > 0 && args[0] == "keys")
{
    return KeysCli.Run(new Store(options), args[1..]);
}

builder.Services.AddSingleton(options);
builder.Services.AddSingleton<Store>();
builder.Services.AddSingleton<WorkerPool>();
builder.Services.AddSingleton<EngineService>();
builder.Services.AddHostedService<WorkerWarmup>();
builder.Services.Configure<JsonOptions>(o => Json.Configure(o.SerializerOptions));
// Requests per minute per device (or address); configurable so the test suite, one device, isn't throttled.
var perMinute = builder.Configuration.GetValue("Engine:RequestsPerMinute", 120);
builder.Services.AddRateLimiter(o =>
{
    o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    o.AddPolicy("per-key", ctx => RateLimitPartition.GetFixedWindowLimiter(
        ctx.Items[ApiKeyAuth.DeviceItem] as string ?? ctx.Connection.RemoteIpAddress?.ToString() ?? "anon",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = perMinute, Window = TimeSpan.FromMinutes(1), QueueLimit = 0 }));
});

var app = builder.Build();

app.UseMiddleware<ApiKeyAuth>();
app.UseRateLimiter();

// Liveness for container health checks: no auth, no engine call.
app.MapGet("/healthz", () => Results.Ok("ok")).AllowAnonymous();

var api = app.MapGroup("").RequireRateLimiting("per-key");

// Waits for the workers, so a passing check means the engine can really solve.
api.MapGet("/health", async (WorkerPool pool, CancellationToken ct) =>
{
    await pool.StartAsync(ct);
    return Results.Ok(new EngineHealth("ok", EngineInfo.Version, pool.DwsimVersion));
});

api.MapGet("/property-packages", async (EngineService e, CancellationToken ct) =>
    Results.Ok(await e.PropertyPackagesAsync(ct)));

api.MapGet("/compounds", async (string? query, int? limit, EngineService e, CancellationToken ct) =>
    Results.Ok(await e.SearchCompoundsAsync(query ?? "", limit ?? 25, ct)));

// Stateless solve: the app keeps its own master copy (decision D5) and sends it whole.
api.MapPost("/solve", async (Flowsheet fs, EngineService e, CancellationToken ct) =>
    Results.Ok(await e.SolveAsync(fs, ct)));

api.MapGet("/flowsheets", (Store s) => Results.Ok(s.List()));

api.MapPost("/flowsheets", (FlowsheetDocument doc, Store s) =>
{
    var stored = s.Create(doc.Name, doc.Flowsheet);
    return Results.Created($"/flowsheets/{stored.Id}", stored);
});

api.MapGet("/flowsheets/{id}", (string id, Store s) =>
    s.Get(id) is { } f ? Results.Ok(f) : NotFound(id));

api.MapPut("/flowsheets/{id}", (string id, FlowsheetDocument doc, Store s) =>
    s.Update(id, doc.Name, doc.Flowsheet) ? Results.Ok(s.Get(id)) : NotFound(id));

api.MapDelete("/flowsheets/{id}", (string id, Store s) =>
    s.Delete(id) ? Results.NoContent() : NotFound(id));

api.MapPost("/flowsheets/{id}/solve", async (string id, Store s, EngineService e, CancellationToken ct) =>
{
    if (s.Get(id) is not { } f) return NotFound(id);
    var result = await e.SolveAsync(f.Flowsheet, ct);
    s.SaveResult(id, result, Store.ModelHash(f.Flowsheet));
    return Results.Ok(result);
});

api.MapGet("/flowsheets/{id}/results", (string id, Store s) =>
{
    if (s.Get(id) is not { } f) return NotFound(id);
    if (s.GetResult(id) is not { } r)
        return Results.NotFound(new EngineError("not_solved", "This flowsheet has not been solved yet."));
    return Results.Ok(new FlowsheetResults(r.Result, r.SolvedAt, Stale: r.ModelHash != Store.ModelHash(f.Flowsheet)));
});

// DWSIM files (.dwxmz). JSON with base64 data so the app needs no multipart handling.
api.MapPost("/export", async (Flowsheet fs, EngineService e, CancellationToken ct) =>
    await FileCall(() => e.ExportDwsimAsync(fs, ct), "cannot_export"));

api.MapGet("/flowsheets/{id}/export", async (string id, Store s, EngineService e, CancellationToken ct) =>
{
    if (s.Get(id) is not { } f) return NotFound(id);
    try
    {
        var export = await e.ExportDwsimAsync(f.Flowsheet, ct);
        return Results.File(Convert.FromBase64String(export.Data), "application/octet-stream", SafeFileName(f.Name) + ".dwxmz");
    }
    catch (Exception x) when (x is WorkerRequestException or TimeoutException or WorkerFailedException)
    {
        return FileError(x, "cannot_export");
    }
});

api.MapPost("/import", async (DwsimFile file, EngineService e, CancellationToken ct) =>
    await FileCall(() => e.ImportDwsimAsync(file, ct), "invalid_file"));

// Hypothetical components: the constants DWSIM's correlations give (HYSYS "Estimate unknown properties").
api.MapPost("/hypotheticals/estimate", async (List<Hypothetical> hypotheticals, EngineService e, CancellationToken ct) =>
    await FileCall(() => e.EstimateHypotheticalsAsync(hypotheticals, ct), "invalid_hypothetical"));

// Molecular weights and ideal liquid densities at 60 °F, for compositions entered by mass or liquid volume (D78).
api.MapPost("/compounds/basis", async (Flowsheet fs, EngineService e, CancellationToken ct) =>
    await FileCall(() => e.CompoundBasisAsync(fs, ct), "invalid_fluid"));

// Phase envelope of one composition (D50).
api.MapPost("/phase-envelope", async (PhaseEnvelopeRequest req, EngineService e, CancellationToken ct) =>
    await FileCall(() => e.PhaseEnvelopeAsync(req, ct), "invalid_envelope"));

// PVT laboratory experiments (D53).
api.MapPost("/pvt", async (PvtRequest req, EngineService e, CancellationToken ct) =>
    await FileCall(() => e.PvtAsync(req, ct), "invalid_pvt"));

// Safety analysis: relief valve loads and sizing (D54–D56).
api.MapPost("/interaction-parameters", async (Flowsheet fs, EngineService e, CancellationToken ct) =>
    await FileCall(() => e.InteractionParametersAsync(fs, ct), "invalid_interaction_parameters"));

api.MapPost("/pvt-regression", async (PvtRegressionRequest req, EngineService e, CancellationToken ct) =>
    await FileCall(() => e.PvtRegressionAsync(req, ct), "invalid_pvt_regression"));

api.MapPost("/relief", async (ReliefRequest req, EngineService e, CancellationToken ct) =>
    await FileCall(() => e.ReliefAsync(req, ct), "invalid_relief"));

// Safety analysis: vessel depressuring (D57).
api.MapPost("/depressuring", async (DepressuringRequest req, EngineService e, CancellationToken ct) =>
    await FileCall(() => e.DepressuringAsync(req, ct), "invalid_depressuring"));

// A dynamic run from the steady state (D86).
api.MapPost("/dynamics", async (DynamicsRequest req, EngineService e, CancellationToken ct) =>
    await FileCall(() => e.DynamicsAsync(req, ct), "invalid_dynamics"));

app.Run();
return 0;

static async Task<IResult> FileCall<T>(Func<Task<T>> call, string code)
{
    try
    {
        return Results.Ok(await call());
    }
    catch (Exception x) when (x is WorkerRequestException or TimeoutException or WorkerFailedException)
    {
        return FileError(x, code);
    }
}

// A bad file or an unexportable flowsheet is the caller's to fix (422); a dead or slow engine is ours (503).
static IResult FileError(Exception x, string code) => x is WorkerRequestException
    ? Results.UnprocessableEntity(new EngineError(code, x.Message))
    : Results.Json(new EngineError("engine_unavailable", x.Message), statusCode: StatusCodes.Status503ServiceUnavailable);

static string SafeFileName(string name)
{
    var clean = new string(name.Select(c => char.IsLetterOrDigit(c) || c is '-' or '_' or ' ' ? c : '_').ToArray()).Trim();
    return clean.Length == 0 ? "flowsheet" : clean;
}

static IResult NotFound(string id) => Results.NotFound(new EngineError("not_found", $"No flowsheet '{id}'."));

public sealed record FlowsheetDocument(string Name, Flowsheet Flowsheet);

/// <summary>Stored results. <c>Stale</c> is true when the flowsheet changed after this solve.</summary>
public sealed record FlowsheetResults(SolveResult Result, DateTimeOffset SolvedAt, bool Stale);

public partial class Program;
