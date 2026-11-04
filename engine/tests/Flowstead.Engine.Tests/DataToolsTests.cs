// Flowstead engine tests — compounds from structure, VLE regression, the optimiser, catalytic reactions and the
// reaction finder (D104–D106).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

public class StructureCompoundTests(EngineFixture engine, ITestOutputHelper output) : IClassFixture<EngineFixture>
{
    private async Task<HttpResponseMessage> Estimate(string smiles) =>
        await engine.Authed().PostAsJsonAsync("/structure-compound", new StructureCompoundRequest(new Hypothetical { Name = "X", Smiles = smiles }), Json.Options);

    [Theory]
    [InlineData("CCCCCC", "1:2 2:4")]                       // n-hexane
    [InlineData("Cc1ccccc1", "1:1 14:5 15:1")]              // toluene
    [InlineData("CCO", "1:1 2:1 20:1")]                     // ethanol
    [InlineData("CC(=O)O", "1:1 27:1")]                     // acetic acid
    [InlineData("CCOC(C)=O", "1:2 2:1 28:1")]               // ethyl acetate
    [InlineData("CC(C)=O", "1:2 24:1")]                     // acetone
    [InlineData("CC=O", "1:1 26:1")]                        // acetaldehyde
    [InlineData("Oc1ccccc1", "14:5 15:1 21:1")]             // phenol
    [InlineData("C1CCCCC1", "11:6")]                        // cyclohexane
    [InlineData("c1ccncc1", "14:5 35:1")]                   // pyridine
    [InlineData("[O-][N+](=O)c1ccccc1", "14:5 15:1 38:1")]  // nitrobenzene
    [InlineData("CC#N", "1:1 37:1")]                        // acetonitrile
    [InlineData("C=CC", "1:1 5:1 6:1")]                     // propene
    [InlineData("CC(C)(C)C", "1:4 4:1")]                    // neopentane
    [InlineData("ClC(Cl)Cl", "3:1 17:3")]                   // chloroform
    [InlineData("CCN(CC)CC", "1:3 2:3 33:1")]               // triethylamine
    [InlineData("CCS", "1:1 2:1 39:1")]                     // ethanethiol
    [InlineData("C1CCOC1", "11:4 23:1")]                    // tetrahydrofuran
    [InlineData("c1ccc2ccccc2c1", "14:8 15:2")]             // naphthalene
    public async Task SmilesGivesJobacksGroups(string smiles, string expected)
    {
        var want = expected.Split(' ').Select(p => p.Split(':')).ToDictionary(p => int.Parse(p[0]), p => int.Parse(p[1]));
        var res = await Estimate(smiles);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<StructureCompoundResult>(res);
        Assert.Equal(want.OrderBy(k => k.Key), r.Groups.ToDictionary(g => g.Id, g => g.Count).OrderBy(k => k.Key));
    }

    [Theory]
    [InlineData("C")]        // methane: in the database
    [InlineData("O")]        // water
    [InlineData("CC.CC")]    // two molecules
    [InlineData("C1CC")]     // unclosed ring
    [InlineData("[Na+].[Cl-]")]
    public async Task UncoveredStructuresAreRefused(string smiles)
    {
        var res = await Estimate(smiles);
        Assert.Equal(System.Net.HttpStatusCode.UnprocessableEntity, res.StatusCode);
        output.WriteLine($"{smiles}: {await res.Content.ReadAsStringAsync()}");
    }

