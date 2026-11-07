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

    // ---- D117: an envelope that did not close, and quality lines checked point by point ----

    private static (Flowsheet Fs, Dictionary<string, double> X) Condensate(string hypo, double mw, double sg, double plus, Dictionary<string, double> rest)
    {
        var x = new Dictionary<string, double>(rest) { [hypo] = plus };
        var fs = new Flowsheet
        {
            PropertyPackage = "Peng-Robinson (PR)", Compounds = x.Keys.ToList(),
            Hypotheticals = [new Hypothetical { Name = hypo, MolecularWeight = mw, SpecificGravity = sg }],
        };
        return (fs, x);
    }

    /// <summary>DWSIM's P-T flash of the fluid: its vapour fraction at (t, p).</summary>
    private async Task<double> VapourFraction(Flowsheet fs, Dictionary<string, double> x, double t, double p)
    {
        var s = new SimObject
        {
            Id = "s", Tag = "S", Type = ObjectType.MaterialStream, Composition = x,
            Specs = new() { [SpecKeys.Temperature] = new(t, "K"), [SpecKeys.Pressure] = new(p, "Pa"), [SpecKeys.MolarFlow] = new(1, "mol/s") },
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs with { Objects = [s] }, Json.Options));
        Assert.True(r.Objects.ContainsKey("s"), string.Join("; ", r.Messages.Select(m => m.Text)));
        return r.Objects["s"].Properties["vapourFraction"];
    }

    [Fact]
    public async Task AnEnvelopeThatDidNotCloseReportsNoCriticalPointOrCricondenbar()
    {
        // A rich condensate with 2.5 % C7+ (MW 120, SG 0.77): DWSIM traces the bubble curve to a stop near 106 bar at
        // -49 °C and the dew curve separately; before D117 that stop was reported as the critical point and cricondenbar.
        var (fs, x) = Condensate("C7+", 120, 0.77, 0.025, new() { ["Nitrogen"] = 0.01, ["Carbon dioxide"] = 0.02, ["Methane"] = 0.80, ["Ethane"] = 0.07, ["Propane"] = 0.04, ["N-butane"] = 0.02, ["N-pentane"] = 0.015 });
        var env = await EngineFixture.Json<PhaseEnvelope>(await engine.Authed().PostAsJsonAsync("/phase-envelope", new PhaseEnvelopeRequest(fs, x, [0.5]), Json.Options));
        output.WriteLine($"bubble {env.BubbleTemperature.Count} dew {env.DewTemperature.Count}; crit {env.CriticalTemperature}; bar {env.CricondenbarPressure}; " + string.Join(" | ", env.Warnings));
        Assert.True(env.BubbleTemperature.Count > 5 && env.DewTemperature.Count > 5);
        Assert.Null(env.CriticalTemperature); Assert.Null(env.CriticalPressure);
        Assert.Null(env.CricondenbarPressure); Assert.Null(env.CricondenthermTemperature);
        Assert.Contains(env.Warnings, w => w.Contains("did not close"));
        // Independent check that the trace is incomplete: a P-T flash at 40 °C and 150 bar, above every pressure on the
        // traced curves, still finds two phases, so the envelope goes higher and no traced point is the cricondenbar.
        var highest = env.BubblePressure.Concat(env.DewPressure).Max();
        Assert.True(150e5 > highest);
        var v = await VapourFraction(fs, x, 313.15, 150e5);
        output.WriteLine($"P-T flash at 40 °C, 150 bar: vapour fraction {v}; highest traced {highest / 1e5:0.0} bar");
        Assert.InRange(v, 1e-4, 1 - 1e-4);
    }

    [Fact]
    public async Task AClosedEnvelopeKeepsItsCricondenbarAndEveryQualityPointIsAFlash()
    {
        // A natural gas with 1 % C6+ (MW 90, SG 0.71): DWSIM closes the envelope at the critical point.
        var (fs, x) = Condensate("C6+", 90, 0.71, 0.01, new() { ["Nitrogen"] = 0.01, ["Carbon dioxide"] = 0.02, ["Methane"] = 0.84, ["Ethane"] = 0.06, ["Propane"] = 0.03, ["N-butane"] = 0.02, ["N-pentane"] = 0.01 });
        var q = new List<double> { 0.1, 0.25, 0.5, 0.75, 0.9 };
        var env = await EngineFixture.Json<PhaseEnvelope>(await engine.Authed().PostAsJsonAsync("/phase-envelope", new PhaseEnvelopeRequest(fs, x, q), Json.Options));
        output.WriteLine($"crit {env.CriticalTemperature} K {env.CriticalPressure} Pa; bar {env.CricondenbarPressure} Pa at {env.CricondenbarTemperature} K; " +
                         string.Join("; ", env.QualityLines.Select(l => $"{l.Quality}: {l.Temperature.Count} pts")) + " | " + string.Join(" | ", env.Warnings));
        Assert.NotNull(env.CriticalTemperature);
        Assert.NotNull(env.CricondenbarPressure);
        Assert.DoesNotContain(env.Warnings, w => w.Contains("did not close"));
        // The cricondenbar is the top of the two-phase region: P-T flashes at its temperature find two phases 2 % below
        // it and one phase 2 % above it.
        var (tb, pb) = (env.CricondenbarTemperature!.Value, env.CricondenbarPressure!.Value);
        var below = await VapourFraction(fs, x, tb, pb * 0.98);
        var above = await VapourFraction(fs, x, tb, pb * 1.02);
        output.WriteLine($"at the cricondenbar temperature: vapour fraction {below} at -2 %, {above} at +2 %");
        Assert.InRange(below, 1e-4, 1 - 1e-4);
        Assert.True(above < 1e-6 || above > 1 - 1e-6);
        // No quality point is DWSIM's give-up value, methane's critical temperature (190.56 K, NIST), and each line
        // stays within the envelope's temperature range.
        var tMin = env.BubbleTemperature.Min() - 1;
        foreach (var l in env.QualityLines)
        {
            Assert.DoesNotContain(l.Temperature, t => Math.Abs(t - 190.56) < 0.2);
            Assert.All(l.Temperature, t => Assert.InRange(t, tMin, env.CricondenthermTemperature!.Value + 1));
        }
    }

    // ---- D118: a wet gas's envelope on a water-free basis, and its water dew line ----

    private static readonly Dictionary<string, double> WetGas = new()
    {
        ["Nitrogen"] = 0.01, ["Carbon dioxide"] = 0.015, ["Methane"] = 0.82, ["Ethane"] = 0.06, ["Propane"] = 0.03,
        ["N-butane"] = 0.015, ["N-pentane"] = 0.01, ["N-hexane"] = 0.005, ["Water"] = 0.035,
    };

    private static Flowsheet Pr(IEnumerable<string> compounds) => new() { PropertyPackage = "Peng-Robinson (PR)", Compounds = compounds.ToList() };

    /// <summary>IAPWS-IF97 saturation temperature of water (K) at <paramref name="p"/> (Pa), its backward equation (31).</summary>
    private static double IapwsSaturationTemperature(double p)
    {
        double[] n = [0, 0.11670521452767e4, -0.72421316703206e6, -0.17073846940092e2, 0.12020824702470e5, -0.32325550322333e7,
                      0.14915108613530e2, -0.48232657361591e4, 0.40511340542057e6, -0.23855557567849, 0.65017534844798e3];
        var b = Math.Pow(p / 1e6, 0.25);
        var e = b * b + n[3] * b + n[6];
        var f = n[1] * b * b + n[4] * b + n[7];
        var g = n[2] * b * b + n[5] * b + n[8];
        var d = 2 * g / (-f - Math.Sqrt(f * f - 4 * e * g));
        return (n[10] + d - Math.Sqrt((n[10] + d) * (n[10] + d) - 4 * (n[9] + n[10] * d))) / 2;
    }

    [Fact]
    public async Task AWetGasEnvelopeIsTheWaterFreeOneAndCloses()
    {
        var fs = Pr(WetGas.Keys);
        var dry = WetGas.Where(c => c.Key != "Water").ToDictionary(c => c.Key, c => c.Value / (1 - WetGas["Water"]));
        var q = new List<double> { 0.5 };
        var wet = await EngineFixture.Json<PhaseEnvelope>(await engine.Authed().PostAsJsonAsync("/phase-envelope",
            new PhaseEnvelopeRequest(fs, WetGas, q, Hydrates: true, Pressure: 6e5, Temperature: 311.15), Json.Options));
        var alone = await EngineFixture.Json<PhaseEnvelope>(await engine.Authed().PostAsJsonAsync("/phase-envelope",
            new PhaseEnvelopeRequest(Pr(dry.Keys), dry, q), Json.Options));
        output.WriteLine($"wet: crit {wet.CriticalTemperature} K {wet.CriticalPressure} Pa; bar {wet.CricondenbarPressure}; water dew {wet.WaterDewTemperature.Count} pts, " +
                         $"{wet.WaterDewTemperatureAtPressure} K at 6 bar | " + string.Join(" | ", wet.Warnings));
        Assert.True(wet.WaterFree);
        Assert.False(alone.WaterFree);
        Assert.NotNull(wet.CriticalTemperature);
        Assert.NotNull(wet.CricondenbarPressure);
        Assert.DoesNotContain(wet.Warnings, w => w.Contains("did not close"));
        Assert.Contains(wet.Warnings, w => w.Contains("water-free basis"));
        // The envelope is the water-free gas's own: the same curves and points as that gas traced on its own.
        // (within 0.01 %: the water stays in the package at zero, which moves the last points near the critical point by a few ppm)
        static void Same(List<double> a, List<double> b) { Assert.Equal(a.Count, b.Count); Assert.All(a.Zip(b), x => Assert.Equal(x.First, x.Second, x.First * 1e-4)); }
        Same(alone.BubbleTemperature, wet.BubbleTemperature);
        Same(alone.DewPressure, wet.DewPressure);
        Assert.Equal(alone.CricondenbarPressure!.Value, wet.CricondenbarPressure!.Value, 10.0);
        Assert.Equal(alone.CricondenthermTemperature!.Value, wet.CricondenthermTemperature!.Value, 1e-4);
        // Independent check of the hydrocarbon envelope: at the cricondenbar temperature a P-T flash of the dry gas finds two
        // phases 2 % below it and one phase 2 % above it.
        var (tb, pb) = (wet.CricondenbarTemperature!.Value, wet.CricondenbarPressure!.Value);
        var below = await VapourFraction(Pr(dry.Keys), dry, tb, pb * 0.98);
        var above = await VapourFraction(Pr(dry.Keys), dry, tb, pb * 1.02);
        output.WriteLine($"dry gas at the cricondenbar temperature: vapour fraction {below} at -2 %, {above} at +2 %");
        Assert.InRange(below, 1e-4, 1 - 1e-4);
        Assert.True(above < 1e-6 || above > 1 - 1e-6);
        // Hydrates are unchanged: the model already takes the water-free gas with free water present.
        Assert.NotNull(wet.HydrateTemperatureAtPressure);
        Assert.True(wet.HydrateTemperature.Count > 10);
    }

    [Fact]
    public async Task TheWaterDewLineMatchesIapwsAtLowPressureAndBukacekAtPressure()
    {
        var fs = Pr(WetGas.Keys);
        var env = await EngineFixture.Json<PhaseEnvelope>(await engine.Authed().PostAsJsonAsync("/phase-envelope",
            new PhaseEnvelopeRequest(fs, WetGas, [], Pressure: 5e5), Json.Options));
        output.WriteLine(string.Join(", ", env.WaterDewTemperature.Zip(env.WaterDewPressure).Select(x => $"{x.Second / 1e5:0.00} bar {x.First - 273.15:0.00} °C")));
        Assert.True(env.WaterDewTemperature.Count > 20);
        Assert.True(env.WaterDewTemperature.Zip(env.WaterDewTemperature.Skip(1)).All(x => x.Second > x.First), "the dew point rises with pressure");
        // Up to 5 bar the gas is nearly ideal: the water's partial pressure is its vapour pressure, y_w·P = p_sat(T),
        // with p_sat from IAPWS-IF97. Peng-Robinson's water and the small gas non-ideality agree within 1 K.
        var yw = WetGas["Water"] / WetGas.Values.Sum();
        var lows = env.WaterDewTemperature.Zip(env.WaterDewPressure).Where(x => x.Second <= 5.01e5).ToList();
        Assert.True(lows.Count >= 5);
        foreach (var (t, p) in lows)
        {
            var reference = IapwsSaturationTemperature(yw * p);
            output.WriteLine($"{p / 1e5:0.000} bar: engine {t - 273.15:0.00} °C, IAPWS ideal {reference - 273.15:0.00} °C");
            Assert.InRange(t, reference - 1.0, reference + 1.0);
        }
        Assert.InRange(env.WaterDewTemperatureAtPressure!.Value, IapwsSaturationTemperature(yw * 5e5) - 1.0, IapwsSaturationTemperature(yw * 5e5) + 1.0);
        // At pressure the gas is not ideal (it holds more water than y_w = p_sat/P). Reference: Bukacek's correlation of
        // the water content of a sweet natural gas (GPSA Engineering Data Book, ±5 %), W = 47484·p_sat/P + B in
        // lb/MMscf with log10 B = 6.69449 − 3083.87/(T + 459.6), T in °F: 47484 lb/MMscf is a mole fraction of one, so
        // y_w = p_sat/P + B/47484. The dew point is the temperature where it equals the stream's water.
        foreach (var target in new[] { 30e5, 70e5 })
        {
            var (t, p) = env.WaterDewTemperature.Zip(env.WaterDewPressure).MinBy(x => Math.Abs(Math.Log(x.Second / target)));
            double Content(double tk) => IapwsSaturationPressure(tk) / p + Math.Pow(10, 6.69449 - 3083.87 / ((tk - 273.15) * 1.8 + 32 + 459.6)) / 47484;
            double lo = 250, hi = 500;
            for (var k = 0; k < 60; k++) { var m = 0.5 * (lo + hi); if (Content(m) < yw) lo = m; else hi = m; }
            output.WriteLine($"{p / 1e5:0.0} bar: engine {t - 273.15:0.00} °C, Bukacek {lo - 273.15:0.00} °C, ideal {IapwsSaturationTemperature(yw * p) - 273.15:0.00} °C");
            Assert.InRange(t, lo - 2.0, lo + 2.0);
        }
    }

    /// <summary>IAPWS-IF97 saturation pressure of water (Pa) at <paramref name="t"/> (K), its equation (30).</summary>
    private static double IapwsSaturationPressure(double t)
    {
        double[] n = [0, 0.11670521452767e4, -0.72421316703206e6, -0.17073846940092e2, 0.12020824702470e5, -0.32325550322333e7,
                      0.14915108613530e2, -0.48232657361591e4, 0.40511340542057e6, -0.23855557567849, 0.65017534844798e3];
        var th = t + n[9] / (t - n[10]);
        var a = th * th + n[1] * th + n[2];
        var b = n[3] * th * th + n[4] * th + n[5];
        var c = n[6] * th * th + n[7] * th + n[8];
        return Math.Pow(2 * c / (-b + Math.Sqrt(b * b - 4 * a * c)), 4) * 1e6;
    }

    [Fact]
    public async Task AnAqueousStreamKeepsItsOwnEnvelope()
    {
        // Mostly water (water with a little methane): not a wet gas, so no water-free envelope is traced.
        var x = new Dictionary<string, double> { ["Water"] = 0.9, ["Methane"] = 0.1 };
        var env = await EngineFixture.Json<PhaseEnvelope>(await engine.Authed().PostAsJsonAsync("/phase-envelope", new PhaseEnvelopeRequest(Pr(x.Keys), x), Json.Options));
        Assert.False(env.WaterFree);
        Assert.Empty(env.WaterDewTemperature);
    }

    // ---- D119: inhibited water that freezes before hydrates form ----

    [Theory]
    [InlineData(0.02)]
    [InlineData(0.20)]
    public async Task InhibitedWaterFreezesAndBelowThatTheInhibitorDoesNotLowerHydrates(double w)
    {
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Methane"] };
        // Methane at 6 bar: hydrates form near −25 °C, from ice.
        var env = await EngineFixture.Json<PhaseEnvelope>(await engine.Authed().PostAsJsonAsync("/phase-envelope",
            new PhaseEnvelopeRequest(fs, new() { ["Methane"] = 1.0 }, Hydrates: true, Pressure: 6e5, Temperature: 311.15,
                Inhibitor: HydrateInhibitors.Methanol, InhibitorWeightFraction: w), Json.Options));
        var tf = env.InhibitedWaterFreezingTemperature!.Value;
        output.WriteLine($"{w:P0} methanol: freezes at {tf - 273.15:0.00} °C; hydrate {env.HydrateTemperatureAtPressure - 273.15:0.00} °C, inhibited {env.InhibitedHydrateTemperatureAtPressure - 273.15:0.00} °C | " + string.Join(" | ", env.Warnings));
        if (w < 0.05)
        {
            // Dilute limit, independent of the activity model: ΔT = K_f·m, water's cryoscopic constant 1.853 K·kg/mol.
            var m = w * 1000 / 32.042 / (1 - w);
            Assert.Equal(273.15 - 1.853 * m, tf, 0.15);
        }
        else
        {
            // CRC Handbook (Concentrative properties of aqueous solutions): 20 wt % methanol freezes near −15.0 °C.
            // DWSIM's NRTL water activity (fitted to vapour–liquid data well above 0 °C) puts it 4.0 K warmer, at
            // −11.0 °C; an ideal solution would give −13.0 °C. Warmer is the safe side: the app says the inhibitor stops
            // working sooner than it does, and the warning says the measured value is colder.
            Assert.InRange(tf - 273.15, -15.0, -10.5);
        }
        // The hydrate point is below the freezing point: the water there is ice, so the inhibitor changes nothing.
        Assert.True(env.HydrateTemperatureAtPressure < tf);
        Assert.Equal(env.HydrateTemperatureAtPressure!.Value, env.InhibitedHydrateTemperatureAtPressure!.Value, 0.01);
        Assert.Contains(env.Warnings, x => x.Contains("freezes below") && x.Contains("leaves out freezing"));
    }
}
