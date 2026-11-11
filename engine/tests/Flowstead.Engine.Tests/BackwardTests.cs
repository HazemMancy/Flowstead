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

    // ---------- widened (D154): heat exchangers, splitters, separators, enthalpy targets ----------

    private static readonly Dictionary<string, double> Rich = new()
    {
        ["Methane"] = 0.70, ["Ethane"] = 0.10, ["Propane"] = 0.08, ["N-butane"] = 0.06, ["N-pentane"] = 0.04, ["Nitrogen"] = 0.02,
    };

    private static Flowsheet Case(Dictionary<string, double> x, List<SimObject> objects, List<Connection> connections) =>
        new() { PropertyPackage = "Peng-Robinson (PR)", Compounds = x.Keys.ToList(), Objects = objects, Connections = connections };

    [Fact]
    public async Task ASplitterProductsFlowFindsTheFeedFlowThroughTheSplitFraction()
    {
        // 30 % to outlet 1 and 30 mol/s wanted there: the feed is 30 / 0.3 = 100 mol/s (hand).
        var feed = Stream("f", new() { [SpecKeys.Temperature] = new(300, "K"), [SpecKeys.Pressure] = new(20e5, "Pa") }, Gas);
        var p1 = Stream("p1", new() { [SpecKeys.MolarFlow] = new(30, "mol/s") });
        var p2 = Stream("p2");
        var fs = Case(Gas, [feed, new() { Id = "t", Tag = "TEE-100", Type = ObjectType.Splitter, Specs = new() { [SpecKeys.Split1] = new(0.3, "") } }, p1, p2],
            [new("f", 0, "t", 0), new("t", 0, "p1", 0), new("t", 1, "p2", 0)]);
        var r = await Solve(fs);
        Assert.Equal(SolveStatus.Converged, r.Status);
        Assert.Equal(100, r.Objects["f"].Properties["molarFlow"], 1e-3);
        Assert.Equal(30, r.Objects["p1"].Properties["molarFlow"], 1e-4);
        Assert.Equal(70, r.Objects["p2"].Properties["molarFlow"], 1e-3);
    }

    [Fact]
    public async Task ASeparatorsVapourTemperatureAndFlowFindTheWellheadTemperatureAndFlow()
    {
        // Wellhead at 70 bara through a choke to 40 bara and an adiabatic separator: the gas must leave at −10 °C, 90 mol/s.
        var feed = Stream("f", new() { [SpecKeys.Pressure] = new(70e5, "Pa") }, Rich);
        var gas = Stream("g", new() { [SpecKeys.Temperature] = new(263.15, "K"), [SpecKeys.MolarFlow] = new(90, "mol/s") });
        var objects = new List<SimObject>
        {
            feed, new() { Id = "v", Tag = "VLV-100", Type = ObjectType.Valve, Specs = new() { [SpecKeys.OutletPressure] = new(40e5, "Pa") } },
            Stream("m"), new() { Id = "s", Tag = "V-100", Type = ObjectType.Separator }, gas, Stream("l"),
        };
        var r = await Solve(Case(Rich, objects, [new("f", 0, "v", 0), new("v", 0, "m", 0), new("m", 0, "s", 0), new("s", 0, "g", 0), new("s", 1, "l", 0)]));
        Assert.Equal(SolveStatus.Converged, r.Status);
        var tIn = r.Objects["f"].Properties["temperature"];
        output.WriteLine($"Wellhead {tIn - 273.15:F2} °C, {r.Objects["f"].Properties["molarFlow"]:F2} mol/s; liquid {r.Objects["l"].Properties.GetValueOrDefault("molarFlow"):F2} mol/s");
        Assert.Equal(263.15, r.Objects["g"].Properties["temperature"], 0.01);
        Assert.Equal(90, r.Objects["g"].Properties["molarFlow"], 1e-3);
        // Mass balance across the separator, and the choke isenthalpic: the wellhead's enthalpy is the separator feed's,
        // which an adiabatic separator at −10 °C and 40 bara has (a flash of the whole fluid there, on its own).
        Assert.Equal(r.Objects["f"].Properties["molarFlow"], 90 + r.Objects["l"].Properties.GetValueOrDefault("molarFlow"), 1e-3);
        var flash = await Solve(Case(Rich, [Stream("x", new() { [SpecKeys.Temperature] = new(263.15, "K"), [SpecKeys.Pressure] = new(40e5, "Pa"), [SpecKeys.MolarFlow] = new(1, "mol/s") }, Rich)], []));
        Assert.Equal(flash.Objects["x"].Properties["massEnthalpy"], r.Objects["f"].Properties["massEnthalpy"], 50.0);
        Assert.True(r.Objects["l"].Properties.GetValueOrDefault("molarFlow") > 0.1, "the rich gas drops liquid at −10 °C and 40 bara");
    }

    [Fact]
    public async Task OneSideOfAHeatExchangerFindsItsFeedTemperatureByThatSidesEnergyBalance()
    {
        // Side A: 5 kg/s of gas at 400 K. Side B: 5 kg/s of the same gas, its outlet wanted at 330 K with 300 kW exchanged:
        // h(B in) = h(330 K, 30 bara) − Q/ṁ (hand), the exchanger without pressure drops.
        const double q = 300e3, w = 5.0;
        var a = Stream("a", new() { [SpecKeys.Temperature] = new(400, "K"), [SpecKeys.Pressure] = new(30e5, "Pa"), [SpecKeys.MassFlow] = new(w, "kg/s") }, Gas);
        var b = Stream("b", new() { [SpecKeys.Pressure] = new(30e5, "Pa"), [SpecKeys.MassFlow] = new(w, "kg/s") }, Gas);
        var bOut = Stream("bo", new() { [SpecKeys.Temperature] = new(330, "K") });
        var r = await Solve(Case(Gas, [a, b, new() { Id = "e", Tag = "E-100", Type = ObjectType.HeatExchanger, Specs = new() { [SpecKeys.Duty] = new(q, "W") } }, Stream("ao"), bOut],
            [new("a", 0, "e", 0), new("b", 0, "e", 1), new("e", 0, "ao", 0), new("e", 1, "bo", 0)]));
        Assert.Equal(SolveStatus.Converged, r.Status);
        var tB = r.Objects["b"].Properties["temperature"];
        output.WriteLine($"Side B feed {tB - 273.15:F2} °C for 330 K out after 300 kW");
        Assert.Equal(330, r.Objects["bo"].Properties["temperature"], 0.01);
        Assert.Equal(await Enthalpy(330, 30e5) - q / w, await Enthalpy(tB, 30e5), 60.0);
    }

    [Fact]
    public async Task AProductsEnthalpyFindsTheFeedTemperature()
    {
        // The heater of the duty test, its product given by enthalpy instead: the same feed temperature results.
        const double q = 500e3, w = 5.0;
        var hOut = await Enthalpy(353.15, 29.5e5);
        var feed = Stream("f", new() { [SpecKeys.Pressure] = new(30e5, "Pa"), [SpecKeys.MassFlow] = new(w, "kg/s") }, Gas);
        var product = Stream("p", new() { [SpecKeys.MassEnthalpy] = new(hOut, "J/kg") });
        var e = new SimObject { Id = "e", Tag = "Q-100", Type = ObjectType.EnergyStream };
        var r = await Solve(Line(ObjectType.Heater, new() { [SpecKeys.Duty] = new(q, "W"), [SpecKeys.PressureDrop] = new(0.5e5, "Pa") }, feed, product, e));
        Assert.Equal(SolveStatus.Converged, r.Status);
        Assert.Equal(353.15, r.Objects["p"].Properties["temperature"], 0.02);
        Assert.Equal(hOut - q / w, await Enthalpy(r.Objects["f"].Properties["temperature"], 30e5), 50.0);
    }

    [Fact]
    public async Task ASeparatorCantTakeAProductsCompositionOrVapourFraction()
    {
        var feed = Stream("f", new() { [SpecKeys.Temperature] = new(263.15, "K"), [SpecKeys.Pressure] = new(40e5, "Pa"), [SpecKeys.MolarFlow] = new(100, "mol/s") });
        SimObject Sep() => new() { Id = "s", Tag = "V-100", Type = ObjectType.Separator };
        var withX = await Solve(Case(Rich, [feed, Sep(), Stream("g", x: Rich), Stream("l")], [new("f", 0, "s", 0), new("s", 0, "g", 0), new("s", 1, "l", 0)]));
        Assert.Contains(withX.Messages, m => m.ObjectId == "g" && m.Text.Contains("changes the composition"));
        var withVf = await Solve(Case(Rich, [feed with { Composition = Rich, Specs = new() { [SpecKeys.Pressure] = new(40e5, "Pa"), [SpecKeys.MolarFlow] = new(100, "mol/s") } },
            Sep(), Stream("g", new() { [SpecKeys.VapourFraction] = new(1, "") }), Stream("l")], [new("f", 0, "s", 0), new("s", 0, "g", 0), new("s", 1, "l", 0)]));
        Assert.Contains(withVf.Messages, m => m.ObjectId == "g" && m.Text.Contains("one phase"));
    }
}
