// Flowstead engine tests — thermodynamics tools (D100), against independent data.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class ThermoToolsTests(EngineFixture engine, ITestOutputHelper output)
{
    private static readonly Flowsheet HexHep = new() { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["N-hexane", "N-heptane"] };

    private async Task<T> Post<T>(string path, object body)
    {
        var r = await engine.Authed().PostAsJsonAsync(path, body, Json.Options);
        if (!r.IsSuccessStatusCode) Assert.Fail($"{path}: {(int)r.StatusCode} {await r.Content.ReadAsStringAsync()}");
        return (await EngineFixture.Json<T>(r))!;
    }

    // NIST WebBook Antoine constants, log10(P/bar) = A − B/(T + C).
    private static double PsatHexane(double t) => Math.Pow(10, 4.00266 - 1171.53 / (t - 48.784)) * 1e5;
    private static double PsatHeptane(double t) => Math.Pow(10, 4.02832 - 1268.636 / (t - 56.199)) * 1e5;

    private static double Near(List<XY> curve, double x) => curve.MinBy(p => Math.Abs(p.X - x))!.Y;

    [Fact]
    public async Task ATxyDiagramOfAnIdealPairFollowsRaoult()
    {
        var d = await Post<BinaryDiagramResult>("/binary-diagram", new BinaryDiagramRequest { Flowsheet = HexHep, First = "N-hexane", Second = "N-heptane", Kind = BinaryDiagramKinds.Txy, Pressure = 101325, Steps = 20 });
        foreach (var w in d.Warnings) output.WriteLine(w);
        Assert.True(d.Bubble.Count >= 19 && d.Dew.Count >= 19, $"{d.Bubble.Count} bubble, {d.Dew.Count} dew points");
        // The ends are the normal boiling points (NIST: 341.88 K and 371.58 K).
        Assert.Equal(371.58, Near(d.Bubble, 0), 1.0);
        Assert.Equal(341.88, Near(d.Bubble, 1), 1.0);
        // Halfway: Raoult's law with Antoine vapour pressures, 0.5 P°hex(T) + 0.5 P°hep(T) = 1 atm.
        double lo = 340, hi = 372;
        for (var k = 0; k < 60; k++) { var m = (lo + hi) / 2; if (0.5 * PsatHexane(m) + 0.5 * PsatHeptane(m) < 101325) lo = m; else hi = m; }
        output.WriteLine($"x = 0.5: PR {Near(d.Bubble, 0.5):0.00} K, Raoult {lo:0.00} K");
        Assert.Equal(lo, Near(d.Bubble, 0.5), 1.5);
        // The dew curve lies above the bubble curve between the ends.
        Assert.True(Near(d.Dew, 0.5) > Near(d.Bubble, 0.5) + 2);
    }

    [Fact]
    public async Task APxyDiagramEndsAtTheVapourPressures()
    {
        var d = await Post<BinaryDiagramResult>("/binary-diagram", new BinaryDiagramRequest { Flowsheet = HexHep, First = "N-hexane", Second = "N-heptane", Kind = BinaryDiagramKinds.Pxy, Temperature = 340, Steps = 20 });
        Assert.Equal(PsatHeptane(340), Near(d.Bubble, 0), PsatHeptane(340) * 0.03);
        Assert.Equal(PsatHexane(340), Near(d.Bubble, 1), PsatHexane(340) * 0.03);
        // Raoult: the bubble pressure is linear in x for an ideal pair.
        var mid = 0.5 * (PsatHexane(340) + PsatHeptane(340));
        Assert.Equal(mid, Near(d.Bubble, 0.5), mid * 0.04);
    }

    [Fact]
    public async Task WaterAndButanolSplitIntoTwoLiquids()
    {
        // Sørensen & Arlt (DECHEMA LLE data): at 25 °C the water-rich liquid holds x(1-butanol) ≈ 0.019 and the
        // butanol-rich one x(1-butanol) ≈ 0.49.
        var fs = new Flowsheet { PropertyPackage = "NRTL", Compounds = ["1-butanol", "Water"] };
        var d = await Post<BinaryDiagramResult>("/binary-diagram", new BinaryDiagramRequest { Flowsheet = fs, First = "1-butanol", Second = "Water", Kind = BinaryDiagramKinds.Txy, Pressure = 101325, Steps = 40 });
        foreach (var w in d.Warnings) output.WriteLine(w);
        output.WriteLine($"LLE: {d.LiquidLiquid1.Count} + {d.LiquidLiquid2.Count} points");
        Assert.NotEmpty(d.LiquidLiquid1);
        var near = d.LiquidLiquid1.Zip(d.LiquidLiquid2).MinBy(p => Math.Abs(p.First.Y - 298.15));
        var (water, organic) = near.First.X < near.Second.X ? (near.First.X, near.Second.X) : (near.Second.X, near.First.X);
        output.WriteLine($"at {near.First.Y:0.0} K: water-rich x = {water:0.000}, butanol-rich x = {organic:0.000}");
        Assert.InRange(water, 0.005, 0.04);
        Assert.InRange(organic, 0.38, 0.62);
    }

    [Fact]
    public async Task TheCriticalPointOfAMixtureAgreesWithItsEnvelope()
    {
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Methane", "Ethane", "Propane"] };
        var x = new Dictionary<string, double> { ["Methane"] = 0.7, ["Ethane"] = 0.2, ["Propane"] = 0.1 };
        var c = await Post<CriticalPointResult>("/critical-points", new CriticalPointRequest { Flowsheet = fs, Composition = x });
        var cp = Assert.Single(c.Points);
        var env = await Post<PhaseEnvelope>("/phase-envelope", new PhaseEnvelopeRequest(fs, x));
        output.WriteLine($"Heidemann–Khalil {cp.Temperature:0.00} K {cp.Pressure / 1e5:0.00} bar; envelope {env.CriticalTemperature:0.00} K {env.CriticalPressure / 1e5:0.00} bar");
        // The envelope reports the same point (it is the same DWSIM calculation, so this checks the wiring, not the method).
        Assert.Equal(env.CriticalTemperature!.Value, cp.Temperature, 2.0);
        Assert.Equal(env.CriticalPressure!.Value, cp.Pressure, env.CriticalPressure.Value * 0.02);
        // Between the pure components' critical temperatures (methane 190.6 K, propane 369.8 K).
        Assert.InRange(cp.Temperature, 190.6, 369.8);
    }

    [Fact]
    public async Task ThePureLimitIsTheCompoundsCriticalPoint()
    {
        // Independent of the envelope: as one compound's fraction goes to 1, the mixture's critical point is its own
        // (methane: 190.56 K, 45.99 bar, NIST), and Peng–Robinson's critical compressibility is 0.3074 by construction.
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Methane", "Ethane"] };
        var c = await Post<CriticalPointResult>("/critical-points", new CriticalPointRequest { Flowsheet = fs, Composition = new() { ["Methane"] = 0.9999, ["Ethane"] = 0.0001 } });
        var cp = Assert.Single(c.Points);
        output.WriteLine($"{cp.Temperature:0.000} K, {cp.Pressure / 1e5:0.000} bar, Zc {cp.Pressure * cp.MolarVolume / (8.314462618 * cp.Temperature):0.0000}");
        Assert.Equal(190.56, cp.Temperature, 0.3);
        Assert.Equal(45.99e5, cp.Pressure, 45.99e5 * 0.005);
        Assert.Equal(0.3074, cp.Pressure * cp.MolarVolume / (8.314462618 * cp.Temperature), 0.005);
    }

    [Fact]
    public async Task WaterIsComparedAcrossPackages()
    {
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Water"] };
        var r = await Post<PackageComparisonResult>("/compare-packages", new PackageComparisonRequest
        {
            Flowsheet = fs, Composition = new() { ["Water"] = 1 }, Temperature = 298.15, Pressure = 1e5,
            Packages = ["Steam Tables (IAPWS-IF97)", "Peng-Robinson (PR)", "NRTL", "No such package"],
        });
        Assert.Equal(4, r.Rows.Count);
        double Rho(string p) => r.Rows.Single(x => x.Package == p).Result!.Phases!.First(ph => ph.Key != "overall").Value.Properties["density"];
        // IAPWS-95 (NIST): 997.05 kg/m³ at 25 °C, 1 bar.
        Assert.Equal(997.05, Rho("Steam Tables (IAPWS-IF97)"), 0.5);
        Assert.NotNull(r.Rows.Single(x => x.Package == "NRTL").Result);
        Assert.Contains("not a fluid package", r.Rows.Single(x => x.Package == "No such package").Error);
    }

    [Fact]
    public async Task ASweepIsTheSameAsSingleSolves()
    {
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Methane", "Propane"] };
        var x = new Dictionary<string, double> { ["Methane"] = 0.9, ["Propane"] = 0.1 };
        var s = await Post<PropertySweepResult>("/property-sweep", new PropertySweepRequest { Flowsheet = fs, Composition = x, From = 1e5, To = 100e5, Points = 5, Fixed = 300, Variable = SweepVariables.Pressure });
        Assert.Equal(5, s.Values.Count);
        Assert.All(s.Results, Assert.NotNull);
        // The gas gets denser with pressure, and the 50 bar point is what a stream at 50 bar gives.
        var rho = s.Results.Select(o => o!.Phases!["overall"].Properties["density"]).ToList();
        Assert.True(rho.Zip(rho.Skip(1)).All(p => p.Second > p.First));
        var one = await Post<SolveResult>("/solve", fs with
        {
            Objects = [new SimObject { Id = "s", Tag = "S", Type = ObjectType.MaterialStream, Composition = x,
                Specs = new() { [SpecKeys.Temperature] = new(300, "K"), [SpecKeys.Pressure] = new(s.Values[2], "Pa"), [SpecKeys.MolarFlow] = new(1, "mol/s") } }],
        });
        Assert.Equal(one.Objects["s"].Phases!["overall"].Properties["density"], rho[2], rho[2] * 1e-6);
    }

    [Fact]
    public async Task ATernaryWithTwoLiquidsHasConsistentTieLines()
    {
        // Water, n-hexane and ethanol with NRTL at 25 °C, 1 bar: water and hexane are almost immiscible, ethanol
        // dissolves in both. Every two-liquid point's feed lies on the line between its liquids (the lever rule).
        var fs = new Flowsheet { PropertyPackage = "NRTL", Compounds = ["Water", "N-hexane", "Ethanol"] };
        var t = await Post<TernaryResult>("/ternary", new TernaryRequest { Flowsheet = fs, Compounds = ["Water", "N-hexane", "Ethanol"], Temperature = 298.15, Pressure = 1e5, Step = 0.125 });
        foreach (var w in t.Warnings) output.WriteLine(w);
        var two = t.Points.Where(p => p.Liquid1 is not null && p.Liquid2 is not null && p.VapourFraction < 1e-9).ToList();
        output.WriteLine($"{t.Points.Count} points, {two.Count} with two liquids");
        Assert.NotEmpty(two);
        foreach (var p in two)
        {
            var b = p.Liquid2Fraction!.Value;
            for (var k = 0; k < 3; k++)
                Assert.Equal(p.Feed[k], (1 - b) * p.Liquid1![k] + b * p.Liquid2![k], 0.01);
        }
        // On the water–hexane edge the liquids are nearly pure.
        var edge = two.Where(p => p.Feed[2] < 1e-9).ToList();
        Assert.NotEmpty(edge);
        Assert.All(edge, p => Assert.True(Math.Max(p.Liquid1![0], p.Liquid2![0]) > 0.99 && Math.Min(p.Liquid1[0], p.Liquid2[0]) < 0.01));
    }
}
