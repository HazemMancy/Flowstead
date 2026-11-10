// Flowstead engine tests — pipe segments with sections and fittings (D98).
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net.Http.Json;
using Flowstead.Engine.Contracts;
using Xunit;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class PipeSectionTests(EngineFixture engine)
{
    // 20 kg/s of water at 25 °C, 10 bar, in 4" schedule 40 (ID 102.3 mm), commercial steel.
    private const double D = 0.1023, Roughness = 4.6e-5, MassFlow = 20.0;

    private static Flowsheet Line(List<PipeSection>? sections, Dictionary<string, Spec>? specs = null) => new()
    {
        PropertyPackage = "Steam Tables (IAPWS-IF97)",
        Compounds = ["Water"],
        Objects =
        [
            new SimObject
            {
                Id = "f", Tag = "F", Type = ObjectType.MaterialStream, Composition = new() { ["Water"] = 1.0 },
                Specs = new() { [SpecKeys.Temperature] = new(298.15, "K"), [SpecKeys.Pressure] = new(10e5, "Pa"), [SpecKeys.MassFlow] = new(MassFlow, "kg/s") },
            },
            new SimObject
            {
                Id = "p", Tag = "P-1", Type = ObjectType.PipeSegment, PipeSections = sections,
                Specs = specs ?? new() { [SpecKeys.InnerDiameter] = new(D, "m"), [SpecKeys.Roughness] = new(Roughness, "m") },
            },
            new SimObject { Id = "o", Tag = "O", Type = ObjectType.MaterialStream },
        ],
        Connections = [new("f", 0, "p", 0), new("p", 0, "o", 0)],
    };

    private async Task<SolveResult> Solve(Flowsheet fs) =>
        await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));

    private static PipeSection Run(double length) => new() { Length = length };
    private static PipeSection Fit(string id, int count = 1) => new() { Fitting = id, Count = count };

    private static double Dp(SolveResult r)
    {
        Assert.True(r.Status == SolveStatus.Converged, string.Join("; ", r.Messages.Select(m => m.Text)));
        return r.Objects["p"].Properties["pressureDrop"];
    }

    [Fact]
    public async Task FittingsAddTheirEquivalentLengthOrVelocityHeads()
    {
        var plain = await Solve(Line([Run(100)]));
        var basis = Dp(plain);
        var liquid = plain.Objects["f"].Phases!.First(p => p.Key != "overall").Value.Properties;
        var rho = liquid["density"];
        var mu = liquid["viscosity"];
        var v = MassFlow / rho / (Math.PI * D * D / 4);
        var head = rho * v * v / 2;

        // Darcy–Weisbach with the Colebrook friction factor, solved by iteration: the reference, independent of DWSIM.
        var re = rho * v * D / mu;
        var f = 0.02;
        for (var i = 0; i < 50; i++) f = Math.Pow(-2 * Math.Log10(Roughness / D / 3.7 + 2.51 / (re * Math.Sqrt(f))), -2);
        Assert.Equal(f * 100 / D * head, basis, f * 100 / D * head * 0.02);

        // L/D fittings: 2 gate valves (8) and a globe valve (340) add f (2·8 + 340) ρv²/2, the same as 356 D more pipe.
        var valves = Dp(await Solve(Line([Run(100), Fit("gate_valve", 2), Fit("globe_valve")])));
        Assert.Equal(f * 356 * head, valves - basis, f * 356 * head * 0.02);
        var longer = Dp(await Solve(Line([Run(100 + 356 * D)])));
        Assert.Equal(longer, valves, longer * 0.002);

        // K fittings: a normal exit (K = 1) and a 2:1 reducer (K = 5.67) add K ρv²/2.
        var exits = Dp(await Solve(Line([Run(100), Fit("reducer_2_1"), Fit("exit_normal")])));
        Assert.Equal(6.67 * head, exits - basis, 6.67 * head * 0.01);
    }

    [Fact]
    public async Task AFittingTakesTheKTheUserGives()
    {
        // D145: a user's K is DWSIM's own K arithmetic. K 1 is exactly the normal exit (DWSIM's K 1); K 4.2 (a vendor's
        // strainer, say) adds 4.2 ρv²/2, with ρ and v the water's (hand: ṁ/ρ over πD²/4).
        var basis = await Solve(Line([Run(100)]));
        var liquid = basis.Objects["f"].Phases!.First(p => p.Key != "overall").Value.Properties;
        var rho = liquid["density"];
        var v = MassFlow / rho / (Math.PI * D * D / 4);
        var exit = Dp(await Solve(Line([Run(100), Fit("exit_normal")])));
        var one = Dp(await Solve(Line([Run(100), new PipeSection { Fitting = PipeFittings.UserK, K = 1.0 }])));
        Assert.Equal(exit, one, exit * 1e-9);
        var strainer = Dp(await Solve(Line([Run(100), new PipeSection { Fitting = PipeFittings.UserK, K = 4.2, Count = 2 }])));
        Assert.Equal(2 * 4.2 * rho * v * v / 2, strainer - Dp(basis), 2 * 4.2 * rho * v * v / 2 * 0.01);
        // Without its K it is refused, not taken as nothing.
        var none = await Solve(Line([Run(100), new PipeSection { Fitting = PipeFittings.UserK }]));
        Assert.Contains(none.Messages, m => m.Level == MessageLevel.Error && m.Text.Contains("give the fitting's K"));
        // Its DWSIM name carries the K, read back exactly.
        Assert.Equal(0.857, PipeFittings.KOf(PipeFittings.DwsimType(new PipeSection { Fitting = PipeFittings.UserK, K = 0.857 })));
        Assert.Equal(PipeFittings.UserK, PipeFittings.FromDwsim("Fitting K=0.857 [29]"));
    }

    [Fact]
    public async Task SectionsAddUpAndCarryTheirOwnDiameter()
    {
        // Two runs of 50 m are one of 100 m; a 3" run (77.9 mm) in between costs about (102.3/77.9)^5 ≈ 3.9 times more per metre.
        var one = Dp(await Solve(Line([Run(100)])));
        var two = Dp(await Solve(Line([Run(50), Run(50)])));
        Assert.Equal(one, two, one * 1e-3);
        var narrow = Dp(await Solve(Line([Run(50), new PipeSection { Length = 50, InnerDiameter = 0.0779 }])));
        Assert.InRange((narrow - one / 2) / (one / 2), 3.4, 4.4);

        // A rise of 10 m adds ρ g Δz.
        var up = await Solve(Line([Run(50), new PipeSection { Length = 50, ElevationChange = 10 }]));
        var rho = up.Objects["f"].Phases!.First(p => p.Key != "overall").Value.Properties["density"];
        Assert.Equal(rho * 9.80665 * 10, Dp(up) - one, rho * 9.80665 * 10 * 0.01);

        // The profile runs the whole 100 m, with each fitting's loss a step at one distance.
        var r = await Solve(Line([Run(50), Fit("globe_valve"), Run(50)]));
        var x = r.Objects["p"].Profiles![ProfileKeys.Length];
        var p = r.Objects["p"].Profiles![ProfileKeys.Pressure];
        Assert.Equal(100, x[^1], 1e-6);
        var at50 = Enumerable.Range(0, x.Count).Where(i => Math.Abs(x[i] - 50) < 1e-9).ToList();
        Assert.True(at50.Count >= 2, "points before and after the valve at 50 m");
        Assert.True(p[at50[0]] - p[at50[^1]] > 0.9 * (Dp(r) - one), "the valve's drop is a step at 50 m");
    }

    [Fact]
    public async Task BadSectionsAreExplained()
    {
        async Task<string> Errors(List<PipeSection> s) =>
            string.Join(" ", (await Solve(Line(s))).Messages.Where(m => m.Level == MessageLevel.Error).Select(m => m.Text));
        Assert.Contains("section 1: give a positive length", await Errors([new PipeSection()]));
        Assert.Contains("unknown fitting 'plug'", await Errors([Run(10), Fit("plug")]));
        Assert.Contains("at least one straight section", await Errors([Fit("gate_valve")]));
        Assert.Contains("the elevation change must be smaller than the length", await Errors([new PipeSection { Length = 5, ElevationChange = 6 }]));
        Assert.Contains("the count must be at least 1", await Errors([Run(10), Fit("gate_valve", 0)]));
    }

    [Fact]
    public async Task SectionsRoundTripThroughADwsimFile()
    {
        var fs = Line([Run(30), Fit("elbow_90", 3), new PipeSection { Length = 20, ElevationChange = -5, InnerDiameter = 0.0779, Increments = 6 }, Fit("swing_check_valve")]);
        var direct = await Solve(fs);
        using var client = engine.Authed();
        var export = await EngineFixture.Json<ExportResult>(await client.PostAsJsonAsync("/export", fs, Json.Options));
        var import = await EngineFixture.Json<ImportResult>(await client.PostAsJsonAsync("/import", new DwsimFile(export.Data), Json.Options));
        var pipe = import.Flowsheet.Objects.Single(o => o.Type == ObjectType.PipeSegment);
        Assert.Equal(fs.Objects[1].PipeSections, pipe.PipeSections);
        Assert.DoesNotContain(import.Warnings, w => w.Contains("sections"));
        Assert.Equal(Dp(direct), Dp(await Solve(import.Flowsheet with { Objects = import.Flowsheet.Objects.Select(o => o.Type == ObjectType.PipeSegment ? o with { Id = "p" } : o).ToList(),
            Connections = import.Flowsheet.Connections.Select(c => c with { FromObject = c.FromObject == pipe.Id ? "p" : c.FromObject, ToObject = c.ToObject == pipe.Id ? "p" : c.ToObject }).ToList() })), 1e-6 * Dp(direct));
    }

    [Theory]
    [InlineData("X", 1, 0)]
    [InlineData("X*1.5", 1.5, 0)]
    [InlineData("2*X+3", 2, 3)]
    [InlineData("X - 4.5", 1, -4.5)]
    [InlineData("1.2E+1*X-1e-3", 12, -0.001)]
    [InlineData("(X+1)/2", 0.5, 0.5)]
    public void SpecExpressionsThatAreLinearAreRead(string expression, double a, double b)
    {
        var ab = SpecExpressions.Linear(expression);
        Assert.NotNull(ab);
        Assert.Equal(a, ab!.Value.A, 1e-12);
        Assert.Equal(b, ab.Value.B, 1e-12);
    }

    [Theory]
    [InlineData("X*X")]
    [InlineData("Y+1")]
    [InlineData("sqrt(X)")]
    [InlineData("")]
    public void OtherSpecExpressionsAreNot(string expression) => Assert.Null(SpecExpressions.Linear(expression));
}