    [Fact]
    public async Task HexaneByStructureMatchesJobacksPublishedMethod()
    {
        var res = await engine.Authed().PostAsJsonAsync("/structure-compound",
            new StructureCompoundRequest(new Hypothetical { Name = "MyHexane", Smiles = "CCCCCC" }), Json.Options);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<StructureCompoundResult>(res);
        output.WriteLine($"{r.Formula} MW {r.MolecularWeight:F3} Tb {r.NormalBoilingPoint:F2} Tc {r.CriticalTemperature:F2} Pc {r.CriticalPressure / 1e5:F3} bar Vc {r.CriticalVolume * 1e6:F1} cm3/mol w {r.AcentricFactor:F4} Hf {r.EnthalpyOfFormation / 1000:F2} kJ/mol");
        // Joback & Reid (1987) group values (Poling, Prausnitz & O'Connell, 5th ed., Table C-1) by hand:
        // CH3 ×2, CH2 ×4; Tb = 198 + Σ; Tc = Tb/(0.584 + 0.965Σ − Σ²); Pc = (0.113 + 0.0032 nA − Σ)^−2 bar; Vc = 17.5 + Σ.
        var tb = 198.2 + 2 * 23.58 + 4 * 22.88;
        var stc = 2 * 0.0141 + 4 * 0.0189;
        var tc = tb / (0.584 + 0.965 * stc - stc * stc);
        var pc = Math.Pow(0.113 + 0.0032 * 20 - (2 * -0.0012 + 4 * 0.0), -2) * 1e5;
        var vc = (17.5 + 2 * 65 + 4 * 56) * 1e-6;
        var hf = (68.29 + 2 * -76.45 + 4 * -20.64) * 1000;
        Assert.Equal("C6H14", r.Formula);
        Assert.Equal(86.177, r.MolecularWeight, 0.01);
        Assert.Equal(tb, r.NormalBoilingPoint, 0.01);
        Assert.Equal(tc, r.CriticalTemperature, 0.01);
        Assert.Equal(pc, r.CriticalPressure, pc * 1e-4);
        Assert.Equal(vc, r.CriticalVolume, 1e-8);
        Assert.Equal(hf, r.EnthalpyOfFormation, 1);
        // And Joback's usual accuracy against NIST for n-hexane: Tb 341.9 K, Tc 507.6 K, Pc 30.25 bar, Hf −166.9 kJ/mol.
        Assert.InRange(r.NormalBoilingPoint, 341.9 * 0.97, 341.9 * 1.03);
        Assert.InRange(r.CriticalTemperature, 507.6 * 0.97, 507.6 * 1.03);
        Assert.InRange(r.CriticalPressure, 30.25e5 * 0.95, 30.25e5 * 1.05);
        Assert.InRange(r.EnthalpyOfFormation, -166.9e3 - 3e3, -166.9e3 + 3e3);
    }

    [Fact]
    public async Task AStructureCompoundSolvesInAFlowsheet()
    {
        var fs = new Flowsheet
        {
            PropertyPackage = "Peng-Robinson (PR)",
            Compounds = ["MyHexane"],
            Hypotheticals = [new Hypothetical { Name = "MyHexane", Smiles = "CCCCCC" }],
            Objects =
            [
                new SimObject { Id = "b", Tag = "B", Type = ObjectType.MaterialStream, Composition = new() { ["MyHexane"] = 1 },
                    Specs = new() { [SpecKeys.Pressure] = new(101325, "Pa"), [SpecKeys.VapourFraction] = new(0, ""), [SpecKeys.MolarFlow] = new(1, "mol/s") } },
                new SimObject { Id = "l", Tag = "L", Type = ObjectType.MaterialStream, Composition = new() { ["MyHexane"] = 1 },
                    Specs = new() { [SpecKeys.Pressure] = new(101325, "Pa"), [SpecKeys.Temperature] = new(298.15, "K"), [SpecKeys.MolarFlow] = new(1, "mol/s") } },
                new SimObject { Id = "g", Tag = "G", Type = ObjectType.MaterialStream, Composition = new() { ["MyHexane"] = 1 },
                    Specs = new() { [SpecKeys.Pressure] = new(1000, "Pa"), [SpecKeys.Temperature] = new(400, "K"), [SpecKeys.MolarFlow] = new(1, "mol/s") } },
            ],
        };
        var res = await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options);
        var r = await EngineFixture.Json<SolveResult>(res);
        Assert.Equal(SolveStatus.Converged, r.Status);
        var bubble = r.Objects["b"].Properties["temperature"];
        var rho = r.Objects["l"].Phases!["liquid1"].Properties["density"];
        var cp = r.Objects["g"].Phases!["vapour"].Properties["massHeatCapacity"];
        output.WriteLine($"bubble {bubble:F2} K, liquid {rho:F1} kg/m3, Cp(400 K, 1 kPa) {cp:F1} J/kg.K");
        // Lee–Kesler ω from Joback's Tb, Tc and Pc: Peng–Robinson boils it at Joback's Tb within a kelvin.
        Assert.InRange(bubble, 336.88 - 1.0, 336.88 + 1.0);
        // Ideal-gas Cp from Joback's polynomial (J/mol.K) at 400 K, divided by M.
        double A = -37.93 + 2 * 19.5 + 4 * -0.909, B = 0.210 + 2 * -8.08e-3 + 4 * 9.50e-2, C = -3.91e-4 + 2 * 1.53e-4 + 4 * -5.44e-5, D = 2.06e-7 + 2 * -9.67e-8 + 4 * 1.19e-8;
        var cpJoback = (A + B * 400 + C * 400 * 400 + D * Math.Pow(400, 3)) / 86.177 * 1000;
        Assert.Equal(cpJoback, cp, cpJoback * 0.01);
        // NIST: n-hexane liquid at 25 °C is 655 kg/m³; Rackett on Joback's constants is within 5 %.
        Assert.InRange(rho, 655 * 0.95, 655 * 1.05);
    }
}

