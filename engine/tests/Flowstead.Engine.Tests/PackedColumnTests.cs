// Flowstead engine tests — packed columns (D152): a rigorous column's trays rated as a packed bed by DWSIM's own
// packing hydraulics, checked against an independent implementation of the published correlations.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Nodes;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

/// <summary>
/// The owner's contact asked for packed absorbers. References, written here apart from DWSIM: Robbins (1991) as given
/// in Perry 8th ed. eqs. 14-145 to 14-149, Kister and Gill's (1991) flood pressure drop 0.115 Fp^0.7 in H2O/ft, and
/// Kister's rule-of-thumb HETP (Seader and Henley eqs. 6-116 and 6-118).
/// </summary>
[Collection(EngineCollection.Name)]
public sealed class PackedColumnTests(EngineFixture engine, ITestOutputHelper output)
{
    private const double InH2OPerFt = 249.089 / 0.3048; // Pa/m

    /// <summary>Robbins' pressure drop, Pa/m. G and L in kg/m² s, densities kg/m³, μL Pa s, Fpd 1/m.</summary>
    private static double Robbins(double g, double l, double rhoG, double rhoL, double muL, double fpdPerM)
    {
        var fpd = fpdPerM * 0.3048;                            // 1/ft
        var rhoGlb = rhoG / 16.01846; var rhoLlb = rhoL / 16.01846; // lb/ft³
        var fs = g / rhoG / 0.3048 * Math.Sqrt(rhoGlb);        // ft/s (lb/ft³)^0.5
        var gf = 986 * fs * Math.Sqrt(fpd / 20) * Math.Pow(10, 0.3 * rhoGlb);
        var lb = l * 737.338;                                  // lb/h ft²
        var lf = fpd > 200
            ? lb * (62.4 / rhoLlb) * Math.Sqrt(fpd / 20) * Math.Pow(muL * 1000, 0.2)
            : lb * (62.4 / rhoLlb) * Math.Sqrt(20 / fpd) * Math.Pow(muL * 1000, 0.1);
        var dry = 7.4e-8 * gf * gf * Math.Pow(10, 2.7e-5 * lf);
        return (dry + 0.4 * Math.Pow(lf / 20000, 0.1) * Math.Pow(dry, 4)) * InH2OPerFt;
    }

    private static double KisterGill(double fpPerM) => 0.115 * Math.Pow(fpPerM * 0.3048, 0.7) * InH2OPerFt;

    /// <summary>Gas velocity at which Robbins' pressure drop reaches Kister and Gill's flood value, m/s.</summary>
    private static double FloodVelocity(double l, double rhoG, double rhoL, double muL, double fpd, double fp)
    {
        double lo = 1e-4, hi = 50;
        while (hi - lo > 1e-9) { var mid = (lo + hi) / 2; if (Robbins(mid * rhoG, l, rhoG, rhoL, muL, fpd) < KisterGill(fp)) lo = mid; else hi = mid; }
        return (lo + hi) / 2;
    }

    [Fact]
    public void TheReferenceCorrelationsReproduceTheirPublishedExamples()
    {
        // Perry's example as worked in the fluids library (Bell, fluids.packed_tower.Robbins): L = 12.2, G = 2.03 kg/m² s,
        // water and air, Fpd = 24 1/ft, 2 m of bed: 619.662 Pa.
        Assert.Equal(619.662, Robbins(2.03, 12.2, 1.1853, 1000, 0.001, 24 / 0.3048) * 2.0, 0.62);
        // Kister and Gill for Pall rings, metal, 25 mm (Perry Table 14-7b: Fp = 183 1/m = 55.8 1/ft):
        // 0.115 × 55.8^0.7 = 1.920 in H2O/ft = 1569 Pa/m.
        Assert.Equal(1.920, KisterGill(183) / InH2OPerFt, 0.002);
    }

    private static Dictionary<string, string> Refs(SimObject o, string packing, Dictionary<string, string>? more)
    {
        var refs = new Dictionary<string, string>(o.Refs) { [ChoiceKeys.Packing] = packing };
        foreach (var (k, v) in more ?? []) refs[k] = v;
        return refs;
    }

