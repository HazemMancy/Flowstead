// ThermoDock engine API — pool of DWSIM worker processes (decision D12).
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// DWSIM keeps process-wide static state and its cancellation is cooperative, so each solve runs in
// a separate long-lived worker process. A solve that overruns its timeout, or whose caller goes
// away, gets its worker killed and replaced. One slow flowsheet never blocks the others.

using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using ThermoDock.Engine.Contracts;
using Json = ThermoDock.Engine.Contracts.Json;

namespace ThermoDock.Engine.Api;

public sealed class EngineOptions
{
    /// <summary>Path to ThermoDock.Engine.Worker.dll.</summary>
    public string WorkerPath { get; set; } = Path.Combine(AppContext.BaseDirectory, "worker", "ThermoDock.Engine.Worker.dll");
    public int Workers { get; set; } = Math.Clamp(Environment.ProcessorCount / 2, 1, 4);
    public int SolveTimeoutSeconds { get; set; } = 60;
    public int StartupTimeoutSeconds { get; set; } = 120;
    public string DatabasePath { get; set; } = "thermodock.db";
}

public sealed class WorkerFailedException(string message) : Exception(message);

public sealed class WorkerPool : IAsyncDisposable
{
    private readonly EngineOptions _options;
    private readonly ILogger<WorkerPool> _log;
    private readonly Channel<WorkerProcess> _idle;
    private readonly SemaphoreSlim _startLock = new(1, 1);
    private int _started;

    public string DwsimVersion { get; private set; } = "unknown";

    public WorkerPool(EngineOptions options, ILogger<WorkerPool> log)
    {
        _options = options;
        _log = log;
        _idle = Channel.CreateUnbounded<WorkerProcess>();
    }

    /// <summary>Starts all workers. Safe to call more than once.</summary>
    public async Task StartAsync(CancellationToken ct)
    {
        await _startLock.WaitAsync(ct);
        try
        {
            if (_started > 0) return;
            var workers = await Task.WhenAll(Enumerable.Range(0, _options.Workers).Select(_ => StartWorkerAsync(ct)));
            foreach (var w in workers) _idle.Writer.TryWrite(w);
            _started = workers.Length;
        }
        finally
        {
            _startLock.Release();
        }
    }

    public async Task<T> CallAsync<T>(string method, object? parameters, TimeSpan timeout, CancellationToken ct)
    {
        await StartAsync(ct);
        var worker = await _idle.Reader.ReadAsync(ct);
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(timeout);
        try
        {
            var node = await worker.CallAsync(method, parameters, timeoutCts.Token);
            Return(worker);
            return node.Deserialize<T>(Json.Options) ?? throw new WorkerFailedException("Empty worker response");
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            _log.LogWarning("{Method} exceeded {Timeout}; restarting worker {Pid}", method, timeout, worker.Pid);
            Replace(worker);
            throw new TimeoutException($"The solve took longer than {timeout.TotalSeconds:0} s and was stopped.");
        }
        catch (OperationCanceledException)
        {
            Replace(worker); // caller went away mid-solve: the worker may still be busy, so recycle it
            throw;
        }
        catch (WorkerRequestException)
        {
            Return(worker); // the worker answered with an error but is healthy
            throw;
        }
        catch (Exception e)
        {
            _log.LogError(e, "Worker {Pid} failed during {Method}", worker.Pid, method);
            Replace(worker);
            throw new WorkerFailedException("The engine worker crashed: " + e.Message);
        }
    }

    private void Return(WorkerProcess w) => _idle.Writer.TryWrite(w);

    private void Replace(WorkerProcess w)
    {
        w.Kill();
        _ = Task.Run(async () =>
        {
            for (var attempt = 1; ; attempt++)
            {
                try
                {
                    _idle.Writer.TryWrite(await StartWorkerAsync(CancellationToken.None));
                    return;
                }
                catch (Exception e) when (attempt < 5)
                {
                    _log.LogError(e, "Restarting worker failed (attempt {Attempt})", attempt);
                    await Task.Delay(TimeSpan.FromSeconds(attempt * 2));
                }
            }
        });
    }

