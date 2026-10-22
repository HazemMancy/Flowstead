// Flowstead engine worker — request loop.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Text.Json;
using System.Text.Json.Nodes;
using Flowstead.Engine.Contracts;
using Json = Flowstead.Engine.Contracts.Json;

namespace Flowstead.Engine.Worker;

public sealed class WorkerHost(TextWriter output)
{
    public int Run(TextReader input)
    {
        DwsimEngine engine;
        try
        {
            engine = new DwsimEngine();
        }
        catch (Exception e)
        {
            Write(new WorkerResponse(0, Error: "DWSIM failed to load: " + e.Message));
            return 3;
        }
        Write(new WorkerResponse(0, JsonSerializer.SerializeToNode(
            new { ready = true, dwsimVersion = engine.DwsimVersion }, Json.Options)));

        while (input.ReadLine() is { } line)
        {
            if (string.IsNullOrWhiteSpace(line)) continue;
            WorkerRequest? req = null;
            try
            {
                req = JsonSerializer.Deserialize<WorkerRequest>(line, Json.Options)
                      ?? throw new InvalidDataException("Empty request");
                Write(new WorkerResponse(req.Id, Handle(engine, req)));
            }
            catch (Exception e)
            {
                Write(new WorkerResponse(req?.Id ?? -1, Error: e.GetBaseException().Message));
            }
        }
        return 0;
    }

    internal static JsonNode? Handle(DwsimEngine engine, WorkerRequest req) => req.Method switch
    {
        WorkerMethods.Ping => JsonValue.Create("pong"),
        WorkerMethods.PropertyPackages => JsonSerializer.SerializeToNode(engine.PropertyPackages(), Json.Options),
        WorkerMethods.SearchCompounds => JsonSerializer.SerializeToNode(
            engine.SearchCompounds(Params<SearchCompoundsParams>(req)), Json.Options),
        WorkerMethods.Solve => JsonSerializer.SerializeToNode(engine.Solve(Params<Flowsheet>(req)), Json.Options),
        WorkerMethods.CompoundCatalogue => JsonSerializer.SerializeToNode(engine.CompoundCatalogue(), Json.Options),
        WorkerMethods.ExportDwsim => JsonSerializer.SerializeToNode(engine.ExportDwsim(Params<Flowsheet>(req)), Json.Options),
        WorkerMethods.ImportDwsim => JsonSerializer.SerializeToNode(engine.ImportDwsim(Params<DwsimFile>(req)), Json.Options),
        WorkerMethods.EstimateHypotheticals => JsonSerializer.SerializeToNode(engine.EstimateHypotheticals(Params<List<Hypothetical>>(req)), Json.Options),
        WorkerMethods.PhaseEnvelope => JsonSerializer.SerializeToNode(engine.PhaseEnvelope(Params<PhaseEnvelopeRequest>(req)), Json.Options),
        WorkerMethods.Pvt => JsonSerializer.SerializeToNode(engine.Pvt(Params<PvtRequest>(req)), Json.Options),
        WorkerMethods.TestSleep when TestHooks => Sleep(req.Params!.GetValue<int>()),
        _ => throw new InvalidOperationException($"Unknown method '{req.Method}'"),
    };

    private static readonly bool TestHooks = Environment.GetEnvironmentVariable("FLOWSTEAD_WORKER_TEST_HOOKS") == "1";

    private static JsonNode Sleep(int ms)
    {
        Thread.Sleep(ms);
        return JsonValue.Create("slept");
    }

    private static T Params<T>(WorkerRequest req) =>
        req.Params.Deserialize<T>(Json.Options) ?? throw new InvalidDataException($"Missing params for {req.Method}");

    private void Write(WorkerResponse r)
    {
        output.WriteLine(JsonSerializer.Serialize(r, Json.Options));
    }
}