public class VleRegressionTests(EngineFixture engine, ITestOutputHelper output) : IClassFixture<EngineFixture>
{
    private async Task<List<VlePoint>> Synthetic(Flowsheet fs, string a, string b, string kind, double fixedValue)
    {
        var req = new BinaryDiagramRequest { Flowsheet = fs, First = a, Second = b, Kind = kind, Pressure = fixedValue, Temperature = fixedValue, Steps = 10, LiquidLiquid = false };
        var res = await engine.Authed().PostAsJsonAsync("/binary-diagram", req, Json.Options);
        var d = await EngineFixture.Json<BinaryDiagramResult>(res);
        return d.Bubble.Where(p => p.X is > 0.01 and < 0.99).Select(p => kind == BinaryDiagramKinds.Txy
            ? new VlePoint(p.X, null, p.Y, fixedValue) : new VlePoint(p.X, null, fixedValue, p.Y)).ToList();
    }

    [Fact]
    public async Task RecoversAKnownKij()
    {
        // "Measurements" made by the engine itself with kij = 0.05, then fitted from a flowsheet without it.
        var truth = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Propane", "N-pentane"], InteractionParameters = [new("Propane", "N-pentane", 0.05)] };
        var points = await Synthetic(truth, "Propane", "N-pentane", BinaryDiagramKinds.Txy, 10e5);
        Assert.True(points.Count >= 6);
        var req = new VleRegressionRequest { Flowsheet = truth with { InteractionParameters = [] }, First = "Propane", Second = "N-pentane", Points = points };
        var res = await engine.Authed().PostAsJsonAsync("/regress-vle", req, Json.Options);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<VleRegressionResult>(res);
        output.WriteLine($"kij {r.Parameter.Kij:F5}, AAD {r.DeviationBefore:F3} % -> {r.DeviationAfter:F4} %, {r.Evaluations} evaluations");
        Assert.Equal(0.05, r.Parameter.Kij, 0.002);
        Assert.True(r.DeviationAfter < 0.02);
        Assert.True(r.DeviationBefore > r.DeviationAfter);
    }

    [Fact]
    public async Task RecoversKnownNrtlParameters()
    {
        var truth = new Flowsheet
        {
            PropertyPackage = "NRTL", Compounds = ["Ethanol", "Water"],
            InteractionParameters = [new("Ethanol", "Water", 0) { A12 = -50, A21 = 1300, Alpha12 = 0.3 }],
        };
        var points = await Synthetic(truth, "Ethanol", "Water", BinaryDiagramKinds.Pxy, 343.15);
        var req = new VleRegressionRequest
        {
            Flowsheet = truth with { InteractionParameters = [new("Ethanol", "Water", 0) { A12 = 0, A21 = 0, Alpha12 = 0.3 }] },
            First = "Ethanol", Second = "Water", Kind = BinaryDiagramKinds.Pxy, Points = points, Parameters = RegressionParameters.Nrtl, Alpha = 0.3,
        };
        var res = await engine.Authed().PostAsJsonAsync("/regress-vle", req, Json.Options);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<VleRegressionResult>(res);
        output.WriteLine($"A12 {r.Parameter.A12:F1} A21 {r.Parameter.A21:F1}, AAD {r.DeviationBefore:F3} % -> {r.DeviationAfter:F4} %, {r.Evaluations} evaluations");
        Assert.True(r.DeviationAfter < 0.05, $"AAD {r.DeviationAfter}");
        Assert.Equal(-50.0, r.Parameter.A12!.Value, 25.0);
        Assert.Equal(1300.0, r.Parameter.A21!.Value, 25.0);
    }
}

