// Flowstead engine tests — dynamic simulation (D86): a separator under pressure and level control.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class DynamicsTests(EngineFixture engine, ITestOutputHelper output)
{
    /// <summary>Gas and condensate at 30 bara let down into a 5 m³, 3 m high separator at 20 bara; gas and liquid out through their valves.</summary>
    private static Flowsheet Separator(bool controllers = true)
    {
        var fs = new Flowsheet
        {
            PropertyPackage = "Peng-Robinson (PR)",
            Compounds = ["Methane", "Propane", "N-hexane"],
            Objects =
            [
                new SimObject
                {
                    Id = "1", Tag = "1", Type = ObjectType.MaterialStream,
                    Specs = new() { [SpecKeys.Temperature] = new(300, "K"), [SpecKeys.Pressure] = new(30e5, "bara"), [SpecKeys.MolarFlow] = new(20, "mol/s") },
                    Composition = new() { ["Methane"] = 0.6, ["Propane"] = 0.25, ["N-hexane"] = 0.15 },
                },
                new SimObject { Id = "vlv", Tag = "VLV-100", Type = ObjectType.Valve, Specs = new() { [SpecKeys.OutletPressure] = new(20e5, "bara") } },
                new SimObject { Id = "2", Tag = "2", Type = ObjectType.MaterialStream },
                new SimObject { Id = "sep", Tag = "V-100", Type = ObjectType.Separator, Specs = new() { [DynamicKeys.Volume] = new(5, "m3"), [DynamicKeys.Height] = new(3, "m") } },
                new SimObject { Id = "3", Tag = "3", Type = ObjectType.MaterialStream },
                new SimObject { Id = "pv", Tag = "PV-101", Type = ObjectType.Valve, Specs = new() { [SpecKeys.OutletPressure] = new(10e5, "bara") } },
                new SimObject { Id = "4", Tag = "4", Type = ObjectType.MaterialStream },
                new SimObject { Id = "5", Tag = "5", Type = ObjectType.MaterialStream },
                new SimObject { Id = "lv", Tag = "LV-102", Type = ObjectType.Valve, Specs = new() { [SpecKeys.OutletPressure] = new(5e5, "bara") } },
                new SimObject { Id = "6", Tag = "6", Type = ObjectType.MaterialStream },
            ],
            Connections = [new("1", 0, "vlv", 0), new("vlv", 0, "2", 0), new("2", 0, "sep", 0), new("sep", 0, "3", 0), new("3", 0, "pv", 0),
                new("pv", 0, "4", 0), new("sep", 1, "5", 0), new("5", 0, "lv", 0), new("lv", 0, "6", 0)],
        };
        if (!controllers) return fs;
        SimObject Loop(string id, string cKey, string mId, double sp) => new()
        {
            Id = id, Tag = id.ToUpperInvariant(), Type = ObjectType.Controller,
            Refs = new()
            {
                [AdjustKeys.ControlledObject] = "sep", [AdjustKeys.ControlledProperty] = cKey,
                [AdjustKeys.ManipulatedObject] = mId, [AdjustKeys.ManipulatedSpec] = SpecKeys.OutletPressure, [AdjustKeys.SteadyState] = "off",
            },
            Specs = new() { [AdjustKeys.Target] = new(sp, "SI") },
        };
        fs.Objects.Add(Loop("pic-100", "pressure", "pv", 20e5));
        fs.Objects.Add(Loop("lic-100", "liquidLevel", "lv", 1.5));
        return fs;
    }

    private async Task<DynamicsResult> Run(Flowsheet fs, double duration, List<DynamicEvent>? events = null)
    {
        var r = await EngineFixture.Json<DynamicsResult>(await engine.Authed().PostAsJsonAsync("/dynamics",
            new DynamicsRequest { Flowsheet = fs, Duration = duration, Step = 1, Events = events ?? [] }, Json.Options));
        foreach (var m in r.Messages) output.WriteLine($"{m.Level}: {m.Text}");
        output.WriteLine($"{r.Times.Count} samples in {r.ElapsedMs} ms");
        Assert.True(r.Status == SolveStatus.Converged, string.Join("; ", r.Messages.Select(m => m.Text)));
        return r;
    }

    private static List<double> T(DynamicsResult r, string id, string key) => r.Trends.Single(t => t.ObjectId == id && t.Key == key).Values;

    [Fact]
    public async Task FromItsSteadyStateThePlantStaysThere()
    {
        var r = await Run(Separator(), 120);
        var p = T(r, "sep", "pressure");
        var level = T(r, "sep", "liquidLevel");
        Assert.InRange(p[0], 20e5 * 0.999, 20e5 * 1.001);
        Assert.InRange(level[0], 1.49, 1.51);
        Assert.All(p, x => Assert.InRange(x, 20e5 * 0.995, 20e5 * 1.005));
        Assert.All(level, x => Assert.InRange(x, 1.45, 1.55));
    }

    [Fact]
    public async Task MassIsConservedWithTheValvesFixed()
    {
        // No controllers: a step on the liquid valve; what the vessel gains is what flowed in less what flowed out.
        var r = await Run(Separator(controllers: false), 300, [new DynamicEvent(10, "lv", DynamicKeys.Opening, 0.2)]);
        var times = r.Times;
        var win = T(r, "vlv", "massFlow"); var wg = T(r, "pv", "massFlow"); var wl = T(r, "lv", "massFlow");
        var m = T(r, "sep", "massHoldup");
        var net = 0.0;
        for (var i = 1; i < times.Count; i++)
            net += (times[i] - times[i - 1]) * ((win[i] + win[i - 1]) - (wg[i] + wg[i - 1]) - (wl[i] + wl[i - 1])) / 2;
        output.WriteLine($"holdup change {m[^1] - m[0]:F2} kg, net inflow {net:F2} kg");
        Assert.True(m[^1] > m[0] + 10, "closing the liquid valve fills the vessel");
        Assert.Equal(m[^1] - m[0], net, Math.Abs(m[^1] - m[0]) * 0.03 + 1);
        Assert.True(T(r, "sep", "liquidLevel")[^1] > 1.52, "the liquid rises");
    }

    [Fact]
    public async Task ControllersRejectAFeedPressureStep()
    {
        // The feed rises from 30 to 33 bara at 30 s: more flows in; PIC and LIC open their valves and bring the vessel back.
        var r = await Run(Separator(), 1800, [new DynamicEvent(30, "1", SpecKeys.Pressure, 33e5)]);
        var p = T(r, "sep", "pressure"); var level = T(r, "sep", "liquidLevel");
        var pOp = T(r, "pic-100", "output"); var lOp = T(r, "lic-100", "output");
        output.WriteLine($"P {p[0] / 1e5:F3} → max {p.Max() / 1e5:F3} → {p[^1] / 1e5:F3} bar; level {level[0]:F3} → max {level.Max():F3} → {level[^1]:F3} m; OP {pOp[0]:F1} → {pOp[^1]:F1} %, {lOp[0]:F1} → {lOp[^1]:F1} %");
        Assert.True(p.Max() > 20.05e5, "the step disturbs the pressure");
        Assert.InRange(p[^1], 20e5 * 0.995, 20e5 * 1.005);
        Assert.InRange(level[^1], 1.45, 1.55);
        Assert.True(pOp[^1] > pOp[0] && lOp[^1] > lOp[0], "both valves end more open to pass the larger flow");
        Assert.True(T(r, "vlv", "massFlow")[^1] > T(r, "vlv", "massFlow")[0] * 1.05);
    }

    [Fact]
    public async Task ASetpointChangeIsFollowed()
    {
        var r = await Run(Separator(), 1200, [new DynamicEvent(10, "pic-100", AdjustKeys.Target, 18e5)]);
        Assert.InRange(T(r, "sep", "pressure")[^1], 18e5 * 0.995, 18e5 * 1.005);
        Assert.Equal(18e5, T(r, "pic-100", "target")[^1]);
    }
}
