// Flowstead engine tests — PVT laboratory experiments (decision D53).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class PvtTests(EngineFixture engine, ITestOutputHelper output)
{
    private static readonly Dictionary<string, double> Oil = new()
    {
        ["Methane"] = 0.35, ["Ethane"] = 0.07, ["Propane"] = 0.06, ["N-butane"] = 0.04, ["N-pentane"] = 0.03, ["N-heptane"] = 0.15, ["N-decane"] = 0.30,
    };

    private static readonly Dictionary<string, double> Condensate = new()
    {
        ["Methane"] = 0.80, ["Ethane"] = 0.08, ["Propane"] = 0.04, ["N-butane"] = 0.02, ["N-heptane"] = 0.03, ["N-decane"] = 0.03,
    };

    private const double Reservoir = 360;

    private async Task<PvtResult> Run(Dictionary<string, double> x, string experiment, List<SeparatorStage>? stages = null)
    {
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = x.Keys.ToList() };
        var response = await engine.Authed().PostAsJsonAsync("/pvt", new PvtRequest(fs, x, experiment, Reservoir, Stages: stages), Json.Options);
        Assert.True(response.IsSuccessStatusCode, await response.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<PvtResult>(response);
        output.WriteLine($"{experiment}: Psat {r.SaturationPressure / 1e5:0.00} bar ({r.SaturationKind}); " + string.Join(", ", r.Summary.Select(s => $"{s.Key} {s.Value:0.####}")));
        foreach (var w in r.Warnings) output.WriteLine("  warning: " + w);
        for (var i = 0; i < r.Table.Values.FirstOrDefault()?.Count; i++)
            output.WriteLine("  " + string.Join("  ", r.Columns.Select(c => $"{c} {r.Table[c][i]:0.####}")));
        return r;
    }

    /// <summary>How many phases DWSIM's stream flash finds at the reservoir temperature and <paramref name="p"/>.</summary>
    private async Task<int> PhasesAt(Dictionary<string, double> x, double p)
    {
        var s = new SimObject
        {
            Id = "s", Tag = "S", Type = ObjectType.MaterialStream, Composition = x,
            Specs = new() { [SpecKeys.Temperature] = new(Reservoir, "K"), [SpecKeys.Pressure] = new(p, "Pa"), [SpecKeys.MolarFlow] = new(1, "mol/s") },
        };
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = x.Keys.ToList(), Objects = [s], LiquidDensity = LiquidDensityMethods.Eos };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        return r.Objects["s"].Phases!.Keys.Count(k => k != "overall");
    }

    /// <summary>The saturation pressure is where DWSIM's own flash goes from one phase to two.</summary>
    private async Task IsTheBoundary(Dictionary<string, double> x, double psat)
    {
        Assert.Equal(1, await PhasesAt(x, psat * 1.002));
        Assert.Equal(2, await PhasesAt(x, psat * 0.998));
    }

    [Fact]
    public async Task OilHasABubblePointAndExpandsBelowIt()
    {
        var r = await Run(Oil, PvtExperiments.Cce);
        Assert.Equal("bubble", r.SaturationKind);
        await IsTheBoundary(Oil, r.SaturationPressure!.Value);
        var p = r.Table[PvtKeys.Pressure];
        var vrel = r.Table[PvtKeys.RelativeVolume];
        for (var i = 0; i < p.Count; i++)
        {
            // Compressed liquid above the bubble point, gas coming out below it.
            if (p[i] > r.SaturationPressure) Assert.InRange(vrel[i]!.Value, 0.9, 1.0);
            else Assert.True(vrel[i] > 1);
        }
        Assert.True(r.Table[PvtKeys.YFunction].Where(y => y is not null).All(y => y > 0));
    }

    [Fact]
    public async Task CondensateDropsOutLiquidOnDepletion()
    {
        var r = await Run(Condensate, PvtExperiments.Cvd);
        Assert.Equal("dew", r.SaturationKind);
        // The upper (retrograde) dew point, not the one near atmospheric pressure.
        Assert.True(r.SaturationPressure > 1e7);
        await IsTheBoundary(Condensate, r.SaturationPressure!.Value);
        var dropout = r.Table[PvtKeys.LiquidDropout];
        var produced = r.Table[PvtKeys.GasProduced];
        Assert.Equal(0, dropout[0]);
        Assert.True(dropout.Skip(1).All(d => d > 0 && d < 0.5));
        // Retrograde: the dropout rises to a maximum, then falls as the pressure keeps dropping.
        var peak = dropout.IndexOf(dropout.Max());
        Assert.InRange(peak, 2, dropout.Count - 2);
        Assert.True(produced.Zip(produced.Skip(1)).All(z => z.Second >= z.First) && produced[^1] < 1);
    }

    [Fact]
    public async Task DifferentialLiberationShrinksTheOil()
    {
        var r = await Run(Oil, PvtExperiments.DifferentialLiberation);
        var p = r.Table[PvtKeys.Pressure];
        var bo = r.Table[PvtKeys.OilFormationVolumeFactor];
        var rs = r.Table[PvtKeys.SolutionGor];
        var atPb = p.IndexOf(r.SaturationPressure);
        Assert.True(atPb >= 0);
        // Below the bubble point Bo and Rs fall with pressure; Bo stays above 1 (hot, gassy oil vs dead oil at 60 °F).
        for (var i = atPb + 1; i < p.Count; i++)
        {
            Assert.True(bo[i] < bo[i - 1] && bo[i] > 1);
            Assert.True(rs[i] <= rs[i - 1]);
        }
        Assert.InRange(r.Summary[PvtKeys.ResidualOilDensity], 650, 850);
    }

    [Fact]
    public async Task SeparatorTestGivesBoGorAndApi()
    {
        var r = await Run(Oil, PvtExperiments.Separator, [new SeparatorStage(310.93, 7e5)]);
        Assert.Equal(2, r.Table[PvtKeys.Pressure].Count); // separator, then stock tank
        Assert.InRange(r.Summary[PvtKeys.OilFormationVolumeFactor], 1.1, 3);
        Assert.True(r.Summary[PvtKeys.TotalGor] > 10);
        // API from the stock-tank density: 141.5/SG − 131.5.
        Assert.Equal(141.5 / (r.Summary[PvtKeys.StockTankOilDensity] / 999.012) - 131.5, r.Summary[PvtKeys.Api], 1e-9);
    }

    [Fact]
    public async Task RegressionPredictsTheFluidThatMadeTheLabData()
    {
        // "Lab data" from the same fluid with its plus fraction's Tc 4 % higher, Pc 7 % lower and its kij with methane
        // 0.03 above DWSIM's; the regression, starting from the Riazi–Daubert estimates and DWSIM's kij, should find a
        // fluid that reproduces the data.
        var plus = new Hypothetical { Name = "C12+", NormalBoilingPoint = 490, MolecularWeight = 170, SpecificGravity = 0.78 };
        var x = new Dictionary<string, double> { ["Methane"] = 0.40, ["Ethane"] = 0.08, ["Propane"] = 0.06, ["N-butane"] = 0.04, ["N-heptane"] = 0.12, ["C12+"] = 0.30 };
        Flowsheet Fluid(Hypothetical h) => new() { PropertyPackage = "Peng-Robinson (PR)", Compounds = x.Keys.ToList(), Hypotheticals = [h] };
        var estimated = (await EngineFixture.Json<List<HypotheticalProperties>>(await engine.Authed().PostAsJsonAsync("/hypotheticals/estimate", new List<Hypothetical> { plus }, Json.Options))).Single();
        var kij0 = (await EngineFixture.Json<List<BinaryParameterInfo>>(await engine.Authed().PostAsJsonAsync("/interaction-parameters", Fluid(plus), Json.Options)))
            .Single(p => p.A == "Methane" && p.B == "C12+").Kij;
        var truth = Fluid(plus with { CriticalTemperature = estimated.CriticalTemperature * 1.04, CriticalPressure = estimated.CriticalPressure * 0.93, AcentricFactor = estimated.AcentricFactor })
            with { InteractionParameters = [new BinaryParameter("Methane", "C12+", kij0 + 0.03)] };

        async Task<PvtResult> Lab(string exp, List<double>? p = null) =>
            await EngineFixture.Json<PvtResult>(await engine.Authed().PostAsJsonAsync("/pvt", new PvtRequest(truth, x, exp, Reservoir, p), Json.Options));
        var sat = (await Lab(PvtExperiments.Saturation)).SaturationPressure!.Value;
        var ps = new List<double> { Math.Round(sat * 1.3), Math.Round(sat * 0.6), Math.Round(sat * 0.35) };
        var cce = await Lab(PvtExperiments.Cce, ps);
        double At(string key, double p) => cce.Table[key][cce.Table[PvtKeys.Pressure].FindIndex(v => Math.Abs(v!.Value - p) < 1)]!.Value;
        var observations = new List<PvtObservation>
        {
            new(PvtExperiments.Saturation, PvtKeys.SaturationPressure, sat),
            new(PvtExperiments.Cce, PvtKeys.Density, At(PvtKeys.Density, ps[0]), ps[0]),
            new(PvtExperiments.Cce, PvtKeys.RelativeVolume, At(PvtKeys.RelativeVolume, ps[1]), ps[1]),
            new(PvtExperiments.Cce, PvtKeys.RelativeVolume, At(PvtKeys.RelativeVolume, ps[2]), ps[2]),
        };
        var response = await engine.Authed(minutes: 8).PostAsJsonAsync("/pvt-regression", new PvtRegressionRequest(Fluid(plus), x, Reservoir, observations), Json.Options);
        Assert.True(response.IsSuccessStatusCode, await response.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<PvtRegressionResult>(response);
        output.WriteLine($"error {r.ErrorBefore:P2} -> {r.ErrorAfter:P3} in {r.Iterations} iterations; " + string.Join(", ", r.Multipliers.Select(m => $"{m.Key} {m.Value:0.0000}"))
            + $"; kij {kij0:0.0000} -> {r.InteractionParameters.SingleOrDefault()?.Kij:0.0000} (truth {kij0 + 0.03:0.0000})");
        foreach (var m in r.Matches) output.WriteLine($"  {m.Observation.Key} at {m.Observation.Pressure}: lab {m.Observation.Value:0.####} before {m.Before:0.####} after {m.After:0.####}");
        foreach (var w in r.Warnings) output.WriteLine("  warning: " + w);
        Assert.True(r.ErrorBefore > 0.003);
        Assert.True(r.ErrorAfter < r.ErrorBefore / 20);
        Assert.Equal(kij0 + 0.03, Assert.Single(r.InteractionParameters).Kij, 0.015);
        // Four observations do not pin down Tc, Pc, ω and kij one by one: DWSIM 9 landed on the constants that made the data,
        // DWSIM 10 on Tc 4 % lower with the same fit (D93). What a tuning is for is predicting what was not measured, so the
        // tuned fluid must match the true one away from the data: saturation 40 K colder, the oil compressed to twice its
        // saturation pressure and expanded to a fifth of it, within 1 % (a saturation pressure's usual lab uncertainty) and
        // at least three times closer than the untuned fluid. DWSIM 10 stops in the Tc–kij valley the four points leave
        // open (fit 0.065 %): saturation 40 K colder 0.67 % off, untuned 5.7 %.
        var tuned = Fluid(Assert.Single(r.Tuned)) with { InteractionParameters = r.InteractionParameters };
        async Task<PvtResult> Run(Flowsheet f, string exp, double t, List<double>? p = null) =>
            await EngineFixture.Json<PvtResult>(await engine.Authed().PostAsJsonAsync("/pvt", new PvtRequest(f, x, exp, t, p), Json.Options));
        async Task<(double Truth, double Tuned, double Untuned)> Predict(Func<Flowsheet, Task<double>> f) => (await f(truth), await f(tuned), await f(Fluid(plus)));
        var away = new List<double> { Math.Round(sat * 2), Math.Round(sat * 0.2) };
        double Cell(PvtResult c, string key, double p) => c.Table[key][c.Table[PvtKeys.Pressure].FindIndex(v => Math.Abs(v!.Value - p) < 1)]!.Value;
        var checks = new (string Label, Func<Flowsheet, Task<double>> Value)[]
        {
            ("saturation pressure 40 K colder", async f => (await Run(f, PvtExperiments.Saturation, Reservoir - 40)).SaturationPressure!.Value),
            ("density at 2 × psat", async f => Cell(await Run(f, PvtExperiments.Cce, Reservoir, away), PvtKeys.Density, away[0])),
            ("relative volume at psat / 5", async f => Cell(await Run(f, PvtExperiments.Cce, Reservoir, away), PvtKeys.RelativeVolume, away[1])),
        };
        foreach (var (label, value) in checks)
        {
            var (t, tu, un) = await Predict(value);
            output.WriteLine($"  {label}: truth {t:G6}, tuned {tu:G6} ({(tu - t) / t:P3}), untuned {un:G6} ({(un - t) / t:P3})");
            Assert.True(Math.Abs(tu - t) <= 0.01 * Math.Abs(t), $"{label}: tuned {tu} vs truth {t}");
            // Where the untuned fluid was already within 1 % (the compressed oil's density), there is nothing to improve on.
            if (Math.Abs(un - t) > 0.01 * Math.Abs(t))
                Assert.True(Math.Abs(tu - t) < Math.Abs(un - t) / 3, $"{label}: tuning did not help ({tu} vs untuned {un}, truth {t})");
        }
    }
}
