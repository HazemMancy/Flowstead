// Flowstead engine tests — steady-state control loops (D84): controllers and Adjusts, any number, solved together.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using Flowstead.Engine.Contracts;
using Xunit;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class ControllerTests(EngineFixture engine)
{
    /// <summary>Gas at 70 bara, 40 °C through a let-down valve and a heater.</summary>
    private static Flowsheet LetDown() => new()
    {
        PropertyPackage = "Peng-Robinson (PR)",
        Compounds = ["Methane", "Ethane", "Propane"],
        Objects =
        [
            new SimObject
            {
                Id = "1", Tag = "1", Type = ObjectType.MaterialStream,
                Specs = new()
                {
                    [SpecKeys.Temperature] = new Spec(313.15, "C"), [SpecKeys.Pressure] = new Spec(70e5, "bara"),
                    [SpecKeys.MolarFlow] = new Spec(100, "mol/s"),
                },
                Composition = new() { ["Methane"] = 0.85, ["Ethane"] = 0.1, ["Propane"] = 0.05 },
            },
            new SimObject { Id = "v", Tag = "VLV-100", Type = ObjectType.Valve, Specs = new() { [SpecKeys.OutletPressure] = new Spec(50e5, "bara") } },
            new SimObject { Id = "2", Tag = "2", Type = ObjectType.MaterialStream },
            new SimObject { Id = "h", Tag = "E-100", Type = ObjectType.Heater, Specs = new() { [SpecKeys.Duty] = new Spec(100_000, "kW") } },
            new SimObject { Id = "3", Tag = "3", Type = ObjectType.MaterialStream },
        ],
        Connections = [new("1", 0, "v", 0), new("v", 0, "2", 0), new("2", 0, "h", 0), new("h", 0, "3", 0)],
    };

    private static SimObject Loop(string id, ObjectType type, string mId, string mKey, string cId, string cKey, double target, double tol,
        double? min = null, double? max = null)
    {
        var specs = new Dictionary<string, Spec> { [AdjustKeys.Target] = new(target, "SI"), [AdjustKeys.Tolerance] = new(tol, "SI") };
        if (min is { } a) specs[AdjustKeys.Minimum] = new(a, "SI");
        if (max is { } b) specs[AdjustKeys.Maximum] = new(b, "SI");
        return new SimObject
        {
            Id = id, Tag = id.ToUpperInvariant(), Type = type, Specs = specs,
            Refs = new()
            {
                [AdjustKeys.ManipulatedObject] = mId, [AdjustKeys.ManipulatedSpec] = mKey,
                [AdjustKeys.ControlledObject] = cId, [AdjustKeys.ControlledProperty] = cKey,
            },
        };
    }

    private async Task<SolveResult> Solve(Flowsheet fs) =>
        await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));

    [Fact]
    public async Task TwoInteractingLoopsAreSolvedTogether()
    {
        // The valve's drop cools the gas (Joule-Thomson), so the heater's duty depends on both loops.
        var fs = LetDown();
        fs.Objects.Add(Loop("pic-100", ObjectType.Controller, "v", SpecKeys.OutletPressure, "3", "pressure", 30e5, 100, 10e5, 69e5));
        fs.Objects.Add(Loop("tic-100", ObjectType.Controller, "h", SpecKeys.Duty, "3", "temperature", 303.15, 0.01, 0, 5e6));
        var r = await Solve(fs);
        Assert.True(r.Status == SolveStatus.Converged, string.Join("; ", r.Messages.Select(m => m.Text)));
        Assert.Equal(30e5, r.Objects["3"].Properties["pressure"], 30e5 * 1e-4);
        Assert.Equal(303.15, r.Objects["3"].Properties["temperature"], 0.02);
        Assert.Equal(ObjectStatus.Solved, r.Objects["pic-100"].Status);
        Assert.Equal(ObjectStatus.Solved, r.Objects["tic-100"].Status);
        // Output as a % of the range: (30 − 10) / (69 − 10) for the pressure loop.
        Assert.Equal(100 * (30e5 - 10e5) / (69e5 - 10e5), r.Objects["pic-100"].Properties["output"], 0.1);
        // The heater's duty is what the gas needs from the valve outlet's temperature to 30 °C.
        var t2 = r.Objects["2"].Properties["temperature"];
        Assert.True(t2 < 303.15, $"valve outlet {t2} K should be below 30 °C after the let-down");
        Assert.True(r.Objects["h"].Properties["duty"] > 0);
    }

    [Fact]
    public async Task AControllerAndAnAdjustWorkTogether()
    {
        var fs = LetDown();
        fs.Objects.Add(Loop("pic-100", ObjectType.Controller, "v", SpecKeys.OutletPressure, "2", "pressure", 40e5, 100, 10e5, 69e5));
        fs.Objects.Add(Loop("adj-1", ObjectType.Adjust, "h", SpecKeys.Duty, "3", "temperature", 323.15, 0.01, 0));
        var r = await Solve(fs);
        Assert.True(r.Status == SolveStatus.Converged, string.Join("; ", r.Messages.Select(m => m.Text)));
        Assert.Equal(323.15, r.Objects["3"].Properties["temperature"], 0.02);
        Assert.False(r.Objects["adj-1"].Properties.ContainsKey("output")); // an Adjust has no faceplate
    }

    [Fact]
    public async Task ASaturatedControllerSaysSo()
    {
        // 75 bara at the outlet is above the 70 bara feed: the valve can only go down to its range's end.
        var fs = LetDown();
        fs.Objects.Add(Loop("pic-100", ObjectType.Controller, "v", SpecKeys.OutletPressure, "2", "pressure", 75e5, 100, 10e5, 69e5));
        var r = await Solve(fs);
        Assert.Equal(SolveStatus.Failed, r.Status);
        Assert.Equal(ObjectStatus.Error, r.Objects["pic-100"].Status);
    }

    [Fact]
    public async Task TwoLoopsOnOneValueOrOneVariableAreRejected()
    {
        var fs = LetDown();
        fs.Objects.Add(Loop("a", ObjectType.Controller, "v", SpecKeys.OutletPressure, "3", "pressure", 30e5, 100));
        fs.Objects.Add(Loop("b", ObjectType.Controller, "h", SpecKeys.Duty, "3", "pressure", 31e5, 100));
        fs.Objects.Add(Loop("c", ObjectType.Adjust, "v", SpecKeys.OutletPressure, "3", "temperature", 300, 0.1));
        var r = await Solve(fs);
        Assert.Equal(SolveStatus.Invalid, r.Status);
        Assert.Contains(r.Messages, m => m.Text.Contains("already controlled by A"));
        Assert.Contains(r.Messages, m => m.Text.Contains("already varied by A"));
    }
}
