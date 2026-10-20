// Flowstead engine API — engine calls with timeouts, caching and plain-language failures.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using Flowstead.Engine.Contracts;

namespace Flowstead.Engine.Api;

public sealed class EngineService(WorkerPool pool, EngineOptions options)
{
    private static readonly TimeSpan LookupTimeout = TimeSpan.FromSeconds(30);
    private List<PropertyPackageInfo>? _packages;

    public async Task<List<PropertyPackageInfo>> PropertyPackagesAsync(CancellationToken ct) =>
        _packages ??= await pool.CallAsync<List<PropertyPackageInfo>>(WorkerMethods.PropertyPackages, null, LookupTimeout, ct);

    public Task<List<CompoundInfo>> SearchCompoundsAsync(string query, int limit, CancellationToken ct) =>
        pool.CallAsync<List<CompoundInfo>>(WorkerMethods.SearchCompounds,
            new SearchCompoundsParams(query, Math.Clamp(limit, 1, 200)), LookupTimeout, ct);

    public async Task<SolveResult> SolveAsync(Flowsheet fs, CancellationToken ct)
    {
        var timeout = TimeSpan.FromSeconds(options.SolveTimeoutSeconds);
        try
        {
            return await pool.CallAsync<SolveResult>(WorkerMethods.Solve, fs, timeout, ct);
        }
        catch (TimeoutException e)
        {
            return Failure(SolveStatus.Timeout, e.Message);
        }
        catch (Exception e) when (e is WorkerFailedException or WorkerRequestException)
        {
            return Failure(SolveStatus.Failed, e.Message);
        }
    }

    /// <summary>Export solves the flowsheet, so it gets the solve timeout.</summary>
    public Task<ExportResult> ExportDwsimAsync(Flowsheet fs, CancellationToken ct) =>
        pool.CallAsync<ExportResult>(WorkerMethods.ExportDwsim, fs, TimeSpan.FromSeconds(options.SolveTimeoutSeconds), ct);

    public Task<ImportResult> ImportDwsimAsync(DwsimFile file, CancellationToken ct) =>
        pool.CallAsync<ImportResult>(WorkerMethods.ImportDwsim, file, TimeSpan.FromSeconds(options.SolveTimeoutSeconds), ct);

    private SolveResult Failure(SolveStatus status, string message) => new()
    {
        Status = status,
        EngineVersion = EngineInfo.Version,
        DwsimVersion = pool.DwsimVersion,
        Messages = [new SolverMessage(MessageLevel.Error, message)],
    };
}

/// <summary>Starts the workers in the background so the first request doesn't pay DWSIM's load time.</summary>
public sealed class WorkerWarmup(WorkerPool pool, ILogger<WorkerWarmup> log) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            await pool.StartAsync(stoppingToken);
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            log.LogError(e, "Engine workers failed to start; requests will retry");
        }
    }
}