public class OptimiserTests(EngineFixture engine, ITestOutputHelper output) : IClassFixture<EngineFixture>
{
    [Fact]
    public async Task TwoStageCompressionFindsTheTextbookInterstagePressure()
    {
        // Methane 1 → 9 bar in two adiabatic stages with intercooling back to 300 K: the total power is least near
        // √(1·9) = 3 bar for a near-ideal gas (equal pressure ratios).
        var gas = new Dictionary<string, double> { ["Methane"] = 1.0 };
        var fs = new Flowsheet
        {
            PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Methane"],
            Objects =
            [
                new SimObject { Id = "s1", Tag = "1", Type = ObjectType.MaterialStream, Composition = gas,
                    Specs = new() { [SpecKeys.Temperature] = new(300, "K"), [SpecKeys.Pressure] = new(1e5, "Pa"), [SpecKeys.MolarFlow] = new(10, "mol/s") } },
                new SimObject { Id = "k1", Tag = "K-1", Type = ObjectType.Compressor, Specs = new() { ["outletPressure"] = new(5e5, "Pa"), ["efficiency"] = new(0.75, "") } },
                new SimObject { Id = "s2", Tag = "2", Type = ObjectType.MaterialStream },
                new SimObject { Id = "c", Tag = "E-1", Type = ObjectType.Cooler, Specs = new() { ["outletTemperature"] = new(300, "K"), ["pressureDrop"] = new(0, "Pa") } },
                new SimObject { Id = "s3", Tag = "3", Type = ObjectType.MaterialStream },
                new SimObject { Id = "k2", Tag = "K-2", Type = ObjectType.Compressor, Specs = new() { ["outletPressure"] = new(9e5, "Pa"), ["efficiency"] = new(0.75, "") } },
                new SimObject { Id = "s4", Tag = "4", Type = ObjectType.MaterialStream },
            ],
            Connections = [new("s1", 0, "k1", 0), new("k1", 0, "s2", 0), new("s2", 0, "c", 0), new("c", 0, "s3", 0), new("s3", 0, "k2", 0), new("k2", 0, "s4", 0)],
        };
        var req = new OptimiseRequest
        {
            Flowsheet = fs, Variables = [new("k1", "outletPressure", 1.5e5, 8e5)],
            Objective = [new("k1", "power"), new("k2", "power")], MaxSolves = 80,
        };
        var res = await engine.Authed().PostAsJsonAsync("/optimise", req, Json.Options);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<OptimiseResult>(res);
        output.WriteLine($"P2 {r.Values[0] / 1e5:F4} bar, total {r.Objective / 1000:F3} kW, {r.Solves} solves, converged {r.Converged} " + string.Join(" | ", r.Warnings));
        Assert.True(r.Feasible);
        Assert.InRange(r.Values[0], 2.9e5, 3.1e5);
        // The optimum beats its neighbours by the engine's own solves.
        foreach (var p in new[] { 2.5e5, 3.6e5 })
        {
            var at = await engine.Authed().PostAsJsonAsync("/solve", fs with
            {
                Objects = fs.Objects.Select(o => o.Id == "k1" ? o with { Specs = new() { ["outletPressure"] = new(p, "Pa"), ["efficiency"] = new(0.75, "") } } : o).ToList(),
            }, Json.Options);
            var s = await EngineFixture.Json<SolveResult>(at);
            Assert.True(s.Objects["k1"].Properties["power"] + s.Objects["k2"].Properties["power"] > r.Objective);
        }
    }