    private async Task<WorkerProcess> StartWorkerAsync(CancellationToken ct)
    {
        var w = await WorkerProcess.StartAsync(_options.WorkerPath, TimeSpan.FromSeconds(_options.StartupTimeoutSeconds), _log, ct);
        DwsimVersion = w.DwsimVersion;
        return w;
    }

    public async ValueTask DisposeAsync()
    {
        _idle.Writer.TryComplete();
        await foreach (var w in _idle.Reader.ReadAllAsync()) w.Kill();
    }
}

/// <summary>The worker answered the request with an error (bad input etc.), as opposed to crashing.</summary>
public sealed class WorkerRequestException(string message) : Exception(message);

internal sealed class WorkerProcess
{
    private readonly Process _process;
    private long _nextId;

    public int Pid => _process.Id;
    public string DwsimVersion { get; private set; } = "unknown";

    private WorkerProcess(Process p) => _process = p;

    public static async Task<WorkerProcess> StartAsync(string workerPath, TimeSpan startupTimeout, ILogger log, CancellationToken ct)
    {
        if (!File.Exists(workerPath)) throw new FileNotFoundException("Engine worker not found", workerPath);
        var psi = new ProcessStartInfo("dotnet", [workerPath])
        {
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
        };
        var p = Process.Start(psi) ?? throw new WorkerFailedException("Could not start the engine worker");
        p.ErrorDataReceived += (_, e) =>
        {
            if (!string.IsNullOrWhiteSpace(e.Data)) log.LogDebug("[worker {Pid}] {Line}", p.Id, e.Data);
        };
        p.BeginErrorReadLine();

        var w = new WorkerProcess(p);
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(startupTimeout);
        try
        {
            var ready = await w.ReadResponseAsync(cts.Token);
            if (ready.Error is not null) throw new WorkerFailedException(ready.Error);
            w.DwsimVersion = ready.Result?["dwsimVersion"]?.GetValue<string>() ?? "unknown";
            log.LogInformation("Engine worker {Pid} ready (DWSIM {Version})", p.Id, w.DwsimVersion);
            return w;
        }
        catch
        {
            w.Kill();
            throw;
        }
    }

    public async Task<JsonNode> CallAsync(string method, object? parameters, CancellationToken ct)
    {
        var id = Interlocked.Increment(ref _nextId);
        var req = new WorkerRequest(id, method, parameters is null ? null : JsonSerializer.SerializeToNode(parameters, Json.Options));
        await _process.StandardInput.WriteLineAsync(JsonSerializer.Serialize(req, Json.Options).AsMemory(), ct);
        await _process.StandardInput.FlushAsync(ct);
        var resp = await ReadResponseAsync(ct);
        if (resp.Id != id) throw new WorkerFailedException($"Worker answered request {resp.Id}, expected {id}");
        if (resp.Error is not null) throw new WorkerRequestException(resp.Error);
        return resp.Result ?? throw new WorkerFailedException("Worker returned no result");
    }

    private async Task<WorkerResponse> ReadResponseAsync(CancellationToken ct)
    {
        var line = await _process.StandardOutput.ReadLineAsync(ct)
                   ?? throw new WorkerFailedException($"Worker exited (code {(_process.HasExited ? _process.ExitCode : -1)})");
        return JsonSerializer.Deserialize<WorkerResponse>(line, Json.Options)
               ?? throw new WorkerFailedException("Unreadable worker response");
    }

    public void Kill()
    {
        try
        {
            if (!_process.HasExited) _process.Kill(entireProcessTree: true);
        }
        catch (InvalidOperationException)
        {
            // already gone
        }
        _process.Dispose();
    }
}
