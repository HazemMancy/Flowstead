// Flowstead engine tests — one API host with real DWSIM worker processes, shared by all tests.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Reflection;
using Flowstead.Engine.Api;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace Flowstead.Engine.Tests;

public sealed class EngineFixture : WebApplicationFactory<Program>, IAsyncLifetime
{
    public static string Meta(string key) =>
        typeof(EngineFixture).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>().First(a => a.Key == key).Value!;

    private readonly string _db = Path.Combine(Path.GetTempPath(), $"flowstead-test-{Guid.NewGuid():n}.db");

    public string ApiKey { get; private set; } = "";

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseSetting("Engine:WorkerPath", Path.GetFullPath(Meta("WorkerPath")));
        builder.UseSetting("Engine:Workers", "2");
        builder.UseSetting("Engine:SolveTimeoutSeconds", "60");
        builder.UseSetting("Engine:DatabasePath", _db);
        builder.UseSetting("Engine:RequestsPerMinute", "100000");
    }

    public async Task InitializeAsync()
    {
        ApiKey = Services.GetRequiredService<Store>().AddKey("test-device");
        await Services.GetRequiredService<WorkerPool>().StartAsync(CancellationToken.None);
    }

    public HttpClient Authed(double minutes = 3)
    {
        var c = CreateClient();
        c.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", ApiKey);
        c.Timeout = TimeSpan.FromMinutes(minutes);
        return c;
    }

    public new async Task DisposeAsync()
    {
        await base.DisposeAsync();
        foreach (var f in new[] { _db, _db + "-wal", _db + "-shm" }) File.Delete(f);
    }

    public static async Task<T> Json<T>(HttpResponseMessage r) =>
        (await r.Content.ReadFromJsonAsync<T>(Contracts.Json.Options))!;
}

[CollectionDefinition(Name)]
public sealed class EngineCollection : ICollectionFixture<EngineFixture>
{
    public const string Name = "engine";
}
