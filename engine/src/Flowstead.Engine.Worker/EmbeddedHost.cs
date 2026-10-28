// Flowstead engine — entry points for running the engine inside another process (the Android app),
// through an embedded .NET (Mono) runtime instead of a child process.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
//
// The native host calls Init once, then Call with one WorkerRequest JSON per solve and gets one
// WorkerResponse JSON back: the same protocol as the stdin/stdout worker, without the pipes.
// Calls are serialised: DWSIM keeps process-wide state (decision D12).

using System.Reflection;
using System.Text.Json;
using Flowstead.Engine.Contracts;
using Json = Flowstead.Engine.Contracts.Json;

namespace Flowstead.Engine.Worker;

public static class EmbeddedHost
{
    private static readonly object Gate = new();
    // object, not DwsimEngine: Mono resolves field types when it initialises this class, before
    // Init has had a chance to point it at DWSIM's assemblies.
    private static object? _engine;

    [ThreadStatic] private static HashSet<string>? Resolving;

    /// <summary>Loads DWSIM from <paramref name="dwsimHome"/>. Returns the ready response (id 0) or an error response.</summary>
    public static string Init(string dwsimHome)
    {
        lock (Gate)
        {
            try
            {
                if (_engine is null)
                {
                    if (!File.Exists(Path.Combine(dwsimHome, "DWSIM.Thermodynamics.dll")))
                        return Respond(new WorkerResponse(0, Error: $"DWSIM not found in '{dwsimHome}'."));
                    AppDomain.CurrentDomain.AssemblyResolve += (_, e) =>
                    {
                        var name = new AssemblyName(e.Name).Name!;
                        // A load from here can ask for the same name again (a version the file doesn't
                        // satisfy); answering that by loading again would recurse until the stack runs out.
                        var loaded = AppDomain.CurrentDomain.GetAssemblies().FirstOrDefault(a => a.GetName().Name == name);
                        if (loaded is not null) return loaded;
                        if (Resolving is { } busy && busy.Contains(name)) return null;
                        var path = Path.Combine(dwsimHome, name + ".dll");
                        if (!File.Exists(path)) return null;
                        (Resolving ??= []).Add(name);
                        try
                        {
                            Console.Error.WriteLine($"engine: loading {name} for {e.RequestingAssembly?.GetName().Name ?? "?"}");
                            return Assembly.LoadFrom(path);
                        }
                        finally { Resolving.Remove(name); }
                    };
                    Console.Error.WriteLine("engine: creating DWSIM");
                    Directory.SetCurrentDirectory(dwsimHome);
                    _engine = Create();
                }
                return Respond(new WorkerResponse(0, JsonSerializer.SerializeToNode(
                    new { ready = true, dwsimVersion = Version(_engine) }, Json.Options)));
            }
            catch (Exception e)
            {
                return Respond(new WorkerResponse(0, Error: "DWSIM failed to load: " + e.GetBaseException().Message));
            }
        }
    }

    /// <summary>Handles one WorkerRequest (JSON) and returns its WorkerResponse (JSON). Never throws.</summary>
    public static string Call(string requestJson)
    {
        lock (Gate)
        {
            WorkerRequest? req = null;
            try
            {
                if (_engine is null) return Respond(new WorkerResponse(-1, Error: "Engine not initialised."));
                req = JsonSerializer.Deserialize<WorkerRequest>(requestJson, Json.Options) ?? throw new InvalidDataException("Empty request");
                Console.Error.WriteLine($"engine: {req.Method} #{req.Id}");
                var answer = Respond(new WorkerResponse(req.Id, Handle(_engine, req)));
                Console.Error.WriteLine($"engine: {req.Method} #{req.Id} done");
                return answer;
            }
            catch (Exception e)
            {
                return Respond(new WorkerResponse(req?.Id ?? -1, Error: e.GetBaseException().Message));
            }
        }
    }

    // DWSIM types only appear inside these methods, so they load after the resolver is in place.
    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static object Create()
    {
        // No worker pool to stop a runaway solve on the phone: DWSIM's own limit (default an hour) instead.
        DWSIM.GlobalSettings.Settings.SolverTimeoutSeconds = 120;
        return new DwsimEngine();
    }

    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static string Version(object engine) => ((DwsimEngine)engine).DwsimVersion;

    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static System.Text.Json.Nodes.JsonNode? Handle(object engine, WorkerRequest req) => WorkerHost.Handle((DwsimEngine)engine, req);

    private static string Respond(WorkerResponse r) => JsonSerializer.Serialize(r, Json.Options);
}
