// ThermoDock engine tests — HYSYS-style checks on unit operations (decision D46).
// Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using ThermoDock.Engine.Api;
using ThermoDock.Engine.Contracts;
using Xunit;

namespace ThermoDock.Engine.Tests;

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
    public async Task ValveTypeFactorsChangeWhereTheFlowChokes()
    {
        // A standard-bore ball valve (FL 0.55) cavitates far sooner: FL²(P1 − FF Pv) = 0.3025 × (3 − 0.947 × 0.47) ≈ 0.77 bar.
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        var r = await Through(Feed("f", water, 353.15, 3e5, 360), ObjectType.Valve,
            new() { [SpecKeys.OutletPressure] = 2e5, [SpecKeys.ValveFl] = 0.55 }, "Steam Tables (IAPWS-IF97)");
        Assert.Equal(0.77e5, r.Objects["u"].Properties["chokedPressureDrop"], 0.02e5);
        Assert.Contains("cavitating", On(r, "u", MessageLevel.Warning).Text);

        // Methane from 20 to 12 bar (x 0.4): a globe valve (xT 0.72) is not choked; a butterfly (xT 0.35) is.
        var methane = new Dictionary<string, double> { ["Methane"] = 1.0 };
        var globe = await Through(Feed("f", methane, 300, 20e5, 1000), ObjectType.Valve, new() { [SpecKeys.OutletPressure] = 12e5 });
        Assert.DoesNotContain(globe.Messages, m => m.ObjectId == "u" && m.Text.Contains("choked"));
        var butterfly = await Through(Feed("f", methane, 300, 20e5, 1000), ObjectType.Valve, new() { [SpecKeys.OutletPressure] = 12e5, [SpecKeys.ValveXt] = 0.35 });
        Assert.Contains("choked", On(butterfly, "u", MessageLevel.Warning).Text);
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

    [Fact]
    public async Task ZoneAnalysisFindsACrossInsideACondenser()
    {
        // Steam at 1 bar and 150 °C condensed to 95 °C against water from 30 °C. The ends look fine (hot end 150 vs
        // ~120 °C, cold end 95 vs 30 °C), but the steam condenses at 99.6 °C while the water there is already above 110 °C.
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        Flowsheet Condenser(double coolingWater) => new()
        {
            PropertyPackage = "Steam Tables (IAPWS-IF97)",
            Compounds = ["Water"],
            Objects =
            [
                Feed("a", water, 423.15, 1e5, 36), Feed("b", water, 303.15, 3e5, coolingWater),
                new SimObject { Id = "e", Tag = "E-100", Type = ObjectType.HeatExchanger, Specs = new() { [SpecKeys.HotOutletTemperature] = new Spec(368.15, "K") } },
                Product("ao"), Product("bo"),
            ],
            Connections = [new("a", 0, "e", 0), new("b", 0, "e", 1), new("e", 0, "ao", 0), new("e", 1, "bo", 0)],
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", Condenser(226.4), Json.Options));
        Assert.Equal(ObjectStatus.Error, r.Objects["e"].Status);
        Assert.StartsWith("Internal temperature cross", r.Objects["e"].Error);

        // Three times the cooling water: feasible, pinched where the steam starts to condense (99.6 °C).
        r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", Condenser(720), Json.Options));
        var e = r.Objects["e"].Properties;
        Assert.Equal(372.76, e["pinchHotTemperature"], 0.1);
        Assert.InRange(e["minimumApproach"], 30, 60);
        Assert.True(e["minimumApproach"] < Math.Min(e["hotOutletTemperature"] - 303.15, 423.15 - e["coldOutletTemperature"]));
    }

    private static readonly PerformanceCurve PumpCurve = new()
    {
        Points =
        [
            new(0.01, 80, 0.55, 2.0), new(0.02, 75, 0.70, 3.0), new(0.03, 65, 0.75, 4.5), new(0.04, 50, 0.68, 7.0),
        ],
    };

    private async Task<SolveResult> OnCurve(SimObject feed, ObjectType type, PerformanceCurve curve, string package)
    {
        var fs = new Flowsheet
        {
            PropertyPackage = package,
            Compounds = feed.Composition.Keys.ToList(),
            Objects = [feed, new SimObject { Id = "u", Tag = "U-100", Type = type, Curve = curve }, Product("o")],
            Connections = [new Connection(feed.Id, 0, "u", 0), new Connection("u", 0, "o", 0)],
        };
        return await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
    }

    [Fact]
    public async Task PumpRunsOnItsCurve()
    {
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        // 100 m³/h (0.0278 m³/s): head 75 − 0.78 × 10 = 67.2 m and efficiency 0.70 + 0.78 × 0.05 = 0.739 (linear on the curve).
        var r = await OnCurve(Feed("f", water, 300, 2e5, 5540), ObjectType.Pump, PumpCurve, "Steam Tables (IAPWS-IF97)");
        Assert.Equal(SolveStatus.Converged, r.Status);
        var p = r.Objects["u"].Properties;
        Assert.Equal(67.2, p["head"], 0.1);
        Assert.Equal(0.739, p["efficiency"], 0.002);
        // Power = Q ΔP / η, and it heats the liquid (DWSIM's own curve mode reported zero).
        Assert.Equal(p["inletVolumeFlow"] * p["pressureRise"] / p["efficiency"], p["power"], p["power"] * 0.01);
        Assert.True(r.Objects["o"].Properties["temperature"] > 300);
        Assert.Equal(4.17, p["npshRequired"], 0.02);

        var runout = await OnCurve(Feed("f", water, 300, 2e5, 10800), ObjectType.Pump, PumpCurve, "Steam Tables (IAPWS-IF97)");
        Assert.StartsWith("Run-out", runout.Objects["u"].Error);
    }

    [Fact]
    public async Task PumpWithTooLittleNpshCavitates()
    {
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        // Water at 90 °C and 0.75 bar: Pv 0.70 bar leaves about 0.5 m, against 4.2 m required at 100 m³/h.
        var r = await OnCurve(Feed("f", water, 363.15, 0.75e5, 5440), ObjectType.Pump, PumpCurve, "Steam Tables (IAPWS-IF97)");
        Assert.Equal(ObjectStatus.Error, r.Objects["u"].Status);
        Assert.StartsWith("It cavitates", r.Objects["u"].Error);
    }

    [Fact]
    public async Task VariableSpeedPumpFollowsTheAffinityLawsAndItsSpeedLines()
    {
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        var feed = Feed("f", water, 300, 2e5, 5540); // 100 m³/h, 0.0278 m³/s
        // At 90 % speed: the rated curve's point at 0.0278 / 0.9 = 0.0309 m³/s (63.7 m, η 0.744), head × 0.81 = 51.6 m.
        var slow = await OnCurve(feed, ObjectType.Pump, PumpCurve with { Speed = 2950, OperatingSpeed = 2655 }, "Steam Tables (IAPWS-IF97)");
        Assert.Equal(SolveStatus.Converged, slow.Status);
        var p = slow.Objects["u"].Properties;
        Assert.Equal(51.6, p["head"], 0.15);
        Assert.Equal(0.744, p["efficiency"], 0.003);
        Assert.Equal(2655, p["operatingSpeed"]);

        // A measured line at 80 % speed with 10 % less head than the affinity laws give: halfway between the two
        // speeds, the head is the mean of both lines carried to 90 % speed, 0.95 × 51.6 = 49.0 m.
        var low = PumpCurve.Points.Select(x => new CurvePoint(x.Flow * 0.8, x.Head * 0.64 * 0.9, x.Efficiency, x.Npshr * 0.64)).ToList();
        var map = PumpCurve with { Speed = 2950, SpeedLines = [new SpeedLine(2360, low)], OperatingSpeed = 2655 };
        var r = await OnCurve(feed, ObjectType.Pump, map, "Steam Tables (IAPWS-IF97)");
        Assert.Equal(49.0, r.Objects["u"].Properties["head"], 0.2);
    }

    [Fact]
    public async Task PolynomialFitReadsBetweenThePointsAsHysysDoes()
    {
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        // Points on H = 90 − 25000 Q²: at 0.0278 m³/s the quadratic gives 70.7 m where straight lines give 70.3 m.
        var curve = new PerformanceCurve { Points = [new(0.01, 87.5), new(0.02, 80), new(0.03, 67.5), new(0.04, 50)], Fit = CurveFits.Polynomial, Order = 2 };
        var r = await OnCurve(Feed("f", water, 300, 2e5, 5540), ObjectType.Pump, curve, "Steam Tables (IAPWS-IF97)");
        var q = r.Objects["u"].Properties["inletVolumeFlow"];
        Assert.Equal(90 - 25000 * q * q, r.Objects["u"].Properties["head"], 0.05);
        var linear = await OnCurve(Feed("f", water, 300, 2e5, 5540), ObjectType.Pump, curve with { Fit = null }, "Steam Tables (IAPWS-IF97)");
        Assert.True(Math.Abs(linear.Objects["u"].Properties["head"] - (90 - 25000 * q * q)) > 0.3);
    }

    [Fact]
    public async Task GivenPressureSetsTheSpeedOfAVariableSpeedPump()
    {
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        // The 90 % speed case above gave 51.6 m at 100 m³/h; asking for that rise, the engine should find about 2655 rpm.
        var fs = new Flowsheet
        {
            PropertyPackage = "Steam Tables (IAPWS-IF97)", Compounds = ["Water"],
            Objects =
            [
                Feed("f", water, 300, 2e5, 5540),
                new SimObject { Id = "u", Tag = "P-100", Type = ObjectType.Pump, Curve = PumpCurve with { Speed = 2950 }, Specs = new() { [SpecKeys.PressureRise] = new(51.6 * 996.5 * 9.80665, "Pa") } },
                Product("o"),
            ],
            Connections = [new Connection("f", 0, "u", 0), new Connection("u", 0, "o", 0)],
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.Equal(SolveStatus.Converged, r.Status);
        var p = r.Objects["u"].Properties;
        Assert.Equal(2655, p["operatingSpeed"], 5.0);
        Assert.Equal(0.744, p["efficiency"], 0.003);
        Assert.Equal(51.6, p["head"], 0.1);

        // A pressure no speed up to twice the rated one can give is an error, not a wrong answer.
        var tooHigh = fs with { Objects = fs.Objects.Select(o => o.Id == "u" ? o with { Specs = new() { [SpecKeys.PressureRise] = new(3e7, "Pa") } } : o).ToList() };
        var bad = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", tooHigh, Json.Options));
        Assert.Contains(bad.Messages, m => m.ObjectId == "u" && m.Text.Contains("no speed"));
    }

    [Fact]
    public async Task CompressorRunsOnItsCurveAndSurgesBelowIt()
    {
        var methane = new Dictionary<string, double> { ["Methane"] = 1.0 };
        var curve = new PerformanceCurve
        {
            Points = [new(0.08, 9000, 0.70), new(0.10, 8800, 0.76), new(0.12, 8400, 0.78), new(0.14, 7800, 0.76), new(0.16, 7000, 0.70)],
        };
        var r = await OnCurve(Feed("f", methane, 300, 20e5, 360), ObjectType.Compressor, curve, "Peng-Robinson (PR)");
        Assert.Equal(SolveStatus.Converged, r.Status);
        var c = r.Objects["u"].Properties;
        Assert.Equal(c["curveHead"], c["adiabaticHead"], c["curveHead"] * 1e-3);
        var q = c["inletVolumeFlow"];
        var (lo, hi) = (curve.Points.Last(p => p.Flow <= q), curve.Points.First(p => p.Flow >= q));
        Assert.Equal(lo.Head + (q - lo.Flow) / (hi.Flow - lo.Flow) * (hi.Head - lo.Head), c["curveHead"], 1e-3);

        var surge = await OnCurve(Feed("f", methane, 300, 20e5, 180), ObjectType.Compressor, curve, "Peng-Robinson (PR)");
        Assert.StartsWith("Surge", surge.Objects["u"].Error);
    }

    [Fact]
    public async Task CurveAndOutletPressureTogetherAreOverSpecified()
    {
        var water = new Dictionary<string, double> { ["Water"] = 1.0 };
        var fs = new Flowsheet
        {
            PropertyPackage = "Steam Tables (IAPWS-IF97)",
            Compounds = ["Water"],
            Objects =
            [
                Feed("f", water, 300, 2e5, 5540),
                new SimObject { Id = "u", Tag = "P-100", Type = ObjectType.Pump, Curve = PumpCurve, Specs = new() { [SpecKeys.OutletPressure] = new Spec(10e5, "Pa") } },
                Product("o"),
            ],
            Connections = [new Connection("f", 0, "u", 0), new Connection("u", 0, "o", 0)],
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.Equal(SolveStatus.Invalid, r.Status);
    }
}
