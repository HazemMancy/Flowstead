// Flowstead engine tests — relief valve loads and sizing (decisions D54–D56).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class SafetyTests(EngineFixture engine, ITestOutputHelper output)
{
    private static readonly Dictionary<string, double> Lpg = new() { ["Propane"] = 0.7, ["N-butane"] = 0.3 };
    private static readonly Dictionary<string, double> Methane = new() { ["Methane"] = 1.0 };

    private async Task<ReliefResult> Relief(ReliefDevice d, params string[] compounds)
    {
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = compounds.ToList() };
        var res = await engine.Authed().PostAsJsonAsync("/relief", new ReliefRequest(fs, d), Json.Options);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<ReliefResult>(res);
        foreach (var s in r.Scenarios)
            output.WriteLine($"{s.Name}: W {s.MassFlow:0.####} kg/s {s.Phase} P1 {s.RelievingPressure / 1e5:0.###} bar T {s.RelievingTemperature:0.##} K A {s.RequiredArea * 1e6:0.#} mm² ({s.Method}) {s.Error} " +
                string.Join(" ", s.Details.Select(kv => $"{kv.Key}={kv.Value:0.####}")) + " | " + string.Join(" | ", s.Warnings));
        output.WriteLine($"governing {r.Governing}: {r.RequiredArea * 1e6:0.#} mm² → {r.Valves} × {r.Orifice} ({r.OrificeArea * 1e6:0.#} mm²), rated {r.RatedFlow:0.###} kg/s");
        return r;
    }

    [Fact]
    public void StandardConstants()
    {
        // API 520-1: C = 356 (US units) at k = 1.4, i.e. 0.0270 in SI.
        var k = 1.4;
        Assert.Equal(0.0270, 0.03948 * Math.Sqrt(k * Math.Pow(2 / (k + 1), (k + 1) / (k - 1))), 4);
        // Omega method: at ω = 1 the critical ratio is e^(−1/2) (isothermal ideal gas).
        Assert.Equal(Math.Exp(-0.5), ReliefMath.CriticalRatio(1), 6);
        // API 526: 3000 mm² needs a P orifice (N is 2800); 20 000 mm² needs two R valves.
        Assert.Equal(("P", 4116.1e-6, 1), ReliefMath.SelectOrifice(3000e-6));
        var (_, area, n) = ReliefMath.SelectOrifice(20000e-6);
        Assert.Equal(2, n);
        Assert.Equal(10322.6e-6, area, 1e-12);
        // 2 m × 6 m vertical vessel, 1.5 m of liquid, 1 m above grade: full bottom head + 1 m of shell.
        Assert.Equal(1.084 * 4 + Math.PI * 2 * 1.0, ReliefMath.WettedArea(new VesselGeometry(2, 6, false, 1.5, 1)), 9);
    }

    [Fact]
    public async Task VapourSizingIsApi520WithDwsimProperties()
    {
        var d = new ReliefDevice
        {
            Id = "psv", Tag = "PSV-100", SetPressure = 20e5 + 101325, Composition = Methane, OperatingTemperature = 300, OperatingPressure = 18e5,
            Scenarios = [new ReliefScenario { Kind = ReliefScenarioKinds.Custom, Name = "Gas", MassFlow = 5, Temperature = 310 }],
        };
        var r = await Relief(d, "Methane");
        var s = r.Scenarios.Single();
        Assert.Equal("vapour", s.Phase);
        Assert.Equal(20e5 + 101325 + 0.1 * 20e5, s.RelievingPressure, 1e-6);
        var (k, z, m) = (s.Details["k"], s.Details["z"], s.Details["molecularWeight"]);
        var c = 0.03948 * Math.Sqrt(k * Math.Pow(2 / (k + 1), (k + 1) / (k - 1)));
        var expected = 5 * 3600 / (c * 0.975 * s.RelievingPressure / 1000) * Math.Sqrt(310 * z / m) / 1e6;
        Assert.Equal(expected, s.RequiredArea!.Value, expected * 1e-9);
        Assert.InRange(m, 16.0, 16.1);
        // Ideal-gas k of methane near 310 K is about 1.30; the real-gas value at 23 bar is higher.
        Assert.InRange(k, 1.28, 1.32);
        Assert.True(s.Details["kRealGas"] > k);
        Assert.Equal(s.RequiredArea, r.RequiredArea);
    }

    [Fact]
    public async Task LiquidSizingIsApi520()
    {
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        var d = new ReliefDevice
        {
            Id = "psv", Tag = "PSV-101", SetPressure = 10e5 + 101325, Composition = water, OperatingTemperature = 300, OperatingPressure = 8e5,
            Scenarios = [new ReliefScenario { Kind = ReliefScenarioKinds.BlockedOutlet, Name = "Pump deadhead", MassFlow = 10, Temperature = 300 }],
        };
        var s = (await Relief(d, "Water")).Scenarios.Single();
        Assert.Equal("liquid", s.Phase);
        var rho = s.Details["density"];
        var q = 10 / rho * 60000;
        var a0 = 11.78 * q / 0.65 * Math.Sqrt(rho / 999.0 / ((s.RelievingPressure - 101325) / 1000)) / 1e6;
        Assert.Equal(a0 / s.Details["kv"], s.RequiredArea!.Value, a0 * 1e-6);
        Assert.InRange(s.Details["kv"], 0.98, 1.0); // water is not viscous
    }

    [Fact]
    public async Task FireOnALiquidFullVesselBoilsItAtTheRelievingPressure()
    {
        var d = new ReliefDevice
        {
            Id = "psv", Tag = "PSV-102", SetPressure = 17e5 + 101325, Composition = Lpg, OperatingTemperature = 303, OperatingPressure = 8e5,
            Vessel = new VesselGeometry(2, 6, false, 1.5, 1),
            Scenarios = [new ReliefScenario { Kind = ReliefScenarioKinds.Fire, Name = "Fire" }],
        };
        var s = (await Relief(d, "Propane", "N-butane")).Scenarios.Single();
        Assert.Null(s.Error);
        var area = s.Details["wettedArea"];
        Assert.Equal(43200 * Math.Pow(area, 0.82), s.Details["heatInput"], 1e-6);
        Assert.Equal(s.Details["heatInput"] / s.Details["latentHeat"], s.MassFlow!.Value, 1e-9);
        Assert.InRange(s.Details["latentHeat"], 200e3, 400e3); // LPG near 20 bar
        Assert.Equal("vapour", s.Phase);
        Assert.Equal(17e5 * 1.21 + 101325, s.RelievingPressure, 1e-6);
    }

    [Fact]
    public async Task ScenariosGoverningAndThermalAndControlValve()
    {
        var d = new ReliefDevice
        {
            Id = "psv", Tag = "PSV-103", SetPressure = 17e5 + 101325, Composition = Lpg, OperatingTemperature = 303, OperatingPressure = 8e5,
            Vessel = new VesselGeometry(2, 6, false, 1.5, 1),
            Scenarios =
            [
                new ReliefScenario { Kind = ReliefScenarioKinds.Fire, Name = "Fire" },
                new ReliefScenario { Kind = ReliefScenarioKinds.ThermalExpansion, Name = "Thermal", HeatInput = 20e3, Temperature = 303 },
                new ReliefScenario { Kind = ReliefScenarioKinds.ControlValveFailure, Name = "Gas blowby", Composition = Methane, Temperature = 300, UpstreamPressure = 50e5, RatedCv = 20 },
                new ReliefScenario { Kind = ReliefScenarioKinds.Custom, Name = "Broken", Temperature = 300 },
            ],
        };
        var r = await Relief(d, "Propane", "N-butane", "Methane");
        Assert.Equal(4, r.Scenarios.Count);
        Assert.NotNull(r.Scenarios.Single(x => x.Name == "Broken").Error);
        var thermal = r.Scenarios.Single(x => x.Name == "Thermal");
        Assert.Equal("liquid", thermal.Phase);
        Assert.True(thermal.MassFlow < 0.1);
        var blowby = r.Scenarios.Single(x => x.Name == "Gas blowby");
        Assert.Equal("vapour", blowby.Phase);
        Assert.True(blowby.MassFlow > 0);
        Assert.Equal(r.Scenarios.Where(x => x.RequiredArea is not null).Max(x => x.RequiredArea), r.RequiredArea);
        Assert.True(r.OrificeArea * r.Valves >= r.RequiredArea);
    }

    [Fact]
    public async Task TwoPhaseUsesTheOmegaMethod()
    {
        // A wide-boiling light hydrocarbon mix, part vapour at the relieving pressure.
        var mix = new Dictionary<string, double> { ["Methane"] = 0.3, ["Propane"] = 0.4, ["N-butane"] = 0.3 };
        var d = new ReliefDevice
        {
            Id = "psv", Tag = "PSV-104", SetPressure = 17e5 + 101325, Composition = mix, OperatingTemperature = 303, OperatingPressure = 8e5,
            Scenarios = [new ReliefScenario { Kind = ReliefScenarioKinds.Custom, Name = "Two-phase", MassFlow = 5, Temperature = 320 }],
        };
        var s = (await Relief(d, "Methane", "Propane", "N-butane")).Scenarios.Single();
        Assert.Equal("twoPhase", s.Phase);
        Assert.True(s.Details["omega"] > 0);
        Assert.Equal(ReliefMath.CriticalRatio(s.Details["omega"]), s.Details["criticalPressureRatio"], 9);
        Assert.Equal(5 / (0.85 * s.Details["massFlux"]), s.RequiredArea!.Value, 1e-12);
    }

    [Fact]
    public async Task InletLossAndBuiltUpBackPressureAreCheckedAtTheRatedFlow()
    {
        ReliefDevice Device(double inletId, double outletId) => new()
        {
            Id = "psv", Tag = "PSV-105", SetPressure = 20e5 + 101325, Composition = Methane, OperatingTemperature = 300, OperatingPressure = 18e5,
            Scenarios = [new ReliefScenario { Kind = ReliefScenarioKinds.Custom, Name = "Gas", MassFlow = 5, Temperature = 310 }],
            InletLine = new ReliefLine(inletId, 2, FittingsLength: 3),
            OutletLine = new ReliefLine(outletId, 30, FittingsLength: 10),
        };
        // 6" inlet and 12" outlet (schedule 40): fine.
        var good = await Relief(Device(0.1541, 0.3033), "Methane");
        output.WriteLine($"inlet {good.Inlet} outlet {good.Outlet}");
        Assert.True(good.Inlet!.Ok);
        Assert.True(good.Outlet!.Ok);
        Assert.True(good.Inlet.PressureDrop > 0 && good.Outlet.PressureDrop > 0);
        // 2" inlet and 3" outlet: the inlet loses more than 3 % and the outlet builds up too much back pressure.
        var bad = await Relief(Device(0.0525, 0.0779), "Methane");
        output.WriteLine($"inlet {bad.Inlet} outlet {bad.Outlet}");
        Assert.False(bad.Inlet!.Ok);
        Assert.False(bad.Outlet!.Ok);
        Assert.True(bad.Inlet.PressureDrop > good.Inlet.PressureDrop);
    }
}
