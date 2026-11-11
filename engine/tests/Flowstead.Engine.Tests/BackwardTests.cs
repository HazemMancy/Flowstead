// Flowstead engine tests — backward specifications (D151): a product's conditions met by its feed upstream.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using Flowstead.Engine.Api;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

/// <summary>
/// The owner asked why Flowstead only solves forward, where HYSYS's solver also works backward. A product stream of a
/// valve, heater or the like now takes specifications, and the feed upstream is found. References: the energy balance
/// of each unit, with the product's enthalpy from a separate flash of its own conditions.
/// </summary>
[Collection(EngineCollection.Name)]
public sealed class BackwardTests(EngineFixture engine, ITestOutputHelper output)
{
    private static readonly Dictionary<string, double> Gas = new()
    {
        ["Nitrogen"] = 0.01, ["Carbon dioxide"] = 0.02, ["Methane"] = 0.85, ["Ethane"] = 0.07, ["Propane"] = 0.03,
        ["N-butane"] = 0.015, ["N-pentane"] = 0.005,
    };

    private static SimObject Stream(string id, Dictionary<string, Spec>? specs = null, Dictionary<string, double>? x = null) =>
        new() { Id = id, Tag = id.ToUpperInvariant(), Type = ObjectType.MaterialStream, Specs = specs ?? [], Composition = x ?? [] };

