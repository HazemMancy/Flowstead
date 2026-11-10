// Flowstead engine tests — relief valve loads and sizing (decisions D54–D56).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Text.Json;
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
        output.WriteLine($"governing {r.Governing}: {r.RequiredArea * 1e6:0.#} mm² (Ø {r.RequiredDiameter * 1000:0.#} mm) → {r.Valves} × {r.Orifice} ({r.OrificeArea * 1e6:0.#} mm², Ø {r.OrificeDiameter * 1000:0.#} mm), valve {r.ValveSize}, rated {r.RatedFlow:0.###} kg/s");
        if (r.Orifice is { } letter)
        {
            // Every sized valve reports its letter, its valve size and both equivalent diameters.
            Assert.Equal(ReliefMath.ValveSizes[letter], r.ValveSize);
            Assert.Equal(ReliefMath.EquivalentDiameter(r.OrificeArea!.Value), r.OrificeDiameter!.Value, 9);
            Assert.True(r.RequiredDiameter <= r.OrificeDiameter);
        }
        return r;
    }

    [Fact]
    public void VolumeBelowALevelCountsTheHeadsByTheirShape()
    {
        // Standing vessel, D 2 m, 6 m shell: a 2:1 head is 0.5 m deep and holds π D³/24.
        var g = new VesselGeometry(2, 6);
        double head = Math.PI * 8 / 24, a = 0.5, r = 1;
        // Bottom head filled to y from its pole, by integrating its cross-section π R² (2y/a − y²/a²): π R² (y²/a − y³/(3a²)).
        foreach (var y in new[] { 0.1, 0.25, 0.4, 0.5 })
            Assert.Equal(Math.PI * r * r * (y * y / a - y * y * y / (3 * a * a)), ReliefMath.VolumeBelow(g, y), 12);
        Assert.Equal(0.3125 * head, ReliefMath.VolumeBelow(g, 0.25), 12);
        // Into the shell, and to the top: the whole vessel.
        Assert.Equal(head + Math.PI * 2.0, ReliefMath.VolumeBelow(g, 2.5), 12);
        Assert.Equal(ReliefMath.Volume(g), ReliefMath.VolumeBelow(g, 7), 12);
        // The top head mirrors the bottom one.
        Assert.Equal(ReliefMath.Volume(g) - ReliefMath.VolumeBelow(g, 0.2), ReliefMath.VolumeBelow(g, 7 - 0.2), 12);
        // Lying: half full is half the vessel; full is all of it.
        var h = new VesselGeometry(2, 6, Horizontal: true);
        Assert.Equal(ReliefMath.Volume(h) / 2, ReliefMath.VolumeBelow(h, 1), 12);
        Assert.Equal(ReliefMath.Volume(h), ReliefMath.VolumeBelow(h, 2), 12);
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
        var (letter, area, n) = ReliefMath.SelectOrifice(20000e-6);
        Assert.Equal(("R", 2), (letter, n));
        Assert.Equal(10322.6e-6, area, 1e-12);
        // Diameters: a K orifice's 1185.8 mm² is a 38.9 mm (1.53 in) circle; its smallest API 526 valve is 3K4.
        Assert.Equal(0.03886, ReliefMath.EquivalentDiameter(1185.8e-6), 5);
        Assert.Equal("3K4", ReliefMath.ValveSizes["K"]);
        Assert.Equal(14, ReliefMath.ValveSizes.Count);
        // A pipe has no heads: a 12" line 5 km long holds π/4 × 0.3² × 5000 m³ behind π × 0.3 × 5000 m² of wall.
        var line = new VesselGeometry(0.3, 5000, Pipe: true);
        Assert.Equal(Math.PI / 4 * 0.09 * 5000, ReliefMath.Volume(line), 1e-9);
        Assert.Equal(Math.PI * 0.3 * 5000, ReliefMath.Surface(line), 1e-9);
        Assert.Equal(Math.PI * 0.3 * 5000 / 2, ReliefMath.WettedArea(line with { LiquidLevel = 0.15 }), 1e-9);
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
    public async Task WetSourGasGetsItsIdealGasKWithoutAWarning()
    {
        // Saturated natural gas with CO₂ and H₂S: k comes from DWSIM's ideal-gas Cp of each component, never the fallback.
        var gas = new Dictionary<string, double> { ["Methane"] = 0.888, ["Ethane"] = 0.05, ["Carbon dioxide"] = 0.04, ["Hydrogen sulfide"] = 0.02, ["Water"] = 0.002 };
        var d = new ReliefDevice
        {
            Id = "psv", Tag = "PSV-200", SetPressure = 70e5, Composition = gas, OperatingTemperature = 320, OperatingPressure = 60e5,
            Scenarios = [new ReliefScenario { Kind = ReliefScenarioKinds.Custom, Name = "Gas", MassFlow = 10, Temperature = 330 }],
        };
        var s = (await Relief(d, gas.Keys.ToArray())).Scenarios.Single();
        Assert.DoesNotContain(s.Warnings, w => w.Contains("real-gas"));
        Assert.InRange(s.Details["k"], 1.24, 1.32);
        Assert.True(s.Details["kRealGas"] > s.Details["k"]);
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

    [Theory]
    [InlineData(0.0068)] // saturated at 37 °C: all vapour at 40 °C, the condensate is the dew-point liquid
    [InlineData(0.02)]   // with free water
    public async Task FireOnAWetAirReceiverBoilsItsWater(double water)
    {
        // The owner's instrument air receiver (HYSYS gives 1D2): 1.29 m × 1.84 m T/T vertical, 0.3 m of water, set 10 barg.
        var air = new Dictionary<string, double> { ["Nitrogen"] = 0.79 * (1 - water), ["Oxygen"] = 0.21 * (1 - water), ["Water"] = water };
        var d = new ReliefDevice
        {
            Id = "psv", Tag = "PSV-100", SetPressure = 10e5 + 101325, Composition = air, OperatingTemperature = 313.15, OperatingPressure = 9e5 + 101325,
            Vessel = new VesselGeometry(1.29, 1.84, false, 0.3, 0),
            Scenarios = [new ReliefScenario { Kind = ReliefScenarioKinds.Fire, Name = "Fire" }],
        };
        var r = await Relief(d, "Nitrogen", "Oxygen", "Water");
        var s = r.Scenarios.Single();
        Assert.Null(s.Error);
        Assert.Equal(ReliefMath.WettedArea(d.Vessel), s.Details["wettedArea"], 9);
        // Water's latent heat at 13.1 bara is 1958 kJ/kg (IAPWS-IF97); a trace of dissolved air must not inflate it.
        Assert.InRange(s.Details["latentHeat"], 1.85e6, 2.15e6);
        Assert.InRange(s.RelievingTemperature!.Value, 460, 470); // water boils near 192 °C
        Assert.Equal(water < 0.007, s.Details.ContainsKey("dewPoint"));
        Assert.Equal("D", r.Orifice);
        Assert.Equal("1D2", r.ValveSize);
    }

    [Fact]
    public async Task InstrumentAirExampleSizesItsReceiverPsvAsHysys()
    {
        // Example 21 as the app sizes it: the receiver's contents are its solved feed (S-1), the valve and scenarios
        // the example's PSV-100. HYSYS gives 1D2 for this receiver (owner's report, D102).
        var dir = Path.Combine(Path.GetFullPath(EngineFixture.Meta("BenchmarksDir")), "21-instrument-air-receiver", "input.json");
        var input = System.Text.Json.Nodes.JsonNode.Parse(await File.ReadAllTextAsync(dir))!["flowsheet"]!;
        var fs = input.Deserialize<Flowsheet>(Json.Options)!;
        var solved = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.True(solved.Status == SolveStatus.Converged, string.Join(" / ", solved.Messages.Select(m => $"{m.ObjectId}: {m.Text}")));
        var feed = solved.Objects["wet"];
        Assert.True(feed.Phases!.ContainsKey("liquid1") || feed.Phases.ContainsKey("liquid2"), "the saturator is meant to leave condensate");
        var psv = input["safety"]!["devices"]![0]!;
        var d = new ReliefDevice
        {
            Id = "psv100", Tag = "PSV-100", SetPressure = psv["setPressure"]!.GetValue<double>(),
            Composition = feed.Phases["overall"].Composition.Where(c => c.Value > 0).ToDictionary(),
            OperatingTemperature = feed.Properties["temperature"], OperatingPressure = feed.Properties["pressure"],
            Vessel = psv["vessel"].Deserialize<VesselGeometry>(Json.Options),
            InletLine = psv["inletLine"].Deserialize<ReliefLine>(Json.Options), OutletLine = psv["outletLine"].Deserialize<ReliefLine>(Json.Options),
            Scenarios =
            [
                new ReliefScenario { Kind = ReliefScenarioKinds.Fire, Name = "Fire" },
                new ReliefScenario { Kind = ReliefScenarioKinds.BlockedOutlet, Name = "Blocked outlet", MassFlow = feed.Properties["massFlow"], Temperature = feed.Properties["temperature"] },
            ],
        };
        var r = await Relief(d, fs.Compounds.ToArray());
        var fire = r.Scenarios.Single(x => x.Name == "Fire");
        Assert.Null(fire.Error);
        Assert.InRange(fire.Details["latentHeat"], 1.85e6, 2.15e6); // water at 13.1 bara: 1958 kJ/kg (IAPWS-IF97)
        Assert.Equal("D", ReliefMath.SelectOrifice(fire.RequiredArea!.Value).Letter);
        Assert.All(r.Scenarios, x => Assert.Null(x.Error));
    }

    [Fact]
    public async Task FireOnADryGasVesselIgnoresAStrayLiquidLevel()
    {
        var d = new ReliefDevice
        {
            Id = "psv", Tag = "PSV-100", SetPressure = 10e5 + 101325, Composition = new() { ["Nitrogen"] = 0.79, ["Oxygen"] = 0.21 },
            OperatingTemperature = 313.15, OperatingPressure = 9e5 + 101325, Vessel = new VesselGeometry(1.29, 1.84, false, 0.3, 0),
            Scenarios = [new ReliefScenario { Kind = ReliefScenarioKinds.Fire, Name = "Fire" }],
        };
        var s = (await Relief(d, "Nitrogen", "Oxygen")).Scenarios.Single();
        Assert.Null(s.Error);
        Assert.Contains(s.Warnings, w => w.Contains("dry gas"));
        // The whole shell and both heads are exposed (2.5 m tall, under 7.6 m).
        Assert.Equal(Math.PI * 1.29 * 1.84 + 2 * 1.084 * 1.29 * 1.29, s.Details["exposedArea"], 9);
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

    private async Task<DepressuringResult> Blowdown(double? orifice, Dictionary<string, double> z, double p0, double t0, params string[] compounds) =>
        await Blowdown(orifice, z, p0, t0, null, 0, compounds);

    private async Task<DepressuringResult> Blowdown(double? orifice, Dictionary<string, double> z, double p0, double t0, double? h, double liquidLevel, params string[] compounds)
    {
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = compounds.ToList() };
        var req = new DepressuringRequest
        {
            Flowsheet = fs, Composition = z, InitialPressure = p0, InitialTemperature = t0,
            Vessel = new VesselGeometry(1.5, 4, LiquidLevel: liquidLevel), OrificeDiameter = orifice, HeatTransferCoefficient = h,
        };
        var res = await engine.Authed().PostAsJsonAsync("/depressuring", req, Json.Options);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<DepressuringResult>(res);
        output.WriteLine($"orifice {r.OrificeDiameter * 1000:0.#} mm (sized {r.OrificeSized}), target {r.TargetPressure / 1e5:0.##} bar at {r.TimeToTarget:0} s, Tmin {r.MinimumTemperature - 273.15:0.#} °C liquid {r.MinimumLiquidTemperature - 273.15:0.#} °C wall {r.MinimumWallTemperature - 273.15:0.#} °C ({r.WallThickness * 1000:0.#} mm), peak {r.PeakFlow:0.###} kg/s, {r.Time.Count} steps | " + string.Join(" | ", r.Warnings));
        for (var i = 0; i < r.Time.Count; i += Math.Max(1, r.Time.Count / 8))
            output.WriteLine($"  t {r.Time[i]:0} s  P {r.Pressure[i] / 1e5:0.##} bar  T {r.Temperature[i] - 273.15:0.#} °C  TL {r.LiquidTemperature[i] - 273.15:0.#} °C  Tw {r.WallTemperature[i] - 273.15:0.#}/{r.WettedWallTemperature[i] - 273.15:0.#} °C  LF {r.LiquidVolumeFraction[i]:0.###}  W {r.MassFlow[i]:0.###}  M {r.Inventory[i]:0.#} kg");
        return r;
    }

    [Fact]
    public async Task GasBlowdownConservesMassAndCools()
    {
        var r = await Blowdown(0.02, Methane, 70e5, 300, "Methane");
        Assert.True(r.Pressure.Zip(r.Pressure.Skip(1)).All(p => p.Second < p.First));
        Assert.True(r.MinimumTemperature < 300 - 30);
        // The wall lags the gas: its inner surface stays warmer than the gas, but cools.
        Assert.True(r.MinimumWallTemperature > r.MinimumTemperature);
        Assert.True(r.MinimumWallTemperature < 300);
        // Adiabatic: the gas follows the isentrope, colder than with the wall's heat. Ideal gas from 70 to ~1 bar:
        // T ≈ 300 (1/70)^((k−1)/k) ≈ 113 K.
        var adiabatic = await Blowdown(0.02, Methane, 70e5, 300, 0, 0, "Methane");
        Assert.True(adiabatic.MinimumTemperature < r.MinimumTemperature);
        Assert.InRange(adiabatic.MinimumTemperature, 95, 135);
        // Mass balance: the outflow integrated over time (trapezoids) equals the inventory lost.
        double released = 0;
        for (var i = 1; i < r.Time.Count; i++) released += 0.5 * (r.MassFlow[i] + r.MassFlow[i - 1]) * (r.Time[i] - r.Time[i - 1]);
        var lost = r.Inventory[0] - r.Inventory[^1];
        Assert.Equal(lost, released, lost * 0.03);
        // API 521 target: the lower of 6.9 barg and half of the starting gauge pressure (no design pressure given).
        Assert.Equal(101325 + 690e3, r.TargetPressure, 1e-6);
        Assert.Equal("6.9 barg", r.TargetBasis);
        Assert.Contains(r.Warnings, w => w.Contains("No design pressure given"));
    }

    [Fact]
    public async Task TheBlowdownTargetAndWallFollowTheDesignPressure()
    {
        // API 521 §5.20.1: the lower of 690 kPa gauge and 50 % of the design gauge pressure. ASME VIII-1 UG-27(c)(1):
        // t = P·R/(S·E − 0.6·P) at the design pressure, S 138 MPa, E 1, R 0.75 m (the 1.5 m vessel), 6 mm at least.
        async Task<DepressuringResult> Run(double designBarg, double startBarg)
        {
            var req = new DepressuringRequest
            {
                Flowsheet = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Methane"] }, Composition = Methane,
                InitialPressure = 101325 + startBarg * 1e5, InitialTemperature = 300, Vessel = new VesselGeometry(1.5, 4),
                OrificeDiameter = 0.02, DesignPressure = 101325 + designBarg * 1e5, Duration = 30,
            };
            var res = await engine.Authed().PostAsJsonAsync("/depressuring", req, Json.Options);
            Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
            var r = await EngineFixture.Json<DepressuringResult>(res);
            output.WriteLine($"design {designBarg} barg: target {(r.TargetPressure - 101325) / 1e5:0.###} barg ({r.TargetBasis}), wall {r.WallThickness * 1000:0.###} mm | " + string.Join(" | ", r.Warnings));
            return r;
        }
        // Design 12 barg: half is 6 barg, below 6.9. Wall 12e5·0.75/(138e6 − 0.72e6) = 6.556 mm. Starting at 70 barg is above it.
        var low = await Run(12, 70);
        Assert.Equal(101325 + 6e5, low.TargetPressure, 1e-6);
        Assert.Equal("half the design pressure", low.TargetBasis);
        Assert.Equal(12e5 * 0.75 / (138e6 - 0.6 * 12e5), low.WallThickness, 1e-9);
        Assert.Contains(low.Warnings, w => w.Contains("above its design pressure"));
        // Design 100 barg, starting at 50: 6.9 barg, though half the start (25 barg) would also give it. Wall 56.818 mm.
        var high = await Run(100, 50);
        Assert.Equal(101325 + 690e3, high.TargetPressure, 1e-6);
        Assert.Equal("6.9 barg", high.TargetBasis);
        Assert.Equal(100e5 * 0.75 / (138e6 - 0.6 * 100e5), high.WallThickness, 1e-9);
        Assert.DoesNotContain(high.Warnings, w => w.Contains("design pressure"));
    }

    [Fact]
    public async Task OrificeIsSizedToReachTheTargetIn15Minutes()
    {
        var r = await Blowdown(null, Methane, 70e5, 300, "Methane");
        Assert.True(r.OrificeSized);
        Assert.InRange(r.TimeToTarget!.Value, 0.9 * 900, 900);
    }

    [Fact]
    public async Task PressureEntropyFlashReturnsTheStateItCameFrom()
    {
        var mix = new Dictionary<string, double> { ["Methane"] = 0.3, ["Propane"] = 0.4, ["N-butane"] = 0.3 };
        SimObject Stream(string id, Dictionary<string, Spec> specs) => new() { Id = id, Tag = id, Type = ObjectType.MaterialStream, Composition = mix, Specs = specs };
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = mix.Keys.ToList() };
        var a = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs with
        {
            Objects = [Stream("a", new() { [SpecKeys.Temperature] = new(320, "K"), [SpecKeys.Pressure] = new(19e5, "Pa"), [SpecKeys.MassFlow] = new(1, "kg/s") })],
        }, Json.Options));
        var s = a.Objects["a"].Phases!["overall"].Properties["massEntropy"];
        var b = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs with
        {
            Objects = [Stream("b", new() { [SpecKeys.MassEntropy] = new(s, "J/kg.K"), [SpecKeys.Pressure] = new(19e5, "Pa"), [SpecKeys.MassFlow] = new(1, "kg/s") })],
        }, Json.Options));
        Assert.Equal(SolveStatus.Converged, b.Status);
        Assert.Equal(320, b.Objects["b"].Properties["temperature"], 0.05);
    }

    [Fact]
    public async Task TwoPhaseBlowdownKeepsTheLiquidColderThanTheGasHeatedWall()
    {
        // A vessel half full of NGL: the liquid boils as the pressure falls and chills the wetted wall more than the gas does the dry wall.
        var ngl = new Dictionary<string, double> { ["Methane"] = 0.3, ["Ethane"] = 0.15, ["Propane"] = 0.3, ["N-butane"] = 0.25 };
        var r = await Blowdown(0.02, ngl, 30e5, 300, null, 0.75, "Methane", "Ethane", "Propane", "N-butane");
        Assert.NotNull(r.MinimumLiquidTemperature);
        // 0.75 m in a vertical 1.5 m × 4 m vessel: the bottom head and 0.375 m of shell, 14 % of the volume.
        Assert.Equal(0.139, r.LiquidVolumeFraction[0], 0.005);
        // Mass balance with two zones.
        double released = 0;
        for (var i = 1; i < r.Time.Count; i++) released += 0.5 * (r.MassFlow[i] + r.MassFlow[i - 1]) * (r.Time[i] - r.Time[i - 1]);
        var lost = r.Inventory[0] - r.Inventory[^1];
        Assert.Equal(lost, released, lost * 0.05);
        // Boiling cools the liquid; the wetted wall follows it more closely than the dry wall follows the gas.
        Assert.True(r.MinimumLiquidTemperature < 300 - 20);
        var wetMin = r.WettedWallTemperature.Where(v => v is not null).Min()!.Value;
        Assert.True(wetMin < r.WallTemperature.Min());
    }

    [Fact]
    public async Task SeparatorWithWaterBlowsDownToTheEndAndIsSized()
    {
        // A field separator: gas, NGL and free water, horizontal, 30 % full. DWSIM's three-phase flash jumps near the
        // water's phase boundary; the blowdown must ride over it (it used to stop after a few seconds and the orifice
        // sizing then grew the orifice without end), and the wall must never end colder than the fluid it touches.
        var z = new Dictionary<string, double> { ["Methane"] = 0.70, ["Ethane"] = 0.08, ["Propane"] = 0.05, ["N-butane"] = 0.04, ["N-pentane"] = 0.03, ["N-hexane"] = 0.03, ["Water"] = 0.07 };
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = z.Keys.ToList() };
        var req = new DepressuringRequest { Flowsheet = fs, Composition = z, InitialPressure = 50e5, InitialTemperature = 303.15, Vessel = new VesselGeometry(2, 6, Horizontal: true, LiquidLevel: 0.6) };
        var res = await engine.Authed(15).PostAsJsonAsync("/depressuring", req, Json.Options);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<DepressuringResult>(res);
        output.WriteLine($"orifice {r.OrificeDiameter * 1000:0.#} mm, target at {r.TimeToTarget:0} s, Tmin {r.MinimumTemperature - 273.15:0.#} °C, liquid {r.MinimumLiquidTemperature - 273.15:0.#} °C, wall {r.MinimumWallTemperature - 273.15:0.#} °C, {r.Time.Count} steps; " + string.Join(" | ", r.Warnings));
        Assert.True(r.OrificeSized);
        Assert.InRange(r.TimeToTarget!.Value, 0.9 * 900, 900);
        Assert.DoesNotContain(r.Warnings, w => w.Contains("did not settle"));
        Assert.True(r.MinimumWallTemperature >= Math.Min(r.MinimumTemperature, r.MinimumLiquidTemperature ?? double.MaxValue) - 0.5);
    }

    // ------------------------------------------------------------------ pressurization from a fixed volume (D149)

    private async Task<DepressuringResult> Equalise(double? hTarget, double? hSource, double? target = null)
    {
        // Two equal vessels (1 m × 3 m, 2:1 heads) of nitrogen at 300 K: the source at 10 bara, the other at 1 atm; a 10 mm orifice.
        var n2 = new Dictionary<string, double> { ["Nitrogen"] = 1.0 };
        var req = new DepressuringRequest
        {
            Flowsheet = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Nitrogen"] }, Composition = n2,
            InitialPressure = 101325, InitialTemperature = 300, Vessel = new VesselGeometry(1.0, 3.0), HeatTransferCoefficient = hTarget,
            TargetPressure = target, Duration = 4 * 3600,
            Supply = new PressurizationSupply
            {
                Composition = n2, Pressure = 10e5, Temperature = 300, OrificeDiameter = 0.01,
                Vessel = new VesselGeometry(1.0, 3.0), HeatTransferCoefficient = hSource,
            },
        };
        var res = await engine.Authed(15).PostAsJsonAsync("/depressuring", req, Json.Options);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<DepressuringResult>(res);
        output.WriteLine($"equalised at {r.EqualisingPressure / 1e5:0.####} bar after {r.TimeToEqualise:0} s; moved {r.MassTransferred:0.###} kg; source min {r.SourceMinimumTemperature - 273.15:0.#} °C wall {r.SourceMinimumWallTemperature - 273.15:0.#} °C; filled max {r.MaximumTemperature - 273.15:0.#} °C; {r.Time.Count} steps | " + string.Join(" | ", r.Warnings));
        return r;
    }

    [Fact]
    public async Task TwoAdiabaticVesselsEqualiseAtTheIdealGasPressure()
    {
        // Ideal gas, no heat: the internal energy (P V /(γ − 1)) is kept, so where the pressures meet
        // P_eq = (Ps·Vs + Pt·Vt)/(Vs + Vt), whatever the temperatures: (10 + 1.01325)/2 = 5.5066 bara for equal volumes.
        var r = await Equalise(0, 0);
        var expected = (10e5 + 101325) / 2;
        Assert.NotNull(r.EqualisingPressure);
        Assert.Equal(expected, r.EqualisingPressure!.Value, expected * 0.01);
        Assert.NotNull(r.TimeToEqualise);
        // The source falls and the filled side rises throughout; the source is colder than it started, the other hotter.
        Assert.True(r.SourcePressure!.Zip(r.SourcePressure!.Skip(1)).All(p => p.Second <= p.First + 1));
        Assert.True(r.Pressure.Zip(r.Pressure.Skip(1)).All(p => p.Second >= p.First - 1));
        Assert.True(r.SourceMinimumTemperature < 300 - 20);
        Assert.True(r.MaximumTemperature > 300 + 20);
        // Mass: what left the source is what the other holds more (each vessel's own volume from its flashes).
        Assert.Equal(r.Inventory[^1] - r.Inventory[0], r.MassTransferred!.Value, r.MassTransferred.Value * 0.005);
    }

    [Fact]
    public async Task WithWallHeatTheVesselsStillEqualiseAndATargetAboveItIsSaidToBeOutOfReach()
    {
        // With the walls giving and taking heat, the net heat Q shifts P_eq by (γ − 1)·Q/(Vs + Vt): close to the ideal-gas value.
        var r = await Equalise(null, null);
        var ideal = (10e5 + 101325) / 2;
        Assert.InRange(r.EqualisingPressure!.Value, 0.95 * ideal, 1.05 * ideal);
        // The source's wall is warmer than its gas: heat flows in, so it is less cold than when adiabatic.
        var adiabatic = await Equalise(null, 0);
        Assert.True(r.SourceMinimumTemperature > adiabatic.SourceMinimumTemperature);
        Assert.Contains(adiabatic.Warnings, w => w.Contains("The source is adiabatic"));
        // A target above where they equalise cannot be reached, and the result says so.
        var high = await Equalise(null, null, target: 7e5);
        Assert.Null(high.TimeToTarget);
        Assert.Contains(high.Warnings, w => w.Contains("above the pressure the two sides equalise at"));
        // One below it is reached before they equalise.
        var low = await Equalise(null, null, target: 4e5);
        Assert.True(low.TimeToTarget < low.TimeToEqualise);
    }

    [Fact]
    public async Task ASeparatorSourceSendsItsGasAndWhatItFillsStartsDry()
    {
        // A two-phase source (methane–propane at 20 bara, 0.5 m of liquid in a 1 m × 3 m vessel): what is filled starts
        // with the source's vapour at 1 atm, so it holds no liquid to begin with; the gas off the top equalises the two.
        var mix = new Dictionary<string, double> { ["Methane"] = 0.5, ["Propane"] = 0.5 };
        var req = new DepressuringRequest
        {
            Flowsheet = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = ["Methane", "Propane"] }, Composition = mix,
            InitialPressure = 101325, InitialTemperature = 300, Vessel = new VesselGeometry(1.0, 3.0), Duration = 4 * 3600,
            Supply = new PressurizationSupply { Composition = mix, Pressure = 20e5, Temperature = 300, OrificeDiameter = 0.01, Vessel = new VesselGeometry(1.0, 3.0, LiquidLevel: 0.5) },
        };
        var res = await engine.Authed(15).PostAsJsonAsync("/depressuring", req, Json.Options);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<DepressuringResult>(res);
        output.WriteLine($"equalised at {r.EqualisingPressure / 1e5:0.###} bar after {r.TimeToEqualise:0} s; filled LF {r.LiquidVolumeFraction[0]:0.####}; {r.Time.Count} steps | " + string.Join(" | ", r.Warnings));
        Assert.Equal(0, r.LiquidVolumeFraction[0]);
        Assert.NotNull(r.EqualisingPressure);
        Assert.InRange(r.EqualisingPressure!.Value, 101325, 20e5);
        Assert.Equal(r.Inventory[^1] - r.Inventory[0], r.MassTransferred!.Value, r.MassTransferred.Value * 0.01);
    }

    private async Task<DepressuringResult> Pressurize(PressurizationSupply supply, double? target = null, double targetTime = 3600)
    {
        var gas = new Dictionary<string, double> { ["Methane"] = 0.92, ["Ethane"] = 0.05, ["Propane"] = 0.03 };
        var fs = new Flowsheet { PropertyPackage = "Peng-Robinson (PR)", Compounds = gas.Keys.ToList() };
        var req = new DepressuringRequest
        {
            // A 12" line (0.3 m inside), 5 km long, at atmospheric pressure, filled with the same gas.
            Flowsheet = fs, Composition = gas, InitialPressure = 101325, InitialTemperature = 288.15,
            Vessel = new VesselGeometry(0.3, 5000, Pipe: true), WallThickness = 0.0095,
            Supply = supply with { Composition = gas }, TargetPressure = target, TargetTime = targetTime, Duration = 4 * 3600,
        };
        var res = await engine.Authed(15).PostAsJsonAsync("/depressuring", req, Json.Options);
        Assert.True(res.IsSuccessStatusCode, await res.Content.ReadAsStringAsync());
        var r = await EngineFixture.Json<DepressuringResult>(res);
        output.WriteLine($"orifice {r.OrificeDiameter * 1000:0.#} mm (sized {r.OrificeSized}) Cv {r.Cv}, target {r.TargetPressure / 1e5:0.##} bar at {r.TimeToTarget:0} s, T {r.MinimumTemperature - 273.15:0.#}…{r.MaximumTemperature - 273.15:0.#} °C, inlet {r.MinimumInletTemperature - 273.15:0.#} °C, wall {r.MinimumWallTemperature - 273.15:0.#} °C, {r.Time.Count} steps | " + string.Join(" | ", r.Warnings));
        for (var i = 0; i < r.Time.Count; i += Math.Max(1, r.Time.Count / 8))
            output.WriteLine($"  t {r.Time[i]:0} s  P {r.Pressure[i] / 1e5:0.##} bar  T {r.Temperature[i] - 273.15:0.#} °C  Tw {r.WallTemperature[i] - 273.15:0.#} °C  W {r.MassFlow[i]:0.###}  M {r.Inventory[i]:0} kg");
        return r;
    }

    [Fact]
    public async Task PipelinePressurizesThroughAChokedOrificeAtConstantFlow()
    {
        var r = await Pressurize(new PressurizationSupply { Composition = [], Pressure = 70e5, Temperature = 288.15, OrificeDiameter = 0.025 });
        Assert.True(r.Pressure.Zip(r.Pressure.Skip(1)).All(p => p.Second > p.First));
        // Choked while the line is below about half the supply pressure: the flow is the same and the inventory grows linearly.
        var w0 = r.MassFlow[0];
        for (var i = 1; i < r.Time.Count && r.Pressure[i] < 0.4 * 70e5; i++)
        {
            Assert.Equal(w0, r.MassFlow[i], w0 * 1e-6);
            Assert.Equal(w0 * r.Time[i], r.Inventory[i] - r.Inventory[0], w0 * r.Time[i] * 0.02);
        }
        // Charging heats the gas; the gas let down from 70 bar enters colder than the supply (Joule–Thomson).
        Assert.True(r.MaximumTemperature > 288.15 + 5);
        Assert.True(r.MinimumInletTemperature < 288.15 - 10);
        Assert.NotNull(r.TimeToTarget);
        Assert.Equal(0.101325e6 + 0.95 * (70e5 - 101325), r.TargetPressure, 1);
    }

    [Fact]
    public async Task PipelinePressurizesThroughAControlValveAndTheOrificeIsSizedToTheTime()
    {
        var valve = await Pressurize(new PressurizationSupply { Composition = [], Pressure = 70e5, Temperature = 288.15, Cv = 20, Opening = 0.5 });
        Assert.Equal(10, valve.Cv);
        Assert.NotNull(valve.TimeToTarget);
        // Sized to reach 50 bar in an hour.
        var sized = await Pressurize(new PressurizationSupply { Composition = [], Pressure = 70e5, Temperature = 288.15 }, target: 50e5, targetTime: 3600);
        Assert.True(sized.OrificeSized);
        Assert.InRange(sized.TimeToTarget!.Value, 0.9 * 3600, 3600);
    }
}
