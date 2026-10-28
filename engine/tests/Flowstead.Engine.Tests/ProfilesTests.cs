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
        Assert.True(r.Status == SolveStatus.Converged, string.Join("; ", r.Messages.Select(m => m.Text)));
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
    public async Task ColumnStagesCarryTheirPhasePropertiesForTraySizing()
    {
        var r = await Benchmark("11-depropanizer");
        var p = r.Objects["t"].Profiles!;
        SameLength(p);
        foreach (var k in new[] { ProfileKeys.VapourDensity, ProfileKeys.LiquidDensity, ProfileKeys.VapourMolarMass, ProfileKeys.LiquidMolarMass, ProfileKeys.SurfaceTension })
            output.WriteLine(k + ": " + string.Join(", ", p[k].Select(v => v.ToString("G4"))));
        // The bottoms product is the reboiler's liquid: the same fluid at the same state, so the same density and molar mass.
        var bottoms = r.Objects["b"];
        var rhoB = bottoms.Phases!.Values.First(ph => ph != bottoms.Phases["overall"] && ph.Properties.ContainsKey("density")).Properties["density"];
        Assert.Equal(rhoB, p[ProfileKeys.LiquidDensity][^1], rhoB * 0.02);
        Assert.Equal(bottoms.Properties.GetValueOrDefault("molecularWeight", bottoms.Phases["overall"].Properties["molecularWeight"]), p[ProfileKeys.LiquidMolarMass][^1], 0.5);
        // C2–iC5 at about 14 bar: a dense vapour, a light liquid, hydrocarbon surface tensions of a few mN/m.
        Assert.All(p[ProfileKeys.VapourDensity], v => Assert.InRange(v, 10, 80));
        Assert.All(p[ProfileKeys.LiquidDensity], v => Assert.InRange(v, 350, 650));
        Assert.All(p[ProfileKeys.SurfaceTension], v => Assert.InRange(v, 5e-4, 0.02));
        Assert.True(p[ProfileKeys.VapourMolarMass][0] < p[ProfileKeys.VapourMolarMass][^1]); // lighter at the top
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

    private async Task<PhaseEnvelope> Envelope(Dictionary<string, double> x, double? pressure, List<double>? qualities = null)
    {
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = x.Keys.ToList() };
        return await EngineFixture.Json<PhaseEnvelope>(await engine.Authed().PostAsJsonAsync(
            "/phase-envelope", new PhaseEnvelopeRequest(fs, x, qualities, Hydrates: true, Pressure: pressure), Json.Options));
    }

    [Theory]
    // Measured hydrate equilibria (Sloan and Koh, Clathrate Hydrates of Natural Gases, 3rd ed.): methane 2.6 MPa at
    // 273.2 K and 7.1 MPa at 283.2 K (sI); propane 0.21 MPa at 274.2 K (sII). The model gives 272.0, 282.5 and 274.24 K:
    // the 1 K or so of the Munck parameters, recorded in D52.
    [InlineData("Methane", 2.6e6, 273.2, "sI")]
    [InlineData("Methane", 7.1e6, 283.2, "sI")]
    [InlineData("Propane", 0.21e6, 274.2, "sII")]
    public async Task HydrateTemperatureMatchesMeasuredData(string gas, double p, double measured, string structure)
    {
        var env = await Envelope(new() { [gas] = 1.0 }, p);
        output.WriteLine($"{gas} at {p / 1e6} MPa: {env.HydrateTemperatureAtPressure} K ({env.HydrateStructureAtPressure}), measured {measured} K");
        Assert.Equal(structure, env.HydrateStructureAtPressure);
        Assert.Equal(measured, env.HydrateTemperatureAtPressure!.Value, 1.5);
    }

    [Theory]
    // GPSA: 20 wt % methanol, x_water 0.877, Nielsen–Bucklin −72 ln x = 9.47 K (Hammerschmidt would give 10.1 K);
    // 30 wt % MEG, Hammerschmidt 1297 × 30 / (62.07 × 70) = 8.96 K.
    [InlineData(HydrateInhibitors.Methanol, 0.20, 9.47)]
    [InlineData(HydrateInhibitors.Meg, 0.30, 8.96)]
    [InlineData(HydrateInhibitors.Teg, 0.30, 3.70)]
    public void InhibitorsDepressTheHydrateTemperature(string inhibitor, double w, double dT)
    {
        Assert.Equal(dT, HydrateInhibitors.Depression(inhibitor, w), 0.02);
        Assert.Equal(w, HydrateInhibitors.Required(inhibitor, dT), 0.002);
    }

    [Theory]
    // In the equilibrium (water activity from DWSIM's NRTL) the depression should sit near the GPSA correlations,
    // which fit measured data: 20 wt % methanol 9.5 K (Nielsen–Bucklin), 30 wt % MEG 9.0 K (Hammerschmidt).
    [InlineData(HydrateInhibitors.Methanol, 0.20)]
    [InlineData(HydrateInhibitors.Meg, 0.30)]
    public async Task InhibitorEntersTheEquilibriumAndTheStreamGetsItsDose(string inhibitor, double w)
    {
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Methane"] };
        // Methane at 7.1 MPa and 5 °C: about 4.4 K inside the hydrate region without inhibitor.
        var env = await EngineFixture.Json<PhaseEnvelope>(await engine.Authed().PostAsJsonAsync("/phase-envelope",
            new PhaseEnvelopeRequest(fs, new() { ["Methane"] = 1.0 }, Hydrates: true, Pressure: 7.1e6, Temperature: 278.15,
                Inhibitor: inhibitor, InhibitorWeightFraction: w), Json.Options));
        var gpsa = HydrateInhibitors.Depression(inhibitor, w);
        output.WriteLine($"{inhibitor} {w:P0}: depression {env.HydrateDepression:0.00} K (GPSA {gpsa:0.00} K); dose {env.RequiredInhibitorFraction:P1}; " + string.Join(" | ", env.Warnings));
        Assert.Equal(gpsa, env.HydrateDepression!.Value, 2.0);
        Assert.Equal(env.HydrateTemperatureAtPressure!.Value - env.HydrateDepression!.Value, env.InhibitedHydrateTemperatureAtPressure!.Value, 1e-9);
        // The inhibited curve is below the free-water one above 0 °C; far below it, where the solution would freeze, ice
        // governs and the inhibitor changes nothing (the gas–ice–hydrate line), so the curves meet there.
        Assert.True(env.InhibitedHydratePressure.Count > 10);
        for (var i = 0; i < env.InhibitedHydratePressure.Count; i++)
        {
            var free = env.HydrateTemperature[env.HydratePressure.IndexOf(env.InhibitedHydratePressure[i])];
            Assert.True(env.InhibitedHydrateTemperature[i] <= free + 1e-6);
            if (free > 275) Assert.True(env.InhibitedHydrateTemperature[i] < free - 5);
        }
        // The dose that brings the hydrate temperature down to the stream's 5 °C: a few wt %, less than the 20–30 % above.
        Assert.InRange(env.RequiredInhibitorFraction!.Value, 0.03, w);
    }

    [Fact]
    public async Task QualityLinesLieBetweenBubbleAndDewAndTheHydrateCurveRisesWithPressure()
    {
        var gas = new Dictionary<string, double> { ["Methane"] = 0.8, ["Ethane"] = 0.1, ["Propane"] = 0.05, ["N-butane"] = 0.03, ["N-hexane"] = 0.02 };
        var env = await Envelope(gas, 7e6, [0.5, 0.9]);
        output.WriteLine(string.Join("; ", env.QualityLines.Select(l => $"{l.Quality}: {l.Temperature.Count} points {l.Pressure[0]}..{l.Pressure[^1]}")));
        output.WriteLine($"hydrate at 7 MPa {env.HydrateTemperatureAtPressure} K {env.HydrateStructureAtPressure}; curve {env.HydrateTemperature.Count} points; " + string.Join(" | ", env.Warnings));
        Assert.Equal(2, env.QualityLines.Count);
        // At 3 MPa: bubble < 0.5 line < 0.9 line < dew.
        static double At(List<double> t, List<double> p, double target)
        {
            for (var i = 1; i < p.Count; i++)
                if ((p[i - 1] - target) * (p[i] - target) <= 0 && p[i] != p[i - 1]) return t[i - 1] + (target - p[i - 1]) / (p[i] - p[i - 1]) * (t[i] - t[i - 1]);
            return double.NaN;
        }
        var tb = At(env.BubbleTemperature, env.BubblePressure, 3e6);
        var t5 = At(env.QualityLines[0].Temperature, env.QualityLines[0].Pressure, 3e6);
        var t9 = At(env.QualityLines[1].Temperature, env.QualityLines[1].Pressure, 3e6);
        var td = At(env.DewTemperature, env.DewPressure, 3e6);
        output.WriteLine($"3 MPa: bubble {tb} < q0.5 {t5} < q0.9 {t9} < dew {td}");
        Assert.True(tb < t5 && t5 < t9 && t9 < td);
        // Hydrates: sII forms with propane and butane present, and the curve rises with pressure.
        Assert.Equal("sII", env.HydrateStructureAtPressure);
        Assert.True(env.HydrateTemperature.Count > 10);
        Assert.True(env.HydrateTemperature[^1] > env.HydrateTemperature[0]);
    }
}