    [Fact]
    public async Task ConstraintsAreHonoured()
    {
        // Same train, but the first stage's discharge must stay at or below 120 °C... while minimising stage 2 alone:
        // unconstrained that pushes P2 to its upper bound; the temperature limit caps it.
        var gas = new Dictionary<string, double> { ["Methane"] = 1.0 };
        var fs = new Flowsheet
        {
            PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Methane"],
            Objects =
            [
                new SimObject { Id = "s1", Tag = "1", Type = ObjectType.MaterialStream, Composition = gas,
                    Specs = new() { [SpecKeys.Temperature] = new(300, "K"), [SpecKeys.Pressure] = new(1e5, "Pa"), [SpecKeys.MolarFlow] = new(10, "mol/s") } },
                new SimObject { Id = "k1", Tag = "K-1", Type = ObjectType.Compressor, Specs = new() { ["outletPressure"] = new(3e5, "Pa"), ["efficiency"] = new(0.75, "") } },
                new SimObject { Id = "s2", Tag = "2", Type = ObjectType.MaterialStream },
            ],
            Connections = [new("s1", 0, "k1", 0), new("k1", 0, "s2", 0)],
        };
        var req = new OptimiseRequest
        {
            Flowsheet = fs, Variables = [new("k1", "outletPressure", 1.5e5, 8e5)], Objective = [new("s2", "pressure")], Maximise = true,
            Constraints = [new("s2", "temperature", Max: 393.15)], MaxSolves = 80,
        };
        var r = await EngineFixture.Json<OptimiseResult>(await engine.Authed().PostAsJsonAsync("/optimise", req, Json.Options));
        output.WriteLine($"P2 {r.Values[0] / 1e5:F4} bar, T2 {r.ConstraintValues[0] - 273.15:F2} °C, feasible {r.Feasible}");
        Assert.True(r.Feasible);
        Assert.InRange(r.ConstraintValues[0]!.Value, 393.15 - 1.0, 393.15 + 0.05);
        Assert.True(r.Values[0] < 8e5 * 0.99);
    }
}

public class CatalyticReactionTests(EngineFixture engine, ITestOutputHelper output) : IClassFixture<EngineFixture>
{
    // Ethylene oxide hydrolysis in water (benchmark 15), first order in EO, k = 3e-4 1/s.
    private static Flowsheet Case(Reaction r, ObjectType reactor, Dictionary<string, Spec> extra)
    {
        var feed = new Dictionary<string, double> { ["Ethylene oxide"] = 0.05, ["Water"] = 0.95 };
        var specs = new Dictionary<string, Spec>(extra) { [SpecKeys.Volume] = new(1.0, "m3") };
        if (reactor == ObjectType.Pfr) specs[SpecKeys.ReactorLength] = new(10, "m");
        return new Flowsheet
        {
            PropertyPackage = "NRTL", Compounds = ["Ethylene oxide", "Water", "Ethylene glycol"], Reactions = [r],
            Objects =
            [
                new SimObject { Id = "f", Tag = "F", Type = ObjectType.MaterialStream, Composition = feed,
                    Specs = new() { [SpecKeys.Temperature] = new(328.15, "K"), [SpecKeys.Pressure] = new(5e5, "Pa"), [SpecKeys.MolarFlow] = new(10, "mol/s") } },
                new SimObject { Id = "r", Tag = "R", Type = reactor, Specs = specs, Refs = new() { [ReactorKeys.Reactions] = r.Id, [ReactorKeys.Mode] = "isothermal" } },
                new SimObject { Id = "o", Tag = "O", Type = ObjectType.MaterialStream },
            ],
            Connections = [new("f", 0, "r", 0), new("r", 0, "o", 0)],
        };
    }

    private static readonly Dictionary<string, double> Stoich = new() { ["Ethylene oxide"] = -1, ["Water"] = -1, ["Ethylene glycol"] = 1 };
    private static Reaction Kinetic => new()
    {
        Id = "k", Name = "EO hydrolysis", Type = ReactionType.Kinetic, Stoichiometry = Stoich, BaseCompound = "Ethylene oxide",
        Phase = ReactionPhase.Liquid, ForwardOrders = new() { ["Ethylene oxide"] = 1 }, ForwardA = 3e-4, ForwardE = 0,
    };
    private static Reaction Catalytic(string numerator, string? denominator = null) => new()
    {
        Id = "c", Name = "EO hydrolysis (cat)", Type = ReactionType.Catalytic, Stoichiometry = Stoich, BaseCompound = "Ethylene oxide",
        Phase = ReactionPhase.Liquid, RateNumerator = numerator, RateDenominator = denominator,
    };