    private static Flowsheet Line(ObjectType type, Dictionary<string, Spec> unitSpecs, SimObject feed, SimObject product, SimObject? energy = null)
    {
        var objects = new List<SimObject> { feed, new() { Id = "u", Tag = "U-100", Type = type, Specs = unitSpecs }, product };
        var connections = new List<Connection> { new(feed.Id, 0, "u", 0), new("u", 0, product.Id, 0) };
        if (energy is not null) { objects.Add(energy); connections.Add(new(energy.Id, 0, "u", 1)); }
        return new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = Gas.Keys.ToList(), Objects = objects, Connections = connections };
    }

    private async Task<SolveResult> Solve(Flowsheet fs) =>
        await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));

    /// <summary>The engine's mass enthalpy of the gas at <paramref name="t"/> and <paramref name="p"/> (a flash of a lone stream).</summary>
    private async Task<double> Enthalpy(double t, double p)
    {
        var r = await Solve(new Flowsheet
        {
            PropertyPackage = "Peng-Robinson (PR)", Compounds = Gas.Keys.ToList(),
            Objects = [Stream("s", new() { [SpecKeys.Temperature] = new(t, "K"), [SpecKeys.Pressure] = new(p, "Pa"), [SpecKeys.MolarFlow] = new(1, "mol/s") }, Gas)],
        });
        return r.Objects["s"].Properties["massEnthalpy"];
    }

    [Fact]
    public async Task AValvesProductTemperatureFindsTheWellheadTemperatureAtTheSameEnthalpy()
    {
        // The separator side known (40 bara, 18 °C), the wellhead at 70 bara: what temperature is the well?
        var feed = Stream("f", new() { [SpecKeys.Pressure] = new(70e5, "Pa"), [SpecKeys.MolarFlow] = new(277.78, "mol/s") }, Gas);
        var product = Stream("p", new() { [SpecKeys.Temperature] = new(291.15, "K") });
        var r = await Solve(Line(ObjectType.Valve, new() { [SpecKeys.OutletPressure] = new(40e5, "Pa") }, feed, product));
        Assert.Equal(SolveStatus.Converged, r.Status);
        Assert.DoesNotContain(r.Objects.Keys, k => k.StartsWith("__backward_"));
        var tIn = r.Objects["f"].Properties["temperature"];
        var tOut = r.Objects["p"].Properties["temperature"];
        output.WriteLine($"Wellhead {tIn - 273.15:F3} °C for 18 °C at the separator");
        Assert.Equal(291.15, tOut, 0.01);
        Assert.True(tIn > tOut, "the gas cools across the valve");
        // Isenthalpic: the wellhead's enthalpy at the found temperature is the product's, flashed on its own at 40 bara, 18 °C.
        var hOut = await Enthalpy(291.15, 40e5);
        Assert.Equal(hOut, r.Objects["f"].Properties["massEnthalpy"], 30.0); // J/kg, of about 4 MJ/kg
        Assert.Equal(hOut, await Enthalpy(tIn, 70e5), 30.0);
    }

    [Fact]
    public async Task AHeatersDutyAndProductTemperatureFindTheFeedTemperatureByTheEnergyBalance()
    {
        const double q = 500e3, w = 5.0; // W, kg/s
        var feed = Stream("f", new() { [SpecKeys.Pressure] = new(30e5, "Pa"), [SpecKeys.MassFlow] = new(w, "kg/s") }, Gas);
        var product = Stream("p", new() { [SpecKeys.Temperature] = new(353.15, "K") });
        var e = new SimObject { Id = "e", Tag = "Q-100", Type = ObjectType.EnergyStream };
        var r = await Solve(Line(ObjectType.Heater, new() { [SpecKeys.Duty] = new(q, "W"), [SpecKeys.PressureDrop] = new(0.5e5, "Pa") }, feed, product, e));
        Assert.Equal(SolveStatus.Converged, r.Status);
        var tIn = r.Objects["f"].Properties["temperature"];
        output.WriteLine($"Feed {tIn - 273.15:F3} °C for 80 °C after 500 kW");
        // h_in = h_out − Q/ṁ, with h_out flashed on its own at 29.5 bara and 80 °C.
        var hOut = await Enthalpy(353.15, 29.5e5);
        Assert.Equal(hOut - q / w, await Enthalpy(tIn, 30e5), 50.0);
        Assert.Equal(353.15, r.Objects["p"].Properties["temperature"], 0.01);
    }

    [Fact]
    public async Task FlowAndCompositionGivenOnTheProductAreTheFeeds()
    {
        var feed = Stream("f", new() { [SpecKeys.Temperature] = new(320, "K"), [SpecKeys.Pressure] = new(50e5, "Pa") });
        var product = Stream("p", new() { [SpecKeys.MolarFlow] = new(100, "mol/s") }, Gas);
        var r = await Solve(Line(ObjectType.Valve, new() { [SpecKeys.PressureDrop] = new(10e5, "Pa") }, feed, product));
        Assert.Equal(SolveStatus.Converged, r.Status);
        Assert.Equal(100, r.Objects["f"].Properties["molarFlow"], 1e-6);
        Assert.Equal(100, r.Objects["p"].Properties["molarFlow"], 1e-6);
        Assert.Equal(0.85, r.Objects["p"].Phases!["overall"].Composition["Methane"], 1e-9);
    }

    [Fact]
    public async Task ATargetTheLineCantReachIsRefusedWithTheReason()
    {
        // The heater fixes its own outlet temperature: nothing upstream can change it.
        var feed = Stream("f", new() { [SpecKeys.Pressure] = new(30e5, "Pa"), [SpecKeys.MolarFlow] = new(10, "mol/s") }, Gas);
        var product = Stream("p", new() { [SpecKeys.Temperature] = new(353.15, "K") });
        var e = new SimObject { Id = "e", Tag = "Q-100", Type = ObjectType.EnergyStream };
        var r = await Solve(Line(ObjectType.Heater, new() { [SpecKeys.OutletTemperature] = new(360, "K") }, feed, product, e));
        Assert.Equal(SolveStatus.Invalid, r.Status);
        Assert.Contains(r.Messages, m => m.ObjectId == "p" && m.Text.Contains("fixes its outlet temperature"));
        // Three state values on one line: over-specified.
        var full = Stream("f", new() { [SpecKeys.Temperature] = new(320, "K"), [SpecKeys.Pressure] = new(50e5, "Pa"), [SpecKeys.MolarFlow] = new(10, "mol/s") }, Gas);
        r = await Solve(Line(ObjectType.Valve, new() { [SpecKeys.PressureDrop] = new(10e5, "Pa") }, full, Stream("p", new() { [SpecKeys.Temperature] = new(300, "K") })));
        Assert.Contains(r.Messages, m => m.ObjectId == "p" && m.Text.Contains("over-specified"));
    }
}
