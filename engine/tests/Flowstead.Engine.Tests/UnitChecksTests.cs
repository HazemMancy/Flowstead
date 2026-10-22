// Flowstead engine tests — HYSYS-style checks on unit operations (decision D46).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using Flowstead.Engine.Api;
using Flowstead.Engine.Contracts;
using Xunit;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class UnitChecksTests(EngineFixture engine)
{
    private static readonly Dictionary<string, double> Gas = new()
    {
        ["Methane"] = 0.8, ["Ethane"] = 0.1, ["Propane"] = 0.05, ["N-butane"] = 0.03, ["N-hexane"] = 0.02,
    };

    private static SimObject Feed(string id, Dictionary<string, double> x, double t, double p, double kmolh = 36) => new()
    {
        Id = id, Tag = id.ToUpperInvariant(), Type = ObjectType.MaterialStream,
        Specs = new()
        {
            [SpecKeys.Temperature] = new Spec(t, "K"),
            [SpecKeys.Pressure] = new Spec(p, "Pa"),
            [SpecKeys.MolarFlow] = new Spec(kmolh / 3.6, "kgmol/h"),
        },
        Composition = x,
    };

    private static SimObject Product(string id) => new() { Id = id, Tag = id.ToUpperInvariant(), Type = ObjectType.MaterialStream };

    /// <summary>feed → unit → product.</summary>
    private async Task<SolveResult> Through(SimObject feed, ObjectType type, Dictionary<string, double> specs, string package = "Peng-Robinson (PR)")
    {
        var unit = new SimObject { Id = "u", Tag = "U-100", Type = type, Specs = specs.ToDictionary(kv => kv.Key, kv => new Spec(kv.Value, "")) };
        var fs = new Flowsheet
        {
            PropertyPackage = package,
            Compounds = feed.Composition.Keys.ToList(),
            Objects = [feed, unit, Product("o")],
            Connections = [new Connection(feed.Id, 0, "u", 0), new Connection("u", 0, "o", 0)],
        };
        return await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
    }

    private static SolverMessage On(SolveResult r, string id, MessageLevel level) =>
        Assert.Single(r.Messages, m => m.ObjectId == id && m.Level == level);

    [Fact]
    public async Task CompressorWithLiquidInItsFeedWarns()
    {
        // Rich gas at −40 °C and 20 bar is 12 % liquid (molar).
        var r = await Through(Feed("f", Gas, 233.15, 20e5), ObjectType.Compressor,
            new() { [SpecKeys.OutletPressure] = 60e5, [SpecKeys.Efficiency] = 0.75 });
        Assert.Equal(SolveStatus.Converged, r.Status);
        Assert.Contains(r.Messages, m => m.ObjectId == "u" && m.Level == MessageLevel.Warning && m.Text.Contains("liquid in the feed"));
        Assert.Equal(ObjectStatus.Solved, r.Objects["u"].Status);
    }

    [Fact]
    public async Task CompressorThatLowersPressureIsAnErrorAndSoIsEverythingDownstream()
    {
        var r = await Through(Feed("f", Gas, 300, 60e5), ObjectType.Compressor,
            new() { [SpecKeys.OutletPressure] = 20e5, [SpecKeys.Efficiency] = 0.75 });
        Assert.Equal(SolveStatus.Failed, r.Status);
        Assert.Equal(ObjectStatus.Error, r.Objects["u"].Status);
        Assert.Contains("not above the inlet", r.Objects["u"].Error);
        Assert.Equal(ObjectStatus.Error, r.Objects["o"].Status);
        Assert.Empty(r.Objects["o"].Properties); // no numbers from an impossible unit
    }

    [Fact]
    public async Task PumpFedWithGasIsAnError()
    {
        var r = await Through(Feed("f", Gas, 300, 20e5), ObjectType.Pump,
            new() { [SpecKeys.OutletPressure] = 60e5, [SpecKeys.Efficiency] = 0.75 });
        Assert.Equal(ObjectStatus.Error, r.Objects["u"].Status);
        Assert.Contains("a pump needs liquid", r.Objects["u"].Error);
    }

    [Fact]
    public async Task PumpReportsNpshAvailableAndWarnsWhenItIsLow()
    {
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        // Subcooled water, 26.85 °C at 10 bar: NPSHa = (P − Psat) / (ρ g) = (1e6 − 3537) / (996.5 × 9.80665) ≈ 102 m.
        var r = await Through(Feed("f", water, 300, 10e5), ObjectType.Pump,
            new() { [SpecKeys.OutletPressure] = 20e5, [SpecKeys.Efficiency] = 0.75 }, "Steam Tables (IAPWS-IF97)");
        Assert.Equal(SolveStatus.Converged, r.Status);
        Assert.Equal(102.0, r.Objects["u"].Properties["npshAvailable"], 102.0 * 0.02);
        Assert.DoesNotContain(r.Messages, m => m.ObjectId == "u");

        // Saturated water: nothing to spare.
        var sat = Feed("f", water, 393.36, 2e5);
        sat.Specs.Remove(SpecKeys.Temperature);
        sat.Specs[SpecKeys.VapourFraction] = new Spec(0.0, "");
        r = await Through(sat, ObjectType.Pump, new() { [SpecKeys.OutletPressure] = 10e5, [SpecKeys.Efficiency] = 0.75 }, "Steam Tables (IAPWS-IF97)");
        Assert.InRange(r.Objects["u"].Properties["npshAvailable"], -0.5, 0.5);
        Assert.Contains("NPSH available", On(r, "u", MessageLevel.Warning).Text);
    }

    [Fact]
    public async Task ValveThatRaisesPressureIsAnError()
    {
        var r = await Through(Feed("f", Gas, 300, 20e5), ObjectType.Valve, new() { [SpecKeys.OutletPressure] = 60e5 });
        Assert.Equal(ObjectStatus.Error, r.Objects["u"].Status);
        Assert.Contains("a valve can only lower pressure", r.Objects["u"].Error);
    }

    [Fact]
    public async Task ExpanderThatRaisesPressureIsAnError()
    {
        var r = await Through(Feed("f", Gas, 300, 20e5), ObjectType.Expander,
            new() { [SpecKeys.OutletPressure] = 60e5, [SpecKeys.Efficiency] = 0.8 });
        Assert.Equal(ObjectStatus.Error, r.Objects["u"].Status);
    }

    [Fact]
    public async Task HeaterThatCoolsWarns()
    {
        var r = await Through(Feed("f", Gas, 350, 20e5), ObjectType.Heater, new() { [SpecKeys.OutletTemperature] = 300 });
        Assert.Equal(SolveStatus.Converged, r.Status);
        Assert.Contains("removes heat", On(r, "u", MessageLevel.Warning).Text);
    }

    [Fact]
    public async Task HeatExchangerTemperatureCrossIsExplained()
    {
        var gas = new Dictionary<string, double>(Gas) { ["Water"] = 0.0 };
        var water = Gas.Keys.ToDictionary(k => k, _ => 0.0);
        water["Water"] = 1.0;
        var fs = new Flowsheet
        {
            PropertyPackage = "Peng-Robinson (PR)",
            Compounds = gas.Keys.ToList(),
            Objects =
            [
                Feed("a", gas, 400, 20e5), Feed("b", water, 300, 5e5, 72),
                new SimObject { Id = "e", Tag = "E-100", Type = ObjectType.HeatExchanger, Specs = new() { [SpecKeys.HotOutletTemperature] = new Spec(290, "K") } },
                Product("ao"), Product("bo"),
            ],
            Connections = [new("a", 0, "e", 0), new("b", 0, "e", 1), new("e", 0, "ao", 0), new("e", 1, "bo", 0)],
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.Equal(ObjectStatus.Error, r.Objects["e"].Status);
        Assert.StartsWith("Temperature cross", r.Objects["e"].Error);
        Assert.DoesNotContain(r.Messages, m => m.ObjectId is null && m.Level == MessageLevel.Error);

        // A feasible duty reports its end approach.
        fs.Objects[2].Specs[SpecKeys.HotOutletTemperature] = new Spec(330, "K");
        r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.InRange(r.Objects["e"].Properties["minimumApproach"], 5, 80);
    }

    [Fact]
    public async Task MixerInletsAtDifferentPressuresWarn()
    {
        var fs = new Flowsheet
        {
            PropertyPackage = "Peng-Robinson (PR)",
            Compounds = Gas.Keys.ToList(),
            Objects = [Feed("a", Gas, 300, 20e5), Feed("b", Gas, 300, 60e5), new SimObject { Id = "m", Tag = "MIX-100", Type = ObjectType.Mixer }, Product("o")],
            Connections = [new("a", 0, "m", 0), new("b", 0, "m", 1), new("m", 0, "o", 0)],
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.Equal(SolveStatus.Converged, r.Status);
        Assert.Contains("different pressures", On(r, "m", MessageLevel.Warning).Text);
    }

    [Fact]
    public async Task ChokedPipeIsExplained()
    {
        var pipe = new Dictionary<string, double> { [SpecKeys.Length] = 1000, [SpecKeys.InnerDiameter] = 0.05 };
        var r = await Through(Feed("f", Gas, 300, 20e5, 2000), ObjectType.PipeSegment, pipe);
        Assert.Equal(ObjectStatus.Error, r.Objects["u"].Status);
        Assert.Contains("choked", r.Objects["u"].Error);
    }

    [Fact]
    public async Task ValveIsSizedByIec60534()
    {
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        // 100 m³/h of water at 25 °C across 1 bar: Kv = Q √(SG/ΔP) = 100 √(0.997/1) ≈ 99.9, Cv = 1.156 Kv ≈ 115.4.
        var feed = Feed("f", water, 298.15, 5e5, 100 * 997.0 / 18.015);
        var r = await Through(feed, ObjectType.Valve, new() { [SpecKeys.PressureDrop] = 1e5 }, "Steam Tables (IAPWS-IF97)");
        var v = r.Objects["u"].Properties;
        Assert.Equal(115.4, v["requiredCv"], 115.4 * 0.01);
        Assert.Equal(99.9, v["requiredKv"], 99.9 * 0.01);
        Assert.DoesNotContain(r.Messages, m => m.ObjectId == "u");

        // An installed Cv 150 valve runs about 77 % open; a Cv 100 valve cannot pass the flow.
        r = await Through(feed, ObjectType.Valve, new() { [SpecKeys.PressureDrop] = 1e5, [SpecKeys.RatedCv] = 150 }, "Steam Tables (IAPWS-IF97)");
        Assert.Equal(0.77, r.Objects["u"].Properties["valveOpening"], 0.01);
        r = await Through(feed, ObjectType.Valve, new() { [SpecKeys.PressureDrop] = 1e5, [SpecKeys.RatedCv] = 100 }, "Steam Tables (IAPWS-IF97)");
        Assert.Equal(ObjectStatus.Error, r.Objects["u"].Status);
        Assert.Contains("too small", r.Objects["u"].Error);
    }

    [Fact]
    public async Task HotWaterLetDownCavitates()
    {
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        // 80 °C water (Pv 0.47 bar) from 3 to 0.6 bar: ΔP 2.4 bar beyond FL²(P1 − FF Pv) ≈ 2.07 bar.
        var r = await Through(Feed("f", water, 353.15, 3e5, 360), ObjectType.Valve, new() { [SpecKeys.OutletPressure] = 0.6e5 }, "Steam Tables (IAPWS-IF97)");
        Assert.Equal(2.07e5, r.Objects["u"].Properties["chokedPressureDrop"], 0.02e5);
        Assert.Contains("cavitating", On(r, "u", MessageLevel.Warning).Text);
    }

    [Fact]
    public async Task GasValveChokesBeyondTheCriticalPressureRatio()
    {
        var methane = new Dictionary<string, double> { ["Methane"] = 1.0 };
        var ok = await Through(Feed("f", methane, 300, 20e5, 1000), ObjectType.Valve, new() { [SpecKeys.OutletPressure] = 15e5 });
        // Independently from IEC's standard-volume form (Q in scfh, Gg, T in °R, Z): 81.5 for 1000 kmol/h.
        Assert.Equal(81.5, ok.Objects["u"].Properties["requiredCv"], 81.5 * 0.01);
        var choked = await Through(Feed("f", methane, 300, 20e5, 1000), ObjectType.Valve, new() { [SpecKeys.OutletPressure] = 5e5 });
        Assert.Contains("choked", On(choked, "u", MessageLevel.Warning).Text);
    }
}