    private async Task<(double In, double Out, double Q)> Eo(Flowsheet fs)
    {
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.True(r.Status == SolveStatus.Converged, string.Join("; ", r.Messages.Select(m => m.Text)));
        double F(string id) => r.Objects[id].Properties["molarFlow"] * r.Objects[id].Phases!["overall"].Composition["Ethylene oxide"];
        return (F("f"), F("o"), r.Objects["o"].Phases!["overall"].Properties["volumeFlow"]);
    }

    [Fact]
    public async Task WithoutAdsorptionACatalyticRateIsTheKineticOne()
    {
        // Both reactors run the per-kg rate on the whole catalyst mass, loading × volume: the PFR multiplies by
        // loading / ε over its fluid volume V·ε, the CSTR by its catalyst amount.
        var pfrSpecs = new Dictionary<string, Spec> { [SpecKeys.CatalystLoading] = new(500, "kg/m3"), [SpecKeys.CatalystVoidFraction] = new(0.5, "") };
        var kPfr = await Eo(Case(Kinetic, ObjectType.Pfr, []));
        var cPfr = await Eo(Case(Catalytic("3e-4/500*R1"), ObjectType.Pfr, pfrSpecs));
        var cstrSpecs = new Dictionary<string, Spec> { [SpecKeys.CatalystLoading] = new(500, "kg/m3") };
        var kCstr = await Eo(Case(Kinetic, ObjectType.Cstr, []));
        var cCstr = await Eo(Case(Catalytic("3e-4/500*R1"), ObjectType.Cstr, cstrSpecs));
        output.WriteLine($"PFR EO out {kPfr.Out:F6} vs {cPfr.Out:F6}; CSTR {kCstr.Out:F6} vs {cCstr.Out:F6}");
        Assert.Equal(kPfr.Out, cPfr.Out, kPfr.Out * 1e-4);
        Assert.Equal(kCstr.Out, cCstr.Out, kCstr.Out * 1e-4);
        Assert.True(kPfr.Out < kPfr.In * 0.99);
    }

    [Fact]
    public async Task LangmuirHinshelwoodCstrMeetsItsDesignEquation()
    {
        // r = k·C/(1 + K·C) per kg; the CSTR balance F0 − F = W·r(C_out) with C_out = F/Q at the outlet.
        const double k = 3e-4 / 500, K = 2e-3, W = 500.0;
        var (f0, f, q) = await Eo(Case(Catalytic($"{k}*R1", $"1+{K}*R1"), ObjectType.Cstr, new() { [SpecKeys.CatalystLoading] = new(W, "kg/m3") }));
        var c = f / q;
        var rate = W * k * c / (1 + K * c);
        output.WriteLine($"F0 {f0:F5} F {f:F5} mol/s, C {c:F2} mol/m3, consumed {f0 - f:F6} vs W·r {rate:F6}");
        Assert.Equal(f0 - f, rate, (f0 - f) * 0.01);
        var plain = await Eo(Case(Kinetic, ObjectType.Cstr, []));
        Assert.True(f > plain.Out); // adsorption slows it
    }
}

