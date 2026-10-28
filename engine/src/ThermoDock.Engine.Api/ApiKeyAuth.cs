// ThermoDock engine API — per-device API keys (decision D4) and the key-management CLI.
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later

using ThermoDock.Engine.Contracts;

namespace ThermoDock.Engine.Api;

/// <summary>Requires <c>Authorization: Bearer &lt;key&gt;</c> on every route except <c>/healthz</c>.</summary>
public sealed class ApiKeyAuth(RequestDelegate next, Store store)
{
    public const string DeviceItem = "thermodock.device";

    public async Task InvokeAsync(HttpContext ctx)
    {
        if (ctx.Request.Path.Equals("/healthz", StringComparison.Ordinal))
        {
            await next(ctx);
            return;
        }
        var header = ctx.Request.Headers.Authorization.ToString();
        var key = header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) ? header[7..].Trim() : "";
        var device = key.Length > 0 ? store.ValidateKey(key) : null;
        if (device is null)
        {
            ctx.Response.StatusCode = StatusCodes.Status401Unauthorized;
            await ctx.Response.WriteAsJsonAsync(new EngineError("unauthorized",
                key.Length == 0 ? "Missing API key." : "Unknown or revoked API key."));
            return;
        }
        ctx.Items[DeviceItem] = device;
        await next(ctx);
    }
}

public static class KeysCli
{
    public static int Run(Store store, string[] args)
    {
        switch (args)
        {
            case ["add", var device]:
                Console.WriteLine($"API key for '{device}' (shown once, store it in the app's Settings):");
                Console.WriteLine(store.AddKey(device));
                return 0;
            case ["revoke", var device]:
                Console.WriteLine($"Revoked {store.RevokeKeys(device)} key(s) for '{device}'.");
                return 0;
            case ["list"]:
                foreach (var (d, created, revoked) in store.ListKeys())
                    Console.WriteLine($"{d,-24} {created:yyyy-MM-dd HH:mm} {(revoked ? "revoked" : "active")}");
                return 0;
            default:
                Console.Error.WriteLine("usage: keys add <device> | keys revoke <device> | keys list");
                return 64;
        }
    }
}
