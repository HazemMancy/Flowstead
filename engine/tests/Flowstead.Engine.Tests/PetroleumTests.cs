// Flowstead engine tests — petroleum assays and a stream's distillation curves (D99).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class PetroleumTests(EngineFixture engine, ITestOutputHelper output)
{
    private static double FToK(double f) => (f - 32) * 5 / 9 + 273.15;
    private static double KToF(double k) => (k - 273.15) * 9 / 5 + 32;

    private static readonly Flowsheet Pr = new() { PropertyPackage = "Peng-Robinson (PR)" };

    // A light crude's TBP (liquid volume %, K).
    private static readonly List<AssayPoint> Crude =
    [
        new(0, 300), new(10, 370), new(30, 450), new(50, 520), new(70, 600), new(90, 700), new(100, 760),
    ];

    private async Task<T> Post<T>(string path, object body)
    {
        var r = await engine.Authed().PostAsJsonAsync(path, body, Json.Options);
        if (!r.IsSuccessStatusCode) Assert.Fail($"{path}: {(int)r.StatusCode} {await r.Content.ReadAsStringAsync()}");
        return (await EngineFixture.Json<T>(r))!;
    }

    private Task<AssayResult> Assay(AssayRequest req) => Post<AssayResult>("/assay", req);

    /// <summary>The stream the assay describes, with its pseudo-components as hypotheticals.</summary>
    private static Flowsheet FluidOf(AssayResult a) => Pr with { Compounds = a.Composition.Keys.ToList(), Hypotheticals = a.Hypotheticals };

    private Task<PetroleumPropertiesResult> Curves(AssayResult a, string tbpBasis = AssayBases.Volume) =>
        Post<PetroleumPropertiesResult>("/petroleum-properties", new PetroleumPropertiesRequest { Flowsheet = FluidOf(a), Composition = a.Composition, TbpBasis = tbpBasis });

    private static double At(DistillationCurveResult c, double percent)
    {
        var p = c.Points;
        for (var i = 1; i < p.Count; i++)
            if (percent <= p[i].Percent) return p[i - 1].Value + (p[i].Value - p[i - 1].Value) * (percent - p[i - 1].Percent) / (p[i].Percent - p[i - 1].Percent);
        return p[^1].Value;
    }

    [Fact]
    public async Task CutsOfATbpCurveGiveTheCurveBack()
    {
        var a = await Assay(new AssayRequest { Flowsheet = Pr, Name = "LC", Points = Crude, Cuts = 20, BulkSpecificGravity = 0.85 });
        foreach (var w in a.Warnings) output.WriteLine(w);
        Assert.Equal(20, a.Hypotheticals.Count);
        Assert.Equal(1.0, a.Composition.Values.Sum(), 1e-9);
        Assert.StartsWith("NBP[LC]", a.Hypotheticals[0].Name);
        // The bulk SG is met (constant Watson K across the cuts).
        Assert.Equal(0.85, a.SpecificGravity, 1e-6);

        // DWSIM's own TBP of the pseudo-component stream returns the curve (DWSIM's test allows 1.5 K with its cuts).
        var r = await Curves(a);
        var tbp = r.Curves.Single(c => c.Kind == DistillationCurveKinds.Tbp);
        foreach (var p in new[] { 10.0, 30, 50, 70, 90 })
        {
            var want = Crude.First(q => q.Percent == p).Value;
            output.WriteLine($"TBP {p} %: {At(tbp, p):0.0} K (given {want})");
            Assert.InRange(At(tbp, p), want - 4, want + 4);
        }
        // The stream's SG and API gravity agree with the cuts: API = 141.5 / SG − 131.5.
        Assert.Equal(0.85, r.Properties[PetroleumKeys.SpecificGravity], 0.85 * 0.005);
        Assert.Equal(141.5 / r.Properties[PetroleumKeys.SpecificGravity] - 131.5, r.Properties[PetroleumKeys.Api], 1e-6);
    }

    [Fact]
    public async Task AD86CurveIsConvertedAsTheDataBookDoes()
    {
        // API Technical Data Book 3A1.1: D86 10/30/50/70/90 % = 350 380 404 433 469 °F gives TBP 316.6 372.6 411.2 451.2 496.7 °F.
        var d86 = new[] { (0.0, 330.0), (10, 350), (30, 380), (50, 404), (70, 433), (90, 469), (100, 490) }.Select(p => new AssayPoint(p.Item1, FToK(p.Item2))).ToList();
        var a = await Assay(new AssayRequest { Flowsheet = Pr, Name = "KERO", CurveType = DistillationCurveKinds.D86, Points = d86, Cuts = 12, BulkSpecificGravity = 0.80 });
        var expected = new[] { (10.0, 316.6), (30, 372.6), (50, 411.2), (70, 451.2), (90, 496.7) };
        foreach (var (p, f) in expected) Assert.Equal(f, KToF(a.Tbp.Single(q => q.Percent == p).Value), 0.3);

        // The stream made of the cuts has DWSIM's D86 curve close to the one given (12 cuts of a 90 °F-wide middle).
        var r = await Curves(a);
        var back = r.Curves.Single(c => c.Kind == DistillationCurveKinds.D86);
        foreach (var (p, f) in new[] { (10.0, 350.0), (50, 404), (90, 469) })
        {
            output.WriteLine($"D86 {p} %: {KToF(At(back, p)):0.0} °F (given {f})");
            Assert.InRange(KToF(At(back, p)), f - 8, f + 8);
        }
    }

    [Fact]
    public async Task LightEndsAreAddedOnTheirBasis()
    {
        // 2 vol % propane and 3 vol % n-butane reported apart from a heavy naphtha's curve.
        var ends = new Dictionary<string, double> { ["Propane"] = 0.02, ["N-butane"] = 0.03 };
        var fs = Pr with { Compounds = ["Propane", "N-butane"] };
        var a = await Assay(new AssayRequest { Flowsheet = fs, Name = "N", Points = Crude, Cuts = 8, BulkSpecificGravity = 0.85, LightEnds = ends });
        Assert.Equal(1.0, a.Composition.Values.Sum(), 1e-9);
        Assert.Contains("Propane", a.Composition.Keys);
        // Back on liquid volume: Σ x MW / ρ60 for each part gives the volume shares given.
        var basis = await Post<List<CompoundBasis>>("/compounds/basis", FluidOf(a));
        var vol = a.Composition.ToDictionary(kv => kv.Key, kv => kv.Value * basis.Single(b => b.Name == kv.Key).MolecularWeight / basis.Single(b => b.Name == kv.Key).StdLiquidDensity!.Value);
        var total = vol.Values.Sum();
        Assert.Equal(0.02, vol["Propane"] / total, 1e-3);
        Assert.Equal(0.03, vol["N-butane"] / total, 1e-3);

        // With the curve run on the whole sample, the cuts start above the light ends' 5 %.
        var inCurve = await Assay(new AssayRequest { Flowsheet = fs, Name = "N", Points = Crude, Cuts = 8, BulkSpecificGravity = 0.85, LightEnds = ends, LightEndsInCurve = true });
        Assert.True(inCurve.Cuts[0].StartTemperature!.Value > Crude[0].Value + 10, $"first cut starts at {inCurve.Cuts[0].StartTemperature:0.0} K");
    }

    [Fact]
    public async Task APlusFractionFromBulkPropertiesKeepsThem()
    {
        // C7+ of MW 200 and SG 0.83, by DWSIM's Riazi distribution.
        var a = await Assay(new AssayRequest { Flowsheet = Pr, Name = "C7+", Cuts = 8, BulkMolecularWeight = 200, BulkSpecificGravity = 0.83 });
        Assert.Equal(8, a.Hypotheticals.Count);
        Assert.Equal(200, a.MolecularWeight, 200 * 0.01);
        Assert.Equal(0.83, a.SpecificGravity, 0.83 * 0.01);
        Assert.True(a.Hypotheticals.Zip(a.Hypotheticals.Skip(1)).All(p => p.Second.NormalBoilingPoint > p.First.NormalBoilingPoint));
        // The pseudo-components solve as hypotheticals: a flash at 60 °C, 1 bar is liquid.
        var r = await Post<SolveResult>("/solve", FluidOf(a) with
        {
            Objects = [new SimObject { Id = "s", Tag = "S", Type = ObjectType.MaterialStream, Composition = a.Composition,
                Specs = new() { [SpecKeys.Temperature] = new(333.15, "K"), [SpecKeys.Pressure] = new(1e5, "Pa"), [SpecKeys.MolarFlow] = new(1, "mol/s") } }],
        });
        Assert.Equal(SolveStatus.Converged, r.Status);
        Assert.Equal(0.0, r.Objects["s"].Properties["vapourFraction"], 0.05);
    }

    [Fact]
    public async Task CutTemperaturesAndGravityCurvesAreHonoured()
    {
        var gravity = new List<AssayPoint> { new(5, 0.70), new(50, 0.84), new(95, 0.95) };
        var a = await Assay(new AssayRequest { Flowsheet = Pr, Name = "G", Points = Crude, CutTemperatures = [400, 500, 600], Gravity = gravity });
        Assert.Equal(4, a.Cuts.Count);
        Assert.Equal(500, a.Cuts[1].EndTemperature!.Value, 1e-9);
        // SG rises with the boiling point, as the curve does.
        Assert.True(a.Cuts.Zip(a.Cuts.Skip(1)).All(p => p.Second.SpecificGravity > p.First.SpecificGravity));
    }

    [Fact]
    public async Task BadAssaysAreExplained()
    {
        async Task<string> Error(AssayRequest req)
        {
            var r = await engine.Authed().PostAsJsonAsync("/assay", req, Json.Options);
            Assert.False(r.IsSuccessStatusCode);
            return (await EngineFixture.Json<EngineError>(r))!.Message;
        }
        Assert.Contains("must not fall", await Error(new AssayRequest { Flowsheet = Pr, Name = "X", Points = [new(0, 400), new(50, 380), new(100, 500)] }));
        Assert.Contains("at least three points", await Error(new AssayRequest { Flowsheet = Pr, Name = "X", Points = [new(0, 400), new(100, 500)] }));
        Assert.Contains("Cut temperatures must rise", await Error(new AssayRequest { Flowsheet = Pr, Name = "X", Points = Crude, CutTemperatures = [800] }));
        Assert.Contains("not a database compound", await Error(new AssayRequest { Flowsheet = Pr, Name = "X", Points = Crude, LightEnds = new() { ["Unobtainium"] = 0.1 } }));
        Assert.Contains("up to 12 characters", await Error(new AssayRequest { Flowsheet = Pr, Name = "A[1]", Points = Crude }));
    }

    [Fact]
    public async Task ADieselHasItsProductProperties()
    {
        // A diesel-range cut (TBP 180–360 °C, SG 0.84): the correlations DWSIM applies give numbers in the diesel range.
        var pts = new List<AssayPoint> { new(0, 453.15), new(30, 523.15), new(50, 553.15), new(70, 583.15), new(100, 633.15) };
        var a = await Assay(new AssayRequest { Flowsheet = Pr, Name = "D", Points = pts, Cuts = 12, BulkSpecificGravity = 0.84 });
        var r = await Curves(a);
        foreach (var w in r.Warnings) output.WriteLine(w);
        var p = r.Properties;
        foreach (var (k, v) in p) output.WriteLine($"{k} = {v}");
        Assert.InRange(p[PetroleumKeys.WatsonK], 11.3, 12.3);
        Assert.InRange(p[PetroleumKeys.CetaneIndex], 40, 65);
        Assert.InRange(p[PetroleumKeys.FlashPointClosedCup] - 273.15, 40, 110);
        // Pour and cloud points are separate API correlations (2B8.1 from SG, MeABP and viscosity; cloud from SG and MeABP),
        // so they can cross; each is in a diesel's range here.
        Assert.InRange(p[PetroleumKeys.PourPoint] - 273.15, -40, 10);
        Assert.InRange(p[PetroleumKeys.CloudPoint] - 273.15, -40, 10);
        Assert.Contains(r.Curves, c => c.Kind == DistillationCurveKinds.Efv);
        // The D2887 curve runs below the TBP's end and above its start (simulated distillation spreads less at the ends).
        Assert.Equal(5, r.Curves.Count);
    }
}