public class ReactionFinderTests(EngineFixture engine, ITestOutputHelper output) : IClassFixture<EngineFixture>
{
    private async Task<ReactionFinderResult> Find(params string[] compounds)
    {
        var res = await engine.Authed().PostAsJsonAsync("/find-reactions",
            new ReactionFinderRequest(new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = [.. compounds] }), Json.Options);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<ReactionFinderResult>(res);
        foreach (var s in r.Reactions) output.WriteLine($"{s.Category}: {s.Name}  {s.Equation}  [{s.Reaction.Type}] {s.Note}");
        foreach (var w in r.Warnings) output.WriteLine("! " + w);
        return r;
    }

    [Fact]
    public async Task MethaneCombustionIsFound()
    {
        var r = await Find("Methane", "Oxygen", "Carbon dioxide", "Water");
        var burn = r.Reactions.FirstOrDefault(s => s.Reaction.Stoichiometry.GetValueOrDefault("Methane") == -1 && s.Reaction.Stoichiometry.GetValueOrDefault("Oxygen") == -2);
        Assert.NotNull(burn);
        Assert.Equal(1, burn!.Reaction.Stoichiometry["Carbon dioxide"]);
        Assert.Equal(2, burn.Reaction.Stoichiometry["Water"]);
        // Element balance of every suggestion: C, H and O are conserved.
        var atoms = new Dictionary<string, (int C, int H, int O)> { ["Methane"] = (1, 4, 0), ["Oxygen"] = (0, 0, 2), ["Carbon dioxide"] = (1, 0, 2), ["Water"] = (0, 2, 1) };
        foreach (var s in r.Reactions)
        {
            var st = s.Reaction.Stoichiometry;
            Assert.Equal(0, st.Sum(kv => kv.Value * atoms[kv.Key].C), 1e-9);
            Assert.Equal(0, st.Sum(kv => kv.Value * atoms[kv.Key].H), 1e-9);
            Assert.Equal(0, st.Sum(kv => kv.Value * atoms[kv.Key].O), 1e-9);
        }
    }

    [Fact]
    public async Task ASuggestedCatalyticReactionRunsInAReactor()
    {
        var r = await Find("Carbon monoxide", "Water", "Carbon dioxide", "Hydrogen");
        var wgs = r.Reactions.FirstOrDefault(s => s.Reaction.Type == ReactionType.Catalytic);
        Assert.NotNull(wgs);
        var rx = wgs!.Reaction;
        var feed = new Dictionary<string, double> { ["Carbon monoxide"] = 0.3, ["Water"] = 0.6, ["Carbon dioxide"] = 0.05, ["Hydrogen"] = 0.05 };
        var t = rx.MinTemperature is { } lo && rx.MaxTemperature is { } hi ? (lo + hi) / 2 : 650.0;
        var fs = new Flowsheet
        {
            PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Carbon monoxide", "Water", "Carbon dioxide", "Hydrogen"], Reactions = [rx],
            Objects =
            [
                new SimObject { Id = "f", Tag = "F", Type = ObjectType.MaterialStream, Composition = feed,
                    Specs = new() { [SpecKeys.Temperature] = new(t, "K"), [SpecKeys.Pressure] = new(20e5, "Pa"), [SpecKeys.MolarFlow] = new(10, "mol/s") } },
                new SimObject { Id = "r", Tag = "R", Type = ObjectType.Pfr, Refs = new() { [ReactorKeys.Reactions] = rx.Id, [ReactorKeys.Mode] = "isothermal" },
                    Specs = new() { [SpecKeys.Volume] = new(1, "m3"), [SpecKeys.ReactorLength] = new(5, "m"), [SpecKeys.CatalystLoading] = new(800, "kg/m3"), [SpecKeys.CatalystVoidFraction] = new(0.4, "") } },
                new SimObject { Id = "o", Tag = "O", Type = ObjectType.MaterialStream },
            ],
            Connections = [new("f", 0, "r", 0), new("r", 0, "o", 0)],
        };
        var s = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.True(s.Status == SolveStatus.Converged, string.Join("; ", s.Messages.Select(m => m.Text)));
        var coIn = 10 * 0.3;
        var coOut = s.Objects["o"].Properties["molarFlow"] * s.Objects["o"].Phases!["overall"].Composition["Carbon monoxide"];
        output.WriteLine($"CO {coIn:F4} -> {coOut:F4} mol/s at {t - 273.15:F0} °C");
        Assert.True(coOut < coIn); // the shift goes forward from this feed
        var h2Out = s.Objects["o"].Properties["molarFlow"] * s.Objects["o"].Phases!["overall"].Composition["Hydrogen"];
        Assert.Equal(coIn - coOut, h2Out - 10 * 0.05, 1e-6); // one H2 per CO
    }
}
