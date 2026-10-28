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

    public Task<List<HypotheticalProperties>> EstimateHypotheticalsAsync(List<Hypothetical> hypotheticals, CancellationToken ct) =>
        pool.CallAsync<List<HypotheticalProperties>>(WorkerMethods.EstimateHypotheticals, hypotheticals, LookupTimeout, ct);

    /// <summary>A phase envelope traces hundreds of flashes, so it gets the solve timeout.</summary>
    public Task<PhaseEnvelope> PhaseEnvelopeAsync(PhaseEnvelopeRequest req, CancellationToken ct) =>
        pool.CallAsync<PhaseEnvelope>(WorkerMethods.PhaseEnvelope, req, TimeSpan.FromSeconds(options.SolveTimeoutSeconds), ct);

    /// <summary>A PVT experiment runs many flashes, so it gets the solve timeout.</summary>
    public Task<PvtResult> PvtAsync(PvtRequest req, CancellationToken ct) =>
        pool.CallAsync<PvtResult>(WorkerMethods.Pvt, req, TimeSpan.FromSeconds(options.SolveTimeoutSeconds), ct);

    /// <summary>Relief sizing runs several flashes per scenario: the solve timeout.</summary>
    public Task<ReliefResult> ReliefAsync(ReliefRequest req, CancellationToken ct) =>
        pool.CallAsync<ReliefResult>(WorkerMethods.Relief, req, TimeSpan.FromSeconds(options.SolveTimeoutSeconds), ct);

    /// <summary>
    /// A blowdown is thousands of flashes (three-phase with water), and sizing the orifice runs it up to five times:
    /// fifteen times the solve timeout (15 minutes by default).
    /// </summary>
    public Task<DepressuringResult> DepressuringAsync(DepressuringRequest req, CancellationToken ct) =>
        pool.CallAsync<DepressuringResult>(WorkerMethods.Depressuring, req, TimeSpan.FromSeconds(15 * options.SolveTimeoutSeconds), ct);

    public Task<List<BinaryParameterInfo>> InteractionParametersAsync(Flowsheet fs, CancellationToken ct) =>
        pool.CallAsync<List<BinaryParameterInfo>>(WorkerMethods.InteractionParameters, fs, TimeSpan.FromSeconds(options.SolveTimeoutSeconds), ct);

    /// <summary>A regression re-runs the PVT experiments dozens of times: four times the solve timeout.</summary>
    public Task<PvtRegressionResult> PvtRegressionAsync(PvtRegressionRequest req, CancellationToken ct) =>
        pool.CallAsync<PvtRegressionResult>(WorkerMethods.PvtRegression, req, TimeSpan.FromSeconds(4 * options.SolveTimeoutSeconds), ct);

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
