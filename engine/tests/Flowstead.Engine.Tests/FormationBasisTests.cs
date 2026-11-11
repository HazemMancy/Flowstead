// Flowstead engine — HYSYS's enthalpy and entropy basis (D119), checked against NIST. Copyright (C) 2026 Flowstead contributors.
// SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using System.Text.Json.Nodes;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class FormationBasisTests(EngineFixture engine, ITestOutputHelper output)
{
    private static Flowsheet Stream(string package, Dictionary<string, double> x, double tC, double pKpa, string? basis, List<Hypothetical>? hypo = null, Dictionary<string, Spec>? specs = null) => new()
    {
        PropertyPackage = package, Compounds = x.Keys.ToList(), EnthalpyBasis = basis, Hypotheticals = hypo ?? [],
        Objects = [new SimObject { Id = "s", Tag = "S", Type = ObjectType.MaterialStream, Composition = x,
            Specs = specs ?? new() { [SpecKeys.Temperature] = new(tC + 273.15, "C"), [SpecKeys.Pressure] = new(pKpa * 1000, "kPa"), [SpecKeys.MolarFlow] = new(1.0, "kgmol/h") } }],
    };

    private async Task<JsonNode> Solve(Flowsheet f)
    {
        var r = await engine.Authed().PostAsJsonAsync("/solve", f, Json.Options);
        r.EnsureSuccessStatusCode();
        var j = JsonNode.Parse(await r.Content.ReadAsStringAsync())!;
        Assert.Equal("converged", j["status"]!.GetValue<string>());
        return j["objects"]!["s"]!;
    }

    private static double P(JsonNode s, string key) => s["properties"]![key]!.GetValue<double>();
    private static double PO(JsonNode s, string key) => s["phases"]!["overall"]!["properties"]![key]!.GetValue<double>();
    private const string PR = "Peng-Robinson (PR)";

    [Fact]
    public async Task PureMethaneAt25CIsItsHeatOfFormationAndAbsoluteEntropy()
    {
        // NIST: methane ideal gas Hf(298.15 K) = −74.87 kJ/mol, S°(298.15 K, 1 bar) = 186.25 J/(mol·K), + R ln 1.01325 for 1 atm.
        // At 1 atm the equation of state's residual is a few J/mol.
        var s = await Solve(Stream(PR, new() { ["Methane"] = 1 }, 25, 101.325, FormationBasisName));
        var mw = P(s, "molarFlow") > 0 ? P(s, "massFlow") / P(s, "molarFlow") : 0; // kg/mol
        var hMolar = s["phases"]!["overall"]!["properties"]!["molarEnthalpy"]!.GetValue<double>(); // J/mol
        output.WriteLine($"methane: H {hMolar:F1} J/mol, massH {P(s, "massEnthalpy"):F0} J/kg, massS {PO(s, "massEntropy"):F1} J/kg/K, MW {mw * 1000:F3}");
        Assert.InRange(hMolar, -74_870 - 400, -74_870 + 400);
        Assert.InRange(P(s, "massEnthalpy") * mw, -74_870 - 400, -74_870 + 400);           // J/kg × kg/mol = J/mol
        Assert.InRange(PO(s, "massEntropy") * mw, 186.14 - 1.5, 186.14 + 1.5);              // J/(mol·K)
    }

    [Fact]
    public async Task AMixtureIsTheMoleWeightedSumAndDWSIMsOwnBasisIsUnchanged()
    {
        // 50 / 50 methane and ethane (NIST Hf −74.87 and −84.0 kJ/mol): −79.44 kJ/mol; entropy is the compounds' (186.25, 229.16) plus ideal mixing R·ln 2 = 5.76.
        var x = new Dictionary<string, double> { ["Methane"] = 0.5, ["Ethane"] = 0.5 };
        var hy = await Solve(Stream(PR, x, 25, 101.325, FormationBasisName));
        var raw = await Solve(Stream(PR, x, 25, 101.325, null));
        var mw = P(hy, "massFlow") / P(hy, "molarFlow");
        Assert.InRange(P(hy, "massEnthalpy") * mw, -79_435 - 500, -79_435 + 500);
        Assert.InRange(PO(hy, "massEntropy") * mw, 0.5 * 186.14 + 0.5 * 229.05 + 5.763 - 2.0, 0.5 * 186.14 + 0.5 * 229.05 + 5.763 + 2.0);
        // Without the option the engine gives what it always did: near zero at 25 °C (ideal-gas reference), no heat of formation.
        Assert.InRange(P(raw, "massEnthalpy") * mw, -300, 300);
    }

    [Fact]
    public async Task ADifferenceIsTheSameOnEitherBasis_AndATypedEnthalpyGivesTheTemperatureBack()
    {
        var x = new Dictionary<string, double> { ["Methane"] = 0.85, ["Ethane"] = 0.1, ["Propane"] = 0.05 };
        var cold = await Solve(Stream(PR, x, 20, 5000, FormationBasisName));
        var hot = await Solve(Stream(PR, x, 80, 5000, FormationBasisName));
        var coldRaw = await Solve(Stream(PR, x, 20, 5000, null));
        var hotRaw = await Solve(Stream(PR, x, 80, 5000, null));
        // ΔH between 20 and 80 °C: the same on both bases (to the J/kg the basis shift's rounding allows).
        Assert.Equal(P(hotRaw, "massEnthalpy") - P(coldRaw, "massEnthalpy"), P(hot, "massEnthalpy") - P(cold, "massEnthalpy"), 1);
        // A stream specified by pressure and the enthalpy the 80 °C stream reported comes back at 80 °C.
        var back = await Solve(Stream(PR, x, 0, 0, FormationBasisName, specs: new()
        {
            [SpecKeys.Pressure] = new(5_000_000, "kPa"), [SpecKeys.MassEnthalpy] = new(P(hot, "massEnthalpy"), "J/kg"), [SpecKeys.MolarFlow] = new(1.0, "kgmol/h"),
        }));
        Assert.Equal(353.15, P(back, "temperature"), 2);
        // A typed entropy the same way.
        var backS = await Solve(Stream(PR, x, 0, 0, FormationBasisName, specs: new()
        {
            [SpecKeys.Pressure] = new(5_000_000, "kPa"), [SpecKeys.MassEntropy] = new(PO(hot, "massEntropy"), "J/kg.K"), [SpecKeys.MolarFlow] = new(1.0, "kgmol/h"),
        }));
        Assert.InRange(P(backS, "temperature"), 353.15 - 0.05, 353.15 + 0.05);
    }

    [Fact]
    public async Task APackageWithAnotherReferenceIsLeftAlone()
    {
        // Steam tables and activity models have their own reference: the option changes nothing for them.
        var a = await Solve(Stream("Steam Tables (IAPWS-IF97)", new() { ["Water"] = 1 }, 150, 500, FormationBasisName));
        var b = await Solve(Stream("Steam Tables (IAPWS-IF97)", new() { ["Water"] = 1 }, 150, 500, null));
        Assert.Equal(P(b, "massEnthalpy"), P(a, "massEnthalpy"), 6);
    }

    [Fact]
    public async Task AHypotheticalTakesItsFormationValuesFromTheParaffinSeries()
    {
        // n-hexane's properties as a pseudo-component (NBP 341.9 K, SG 0.664): NIST Hf(g) = −167.2 kJ/mol.
        var x = new Dictionary<string, double> { ["HEXLIKE"] = 1 };
        var s = await Solve(Stream(PR, x, 25, 101.325, FormationBasisName, [new Hypothetical { Name = "HEXLIKE", NormalBoilingPoint = 341.9, SpecificGravity = 0.664 }]));
        var mw = P(s, "massFlow") / P(s, "molarFlow");
        var h = s["phases"]!["overall"]!["properties"]!["molarEnthalpy"]!.GetValue<double>();
        output.WriteLine($"hexane-like hypothetical: H {h:F0} J/mol, MW {mw * 1000:F2}, S {PO(s, "massEntropy") * mw:F1}");
        // At 25 °C and 1 atm n-hexane is a liquid, so what is reported is its liquid enthalpy of formation (NIST: −198.7 kJ/mol)
        // and liquid entropy (296.06 J/(mol·K)): the gas values of the series (−167.2, 388.8) less the vaporisation.
        Assert.InRange(h, -198_700 - 3_000, -198_700 + 3_000);
        Assert.InRange(PO(s, "massEntropy") * mw, 296.06 - 6, 296.06 + 6);
    }

    [Fact]
    public async Task EveryCommonCompoundIsItsNistHeatOfFormationAndEntropy()
    {
        // NIST-JANAF / NIST WebBook, ideal gas at 298.15 K: Hf kJ/mol, S° J/(mol·K) at 1 bar. At 0.5 kPa every one is a gas;
        // S° is compared after taking off R ln(101.325/0.5) for the pressure and adding R ln 1.01325 for 1 bar → 1 atm.
        // ChemSep writes structural formulas (CH3(CH2)4CH3, (C6H5)CH3, HOH): the atoms must be counted through them.
        var nist = new (string Name, double Hf, double S0)[]
        {
            ("Methane", -74.87, 186.25), ("Ethane", -84.0, 229.2), ("Propane", -104.7, 270.3), ("Isobutane", -134.2, 295.4),
            ("N-butane", -125.6, 310.2), ("Isopentane", -153.6, 343.7), ("N-pentane", -146.8, 349.1), ("N-hexane", -166.9, 388.8),
            ("Carbon dioxide", -393.51, 213.79), ("Hydrogen sulfide", -20.6, 205.81), ("Nitrogen", 0, 191.61), ("Water", -241.83, 188.84),
            ("Benzene", 82.9, 269.2), ("Toluene", 50.4, 320.7), ("Methanol", -201.0, 239.9),
        };
        var problems = new List<string>();
        foreach (var (name, hf, s0) in nist)
        {
            var s = await Solve(Stream(PR, new() { [name] = 1 }, 25, 0.5, FormationBasisName));
            var mw = P(s, "massFlow") / P(s, "molarFlow");
            var h = s["phases"]!["overall"]!["properties"]!["molarEnthalpy"]!.GetValue<double>() / 1000;
            var sm = PO(s, "massEntropy") * mw - 8.314462618 * Math.Log(101.325 / 0.5) + 8.314462618 * Math.Log(1.01325);
            output.WriteLine($"{name,-18} H {h,9:F2} (NIST {hf,8:F2})   S {sm,7:F2} (NIST {s0,7:F2})");
            if (Math.Abs(h - hf) > 1.0) problems.Add($"{name}: H {h:F2} kJ/mol, NIST {hf}");
            if (Math.Abs(sm - s0) > 1.5) problems.Add($"{name}: S {sm:F2} J/(mol·K), NIST {s0}");
        }
        Assert.True(problems.Count == 0, string.Join("; ", problems));
    }

    [Fact]
    public async Task LiquidWaterIsItsHeatOfFormationLessPengRobinsonsVaporisation()
    {
        // NIST liquid water at 25 °C: −285.83 kJ/mol. Peng-Robinson's departure gives a heat of vaporisation about 1.7 kJ/mol
        // above the steam tables', as HYSYS's PR does; the free-water phase of a gas–water case is the same liquid.
        foreach (var x in new[] { new Dictionary<string, double> { ["Water"] = 1 }, new Dictionary<string, double> { ["Water"] = 0.5, ["Methane"] = 0.5 } })
        {
            var s = await Solve(Stream(PR, x, 25, 101.325, FormationBasisName));
            var water = s["phases"]!.AsObject().First(p => p.Key != "overall" && p.Value!["composition"]?["Water"]?.GetValue<double>() > 0.99).Value!;
            var h = water["properties"]!["molarEnthalpy"]!.GetValue<double>() / 1000;
            Assert.InRange(h, -285.83 - 2.5, -285.83 + 2.5);
        }
    }

    private const string FormationBasisName = "formation";
}
