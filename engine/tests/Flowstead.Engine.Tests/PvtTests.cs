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
}
