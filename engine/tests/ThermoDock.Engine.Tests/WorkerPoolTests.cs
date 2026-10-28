// ThermoDock engine tests — solve timeouts kill and replace the stuck worker; other work carries on.
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later

using ThermoDock.Engine.Api;
using ThermoDock.Engine.Contracts;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace ThermoDock.Engine.Tests;

public sealed class WorkerPoolTests : IAsyncLifetime
{
    private WorkerPool _pool = null!;

    public async Task InitializeAsync()
    {
        Environment.SetEnvironmentVariable("THERMODOCK_WORKER_TEST_HOOKS", "1");
        _pool = new WorkerPool(new EngineOptions { WorkerPath = Path.GetFullPath(EngineFixture.Meta("WorkerPath")), Workers = 2 },
            NullLogger<WorkerPool>.Instance);
        await _pool.StartAsync(CancellationToken.None);
    }

    public async Task DisposeAsync() => await _pool.DisposeAsync();

    [Fact]
    public async Task TimeoutKillsTheWorkerAndThePoolRecovers()
    {
        await Assert.ThrowsAsync<TimeoutException>(() =>
            _pool.CallAsync<string>(WorkerMethods.TestSleep, 30_000, TimeSpan.FromSeconds(1), CancellationToken.None));

        // The other worker is still free while the killed one restarts.
        var pong = await _pool.CallAsync<string>(WorkerMethods.Ping, null, TimeSpan.FromSeconds(10), CancellationToken.None);
        Assert.Equal("pong", pong);

        // Both workers become available again: two concurrent calls complete.
        var both = await Task.WhenAll(
            _pool.CallAsync<string>(WorkerMethods.TestSleep, 200, TimeSpan.FromSeconds(120), CancellationToken.None),
            _pool.CallAsync<string>(WorkerMethods.TestSleep, 200, TimeSpan.FromSeconds(120), CancellationToken.None));
        Assert.All(both, s => Assert.Equal("slept", s));
    }

    [Fact]
    public async Task ASlowSolveDoesNotBlockAnotherCaller()
    {
        var slow = _pool.CallAsync<string>(WorkerMethods.TestSleep, 3_000, TimeSpan.FromSeconds(30), CancellationToken.None);
        var started = DateTime.UtcNow;
        Assert.Equal("pong", await _pool.CallAsync<string>(WorkerMethods.Ping, null, TimeSpan.FromSeconds(10), CancellationToken.None));
        Assert.True(DateTime.UtcNow - started < TimeSpan.FromSeconds(2), "ping waited for the slow call");
        Assert.Equal("slept", await slow);
    }

    [Fact]
    public async Task CallerCancellationRecyclesTheWorker()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(500));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            _pool.CallAsync<string>(WorkerMethods.TestSleep, 30_000, TimeSpan.FromSeconds(60), cts.Token));
        Assert.Equal("pong", await _pool.CallAsync<string>(WorkerMethods.Ping, null, TimeSpan.FromSeconds(120), CancellationToken.None));
    }
}
