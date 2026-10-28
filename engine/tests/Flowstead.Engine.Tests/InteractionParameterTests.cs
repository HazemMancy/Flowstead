// Flowstead engine tests — binary interaction parameters (D58).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class InteractionParameterTests(EngineFixture engine, ITestOutputHelper output)
{
    private static readonly Dictionary<string, double> Oil = new() { ["Methane"] = 0.4, ["N-decane"] = 0.6 };

    private static Flowsheet Fluid(params BinaryParameter[] kij) =>
        new() { PropertyPackage = "Peng-Robinson (PR)", Compounds = Oil.Keys.ToList(), InteractionParameters = kij.ToList() };

    private async Task<List<BinaryParameterInfo>> Table(Flowsheet fs)
    {
        var r = await engine.Authed().PostAsJsonAsync("/interaction-parameters", fs, Json.Options);
        Assert.True(r.IsSuccessStatusCode, await r.Content.ReadAsStringAsync());
        return await EngineFixture.Json<List<BinaryParameterInfo>>(r);
    }

    private async Task<double> Bubble(Flowsheet fs) =>
        (await EngineFixture.Json<PvtResult>(await engine.Authed().PostAsJsonAsync("/pvt",
            new PvtRequest(fs, Oil, PvtExperiments.Saturation, 350), Json.Options))).SaturationPressure!.Value;

    [Fact]
    public async Task UserKijReplacesTheDatabankValueForThatFlowsheetOnly()
    {
        var pair = Assert.Single(await Table(Fluid()));
        output.WriteLine($"DWSIM kij {pair.A}–{pair.B}: {pair.Default}");
        Assert.False(pair.User);

        var plain = await Bubble(Fluid());
        var tuned = await Bubble(Fluid(new BinaryParameter("N-decane", "Methane", 0.15)));
        output.WriteLine($"bubble point at 350 K: {plain / 1e5:0.00} bar with DWSIM's kij, {tuned / 1e5:0.00} bar with 0.15");
        // A larger kij makes methane less soluble: the bubble point rises.
        Assert.True(tuned > plain * 1.05);

        var shown = Assert.Single(await Table(Fluid(new BinaryParameter("Methane", "N-decane", 0.15))));
        Assert.True(shown.User);
        Assert.Equal(0.15, shown.Kij);
        Assert.Equal(pair.Default, shown.Default);

        // The next flowsheet without the pair gets DWSIM's value back.
        Assert.Equal(plain, await Bubble(Fluid()), plain * 1e-6);
    }
}

[Collection(EngineCollection.Name)]
public sealed class CompoundBasisTests(EngineFixture engine, ITestOutputHelper output)
{
    [Fact]
    public async Task MolecularWeightsAndStandardLiquidDensitiesForEnteringMassOrVolume()
    {
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Methane", "Propane", "N-hexane", "Water"] };
        var r = await engine.Authed().PostAsJsonAsync("/compounds/basis", fs, Json.Options);
        Assert.True(r.IsSuccessStatusCode, await r.Content.ReadAsStringAsync());
        var basis = (await EngineFixture.Json<List<CompoundBasis>>(r)).ToDictionary(b => b.Name);
        foreach (var b in basis.Values) output.WriteLine($"{b.Name}: MW {b.MolecularWeight:0.000}, ρ60F {b.StdLiquidDensity:0.0} kg/m³");
        Assert.Equal(16.043, basis["Methane"].MolecularWeight, 0.01);
        Assert.Equal(18.015, basis["Water"].MolecularWeight, 0.01);
        // GPA 2145 for methane (2.5 lb/gal); propane 507, n-hexane 664, water 999 kg/m³ at 60 °F within 1 %.
        Assert.Equal(299.6, basis["Methane"].StdLiquidDensity!.Value, 0.5);
        Assert.Equal(507.0, basis["Propane"].StdLiquidDensity!.Value, 5.0);
        Assert.Equal(664.0, basis["N-hexane"].StdLiquidDensity!.Value, 6.7);
        Assert.Equal(999.0, basis["Water"].StdLiquidDensity!.Value, 10.0);
    }
}
