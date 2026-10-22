// Flowstead engine tests — profiles along units and phase envelopes (decision D50).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Nodes;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class ProfilesTests(EngineFixture engine, ITestOutputHelper output)
{
    private static readonly string Dir = Path.GetFullPath(EngineFixture.Meta("BenchmarksDir"));

    private async Task<SolveResult> Benchmark(string name)
    {
        var input = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(Dir, name, "input.json")))!;
        var fs = input["flowsheet"].Deserialize<Flowsheet>(Json.Options)!;
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.Equal(SolveStatus.Converged, r.Status);
        return r;
    }

    private static void SameLength(Dictionary<string, List<double>> p) =>
        Assert.Single(p.Values.Select(v => v.Count).Distinct());

    [Fact]
    public async Task PipeProfileRunsFromInletToOutlet()
    {
        var r = await Benchmark("09-pipe-and-orifice");
        var p = r.Objects["p"].Profiles!;
        SameLength(p);
        var x = p[ProfileKeys.Length];
        var pressure = p[ProfileKeys.Pressure];
        output.WriteLine($"{x.Count} points, {x[^1]} m, {pressure[0]} → {pressure[^1]} Pa");
        Assert.Equal(0, x[0]);
        Assert.True(x.Zip(x.Skip(1)).All(s => s.Second > s.First));
        Assert.Equal(r.Objects["in"].Properties["pressure"], pressure[0], 1.0);
        Assert.Equal(r.Objects["out"].Properties["pressure"], pressure[^1], r.Objects["p"].Properties["pressureDrop"] * 0.01);
    }

    [Fact]
    public async Task ColumnProfileRunsTopToBottom()
    {
        var r = await Benchmark("11-depropanizer");
        var t = r.Objects["t"];
        var p = t.Profiles!;
        SameLength(p);
        var temperature = p[ProfileKeys.Temperature];
        output.WriteLine("T: " + string.Join(", ", temperature.Select(v => v.ToString("0.0"))));
        output.WriteLine("V: " + string.Join(", ", p[ProfileKeys.VapourFlow].Select(v => v.ToString("0.000"))));
        output.WriteLine("L: " + string.Join(", ", p[ProfileKeys.LiquidFlow].Select(v => v.ToString("0.000"))));
        output.WriteLine($"d {r.Objects["d"].Properties["molarFlow"]} b {r.Objects["b"].Properties["molarFlow"]}");
        Assert.Equal(t.Properties["topTemperature"], temperature[0], 1e-6);
        Assert.Equal(t.Properties["bottomTemperature"], temperature[^1], 1e-6);
        Assert.True(temperature[^1] > temperature[0]);
        // Flows are mol/s, as the streams: the liquid leaving the reboiler is the bottoms product.
        Assert.Equal(r.Objects["b"].Properties["molarFlow"], p[ProfileKeys.LiquidFlow][^1], r.Objects["b"].Properties["molarFlow"] * 0.001);
    }

    [Fact]
    public async Task ExchangerProfileIsTheZoneAnalysis()
    {
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        SimObject Feed(string id, double t, double p, double kmolh) => new()
        {
            Id = id, Tag = id, Type = ObjectType.MaterialStream, Composition = water,
            Specs = new() { [SpecKeys.Temperature] = new(t, "K"), [SpecKeys.Pressure] = new(p, "Pa"), [SpecKeys.MolarFlow] = new(kmolh / 3.6, "kgmol/h") },
        };
        var fs = new Flowsheet
        {
            PropertyPackage = "Steam Tables (IAPWS-IF97)",
            Compounds = ["Water"],
            Objects =
            [
                Feed("a", 423.15, 1e5, 36), Feed("b", 303.15, 3e5, 720),
                new SimObject { Id = "e", Tag = "E-100", Type = ObjectType.HeatExchanger, Specs = new() { [SpecKeys.HotOutletTemperature] = new Spec(368.15, "K") } },
                new SimObject { Id = "ao", Tag = "ao", Type = ObjectType.MaterialStream }, new SimObject { Id = "bo", Tag = "bo", Type = ObjectType.MaterialStream },
            ],
            Connections = [new("a", 0, "e", 0), new("b", 0, "e", 1), new("e", 0, "ao", 0), new("e", 1, "bo", 0)],
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        var e = r.Objects["e"];
        var p = e.Profiles!;
        SameLength(p);
        Assert.Equal(21, p[ProfileKeys.HeatFlow].Count);
        Assert.Equal(0, p[ProfileKeys.HeatFlow][0]);
        Assert.Equal(Math.Abs(e.Properties["duty"]), p[ProfileKeys.HeatFlow][^1], 1e-6);
        Assert.Equal(423.15, p[ProfileKeys.HotTemperature][0], 0.01);
        Assert.Equal(e.Properties["coldOutletTemperature"], p[ProfileKeys.ColdTemperature][0], 0.01);
        var approach = p[ProfileKeys.HotTemperature].Zip(p[ProfileKeys.ColdTemperature]).Min(z => z.First - z.Second);
        Assert.Equal(e.Properties["minimumApproach"], approach, 1e-9);
    }

    [Fact]
    public async Task EnvelopeDewPointMatchesADewPointFlash()
    {
        var gas = new Dictionary<string, double> { ["Methane"] = 0.8, ["Ethane"] = 0.1, ["Propane"] = 0.05, ["N-butane"] = 0.03, ["N-hexane"] = 0.02 };
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = gas.Keys.ToList() };
        var env = await EngineFixture.Json<PhaseEnvelope>(await engine.Authed().PostAsJsonAsync("/phase-envelope", new PhaseEnvelopeRequest(fs, gas), Json.Options));
        output.WriteLine($"bubble {env.BubbleTemperature.Count} dew {env.DewTemperature.Count} crit {env.CriticalTemperature} K {env.CriticalPressure} Pa; " +
                         $"cricondenbar {env.CricondenbarPressure} Pa at {env.CricondenbarTemperature} K; cricondentherm {env.CricondenthermTemperature} K at {env.CricondenthermPressure} Pa");
        Assert.True(env.BubbleTemperature.Count > 5 && env.DewTemperature.Count > 5);
        Assert.True(env.CricondenthermTemperature >= env.CricondenbarTemperature);
        // A natural gas: the critical point sits below the cricondenbar and left of the cricondentherm.
        Assert.NotNull(env.CriticalTemperature);
        Assert.True(env.CriticalPressure <= env.CricondenbarPressure && env.CriticalTemperature <= env.CricondenthermTemperature);

        // The dew temperature at 20 bar on the curve (first crossing, below the cricondentherm) …
        double? onCurve = null;
        for (var i = 1; i < env.DewPressure.Count && onCurve is null; i++)
        {
            var (p0, p1) = (env.DewPressure[i - 1], env.DewPressure[i]);
            if ((p0 - 20e5) * (p1 - 20e5) <= 0 && p1 != p0)
                onCurve = env.DewTemperature[i - 1] + (20e5 - p0) / (p1 - p0) * (env.DewTemperature[i] - env.DewTemperature[i - 1]);
        }
        Assert.NotNull(onCurve);
        // … equals DWSIM's own dew-point flash of the same gas.
        var dew = new SimObject
        {
            Id = "s", Tag = "S", Type = ObjectType.MaterialStream, Composition = gas,
            Specs = new() { [SpecKeys.Pressure] = new(20e5, "Pa"), [SpecKeys.VapourFraction] = new(1, ""), [SpecKeys.MolarFlow] = new(1, "mol/s") },
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs with { Objects = [dew] }, Json.Options));
        output.WriteLine($"envelope {onCurve} K, flash {r.Objects["s"].Properties["temperature"]} K");
        Assert.Equal(r.Objects["s"].Properties["temperature"], onCurve!.Value, 1.0);
    }

    [Fact]
    public async Task EnvelopeRefusesAnUnknownCompound()
    {
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Methane"] };
        var res = await engine.Authed().PostAsJsonAsync("/phase-envelope", new PhaseEnvelopeRequest(fs, new() { ["Ethane"] = 1 }), Json.Options);
        Assert.Equal(System.Net.HttpStatusCode.UnprocessableEntity, res.StatusCode);
    }
}