    private async Task<(SolveResult Result, ObjectResult Column)> SolvePacked(string packing, Dictionary<string, string>? more = null)
    {
        var input = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(EngineFixture.Meta("BenchmarksDir"), "11-depropanizer", "input.json")))!;
        var fs = input["flowsheet"].Deserialize<Flowsheet>(Json.Options)!;
        // The rigorous depropanizer alone (feed f, column t, products d and b).
        var keep = new HashSet<string> { "f", "t", "d", "b" };
        fs = fs with
        {
            Objects = fs.Objects.Where(o => keep.Contains(o.Id)).Select(o => o.Id == "t" ? o with { Refs = Refs(o, packing, more) } : o).ToList(),
            Connections = fs.Connections.Where(c => keep.Contains(c.FromObject) && keep.Contains(c.ToObject)).ToList(),
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        foreach (var m in r.Messages) output.WriteLine($"{m.Level}: {m.Text}");
        Assert.Equal(SolveStatus.Converged, r.Status);
        return (r, r.Objects["t"]);
    }

    [Fact]
    public async Task ADepropanizerPackedWithPallRingsIsSizedForSeventyPercentOfFlood()
    {
        const double fp = 183, fpd = 174; // Pall rings, metal, 25 mm (Perry Table 14-7b), 1/m
        var (_, t) = await SolvePacked("Pall rings Metal 25 mm (Perry)");
        var p = t.Properties;
        Assert.Equal("Pall rings Metal 25 mm (Perry)", t.Texts!["packing"]);
        Assert.Contains("Robbins", t.Texts["packingModel"]);
        var prof = t.Profiles!;
        foreach (var k in new[] { ProfileKeys.LiquidViscosity, ProfileKeys.VapourViscosity }) Assert.True(prof.ContainsKey(k), k);

        // The 20 trays are the bed's 20 stages (the condenser and the reboiler are not packed).
        Assert.Equal(20, p["packingStages"]);
        var d = p["packingDiameter"];
        output.WriteLine($"D = {d:0.000} m (required {p["packingRequiredDiameter"]:0.000}), flood {p["packingFloodFraction"]:P1} at stage {p["packingLimitingStage"]}, " +
                         $"bed {p["packingHeight"]:0.00} m, ΔP {p["packingPressureDrop"]:0} Pa, HETP {p["packingHetp"]:0.000} m");
        Assert.InRange(d - p["packingRequiredDiameter"], 0, 0.05 + 1e-9);
        Assert.Equal(0, Math.IEEERemainder(d, 0.05), 1e-9);

        // Each stage again, from the solved profile, by the reference correlations.
        double Flood(int i, double dia)
        {
            var area = Math.PI * dia * dia / 4;
            var g = prof[ProfileKeys.VapourFlow][i] * prof[ProfileKeys.VapourMolarMass][i] / 1000 / area;
            var l = prof[ProfileKeys.LiquidFlow][i] * prof[ProfileKeys.LiquidMolarMass][i] / 1000 / area;
            var rhoG = prof[ProfileKeys.VapourDensity][i];
            return g / rhoG / FloodVelocity(l, rhoG, prof[ProfileKeys.LiquidDensity][i], prof[ProfileKeys.LiquidViscosity][i], fpd, fp);
        }
        double Gradient(int i, double dia)
        {
            var area = Math.PI * dia * dia / 4;
            var g = prof[ProfileKeys.VapourFlow][i] * prof[ProfileKeys.VapourMolarMass][i] / 1000 / area;
            var l = prof[ProfileKeys.LiquidFlow][i] * prof[ProfileKeys.LiquidMolarMass][i] / 1000 / area;
            return Robbins(g, l, prof[ProfileKeys.VapourDensity][i], prof[ProfileKeys.LiquidDensity][i], prof[ProfileKeys.LiquidViscosity][i], fpd);
        }
        var trays = Enumerable.Range(1, 20).ToList(); // profile index: 0 is the condenser
        var limiting = trays.MaxBy(i => Flood(i, d));
        // The stage the engine names as limiting is (to the bisections' tolerance) the most loaded.
        Assert.Equal(Flood(limiting, d), Flood((int)p["packingLimitingStage"] - 1, d), 0.002);
        Assert.Equal(Flood(limiting, d), p["packingFloodFraction"], 0.002);
        // At the required diameter the limiting stage is at 70 % of flood.
        Assert.Equal(0.70, trays.Max(i => Flood(i, p["packingRequiredDiameter"])), 0.002);
        Assert.Equal(trays.Max(i => Gradient(i, d)), p["packingMaxPressureGradient"], p["packingMaxPressureGradient"] * 0.002);

        // Kister's rule (Seader and Henley eq. 6-116): HETP (ft) = 1.5 d_p (in), which is HETP = 18 d_p in any one unit
        // (12 in per ft × 1.5). The catalogue's ring is 25 mm, so 18 × 0.025 m = 0.450 m (a 1-in ring, 25.4 mm, would be
        // 0.4572 m); but not less than the diameter (or 1 ft) below 2 ft (eq. 6-118).
        var hetp = d < 0.6096 ? Math.Max(0.45, Math.Max(d, 0.3048)) : 0.45;
        Assert.Equal(hetp, p["packingHetp"], 1e-9);
        Assert.Equal(20 * hetp, p["packingHeight"], 1e-9);
        Assert.Equal(trays.Sum(i => Gradient(i, d)) * hetp, p["packingPressureDrop"], p["packingPressureDrop"] * 0.002);
    }

    [Fact]
    public async Task TheUsersDiameterHeightAndFloodTargetAreHonoured()
    {
        var (_, t) = await SolvePacked("Pall rings Metal 25 mm (Perry)", new()
        {
            [ChoiceKeys.PackingFlood] = "0.6", [ChoiceKeys.PackingDiameter] = "0.8", [ChoiceKeys.PackingHeight] = "4",
        });
        var p = t.Properties;
        Assert.Equal(0.6, p["packingFloodTarget"]);
        Assert.Equal(0.8, p["packingDiameter"]);
        Assert.Equal(4.0, p["packingHeight"]);
        // The 4 m bed is shared by the 20 stages, 0.2 m each, so the drop is at most 4 m at the steepest gradient.
        Assert.InRange(p["packingPressureDrop"], 0, p["packingMaxPressureGradient"] * 4 + 1e-6);
    }

    [Fact]
    public async Task APackingOutsideTheCatalogueIsAWarningNotAFailure()
    {
        var (r, t) = await SolvePacked("Unobtainium saddles");
        Assert.DoesNotContain("packingDiameter", t.Properties.Keys);
        Assert.Contains(r.Messages, m => m.Level == MessageLevel.Warning && m.Text.Contains("not in DWSIM's packing catalogue"));
        // The tray sizing's numbers are still there.
        Assert.Contains(ProfileKeys.VapourDensity, t.Profiles!.Keys);
    }
}
