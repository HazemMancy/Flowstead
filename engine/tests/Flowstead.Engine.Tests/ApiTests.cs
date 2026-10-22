// Flowstead engine tests — HTTP API behaviour: auth, lookups, CRUD, stored results, validation.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net;
using System.Net.Http.Json;
using Flowstead.Engine.Api;
using Flowstead.Engine.Contracts;
using Xunit;
using Xunit.Abstractions;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class ApiTests(EngineFixture engine, ITestOutputHelper output)
{
    private static Flowsheet Water(double tempK) => new()
    {
        PropertyPackage = "Steam Tables (IAPWS-IF97)",
        Compounds = ["Water"],
        Objects =
        [
            new SimObject
            {
                Id = "w", Tag = "W-1", Type = ObjectType.MaterialStream,
                Specs = new()
                {
                    [SpecKeys.Temperature] = new Spec(tempK, "C"),
                    [SpecKeys.Pressure] = new Spec(200_000, "kPa"),
                    [SpecKeys.MassFlow] = new Spec(1.0, "kg/h"),
                },
                Composition = new() { ["Water"] = 1.0 },
            },
        ],
    };

    [Fact]
    public async Task RejectsMissingAndUnknownKeys()
    {
        var anon = engine.CreateClient();
        Assert.Equal(HttpStatusCode.Unauthorized, (await anon.GetAsync("/health")).StatusCode);
        anon.DefaultRequestHeaders.Authorization = new("Bearer", "fs_not-a-key");
        var r = await anon.GetAsync("/health");
        Assert.Equal(HttpStatusCode.Unauthorized, r.StatusCode);
        Assert.Equal("unauthorized", (await EngineFixture.Json<EngineError>(r)).Code);
        Assert.Equal(HttpStatusCode.OK, (await engine.CreateClient().GetAsync("/healthz")).StatusCode);
    }

    [Fact]
    public async Task HealthReportsVersions()
    {
        var h = await EngineFixture.Json<EngineHealth>(await engine.Authed().GetAsync("/health"));
        Assert.Equal("ok", h.Status);
        Assert.Equal("9.0.5", h.DwsimVersion);
    }

    [Fact]
    public async Task PropertyPackagesListMvpFirstIncludingPrLk()
    {
        var list = await engine.Authed().GetFromJsonAsync<List<PropertyPackageInfo>>("/property-packages", Json.Options);
        Assert.NotNull(list);
        Assert.Equal("Peng-Robinson (PR)", list[0].Name);
        Assert.Contains(list, p => p is { Name: "Peng-Robinson / Lee-Kesler (PR/LK)", Mvp: true });
        Assert.Contains(list, p => p is { Name: "Steam Tables (IAPWS-IF97)", Mvp: true });
    }

    [Fact]
    public async Task CompoundSearchRanksExactMatchFirst()
    {
        var list = await engine.Authed().GetFromJsonAsync<List<CompoundInfo>>("/compounds?query=methane&limit=5", Json.Options);
        Assert.NotNull(list);
        Assert.Equal("Methane", list[0].Name);
        Assert.Equal(16.043, list[0].MolecularWeight, 2);
        Assert.InRange(list.Count, 1, 5);
    }

    [Theory]
    [InlineData("CO2", "Carbon dioxide")]
    [InlineData("nC4", "N-butane")]
    [InlineData("butane", "N-butane")]
    [InlineData("TEG", "Triethylene glycol")]
    [InlineData("124-38-9", "Carbon dioxide")]
    public async Task CompoundSearchUnderstandsEngineeringShorthand(string query, string expected)
    {
        var list = await engine.Authed().GetFromJsonAsync<List<CompoundInfo>>($"/compounds?query={Uri.EscapeDataString(query)}&limit=3", Json.Options);
        Assert.Equal(expected, list![0].Name);
    }

    [Fact]
    public async Task HypotheticalEstimatesKeepTheInputsAndFillTheRest()
    {
        var client = engine.Authed();
        var r = await client.PostAsJsonAsync("/hypotheticals/estimate",
            new List<Hypothetical> { new() { Name = "C10*", NormalBoilingPoint = 447.3, MolecularWeight = 142.28, SpecificGravity = 0.734 } }, Json.Options);
        var p = Assert.Single(await EngineFixture.Json<List<HypotheticalProperties>>(r));
        Assert.Equal(447.3, p.NormalBoilingPoint, 6);
        Assert.Equal(0.734, p.SpecificGravity, 6);
        // n-decane: Tc 617.7 K, Pc 21.1 bar. Riazi–Daubert: within 1 % and 5 %.
        Assert.InRange(p.CriticalTemperature, 611, 624);
        Assert.InRange(p.CriticalPressure, 20.0e5, 22.2e5);
        Assert.Equal(["criticalTemperature", "criticalPressure", "acentricFactor"], p.Estimated);

        var bad = await client.PostAsJsonAsync("/hypotheticals/estimate", new List<Hypothetical> { new() { Name = "X", SpecificGravity = 0.8 } }, Json.Options);
        Assert.Equal(HttpStatusCode.UnprocessableEntity, bad.StatusCode);
    }

    [Fact]
    public async Task UnderSpecifiedStreamIsRejectedBeforeSolving()
    {
        var fs = Water(300) with { };
        fs.Objects[0].Specs.Remove(SpecKeys.Temperature);
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.Equal(SolveStatus.Invalid, r.Status);
        Assert.Contains(r.Messages, m => m.ObjectId == "w" && m.Text.Contains("under-specified"));
        Assert.Empty(r.Objects);
    }

    [Fact]
    public async Task StoredFlowsheetSolveResultsAndStaleness()
    {
        var client = engine.Authed();
        var created = await client.PostAsJsonAsync("/flowsheets", new FlowsheetDocument("Water heater check", Water(300)), Json.Options);
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);
        var stored = await EngineFixture.Json<StoredFlowsheet>(created);

        Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync($"/flowsheets/{stored.Id}/results")).StatusCode);

        var solved = await EngineFixture.Json<SolveResult>(await client.PostAsync($"/flowsheets/{stored.Id}/solve", null));
        Assert.Equal(SolveStatus.Converged, solved.Status);
        Assert.Equal(0.0, solved.Objects["w"].Properties["vapourFraction"]);

        var results = await EngineFixture.Json<FlowsheetResults>(await client.GetAsync($"/flowsheets/{stored.Id}/results"));
        Assert.False(results.Stale);

        await client.PutAsJsonAsync($"/flowsheets/{stored.Id}", new FlowsheetDocument("Water heater check", Water(310)), Json.Options);
        results = await EngineFixture.Json<FlowsheetResults>(await client.GetAsync($"/flowsheets/{stored.Id}/results"));
        Assert.True(results.Stale);

        Assert.Equal(HttpStatusCode.NoContent, (await client.DeleteAsync($"/flowsheets/{stored.Id}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync($"/flowsheets/{stored.Id}")).StatusCode);
    }

    [Fact]
    public async Task ValveLetdownDropsPressureAndCoolsGas()
    {
        // Small JT check: lean gas 70 bara → 20 bara through a valve must cool down (positive JT coefficient).
        var fs = new Flowsheet
        {
            PropertyPackage = "Peng-Robinson (PR)",
            Compounds = ["Methane", "Ethane", "Propane"],
            Objects =
            [
                new SimObject
                {
                    Id = "in", Tag = "HP", Type = ObjectType.MaterialStream,
                    Specs = new()
                    {
                        [SpecKeys.Temperature] = new Spec(313.15, "C"),
                        [SpecKeys.Pressure] = new Spec(7e6, "bara"),
                        [SpecKeys.MolarFlow] = new Spec(100 / 3.6, "kgmol/h"),
                    },
                    Composition = new() { ["Methane"] = 0.9, ["Ethane"] = 0.07, ["Propane"] = 0.03 },
                },
                new SimObject { Id = "v", Tag = "PV-100", Type = ObjectType.Valve, Specs = new() { [SpecKeys.OutletPressure] = new Spec(2e6, "bara") } },
                new SimObject { Id = "out", Tag = "LP", Type = ObjectType.MaterialStream },
            ],
            Connections = [new Connection("in", 0, "v", 0), new Connection("v", 0, "out", 0)],
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.True(r.Status == SolveStatus.Converged, string.Join("; ", r.Messages.Select(m => m.Text)));
        var outlet = r.Objects["out"].Properties;
        Assert.Equal(2e6, outlet["pressure"], 1);
        Assert.InRange(outlet["temperature"], 273.15, 313.15 - 5); // cooled by more than 5 K
        Assert.Equal(r.Objects["in"].Properties["massEnthalpy"], outlet["massEnthalpy"], 0); // isenthalpic, J/kg
    }

    [Fact]
    public async Task CostaldIsOptInAndChangesLiquidDensity()
    {
        static Flowsheet Liquid(double tK, double pPa, Dictionary<string, double> x) => new()
        {
            PropertyPackage = "Peng-Robinson (PR)",
            Compounds = x.Keys.ToList(),
            Objects =
            [
                new SimObject
                {
                    Id = "l", Tag = "LIQ", Type = ObjectType.MaterialStream,
                    Specs = new()
                    {
                        [SpecKeys.Temperature] = new Spec(tK, "C"),
                        [SpecKeys.Pressure] = new Spec(pPa, "bara"),
                        [SpecKeys.MassFlow] = new Spec(1.0, "kg/h"),
                    },
                    Composition = x,
                },
            ],
        };
        async Task<double> Density(Flowsheet f)
        {
            var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", f, Json.Options));
            Assert.True(r.Status == SolveStatus.Converged, string.Join("; ", r.Messages.Select(m => m.Text)));
            return r.Objects["l"].Phases!["liquid1"].Properties["density"];
        }
        // Pure n-hexane at 25 °C (measured about 655 kg/m³): both methods agree.
        var hexane = Liquid(298.15, 1e5, new() { ["N-hexane"] = 1.0 });
        Assert.Equal(await Density(hexane), await Density(hexane with { LiquidDensity = LiquidDensityMethods.Costald }), 0);
        // Propane/n-decane 40/60 mol at 25 °C, 20 bar. DWSIM's default mixes pure-liquid densities by mole fraction
        // (D39) and gives about 614 kg/m³; ideal mixing by mass gives about 675 and COSTALD a little more.
        var mixture = Liquid(298.15, 2e6, new() { ["Propane"] = 0.4, ["N-decane"] = 0.6 });
        var standard = await Density(mixture);
        var costald = await Density(mixture with { LiquidDensity = LiquidDensityMethods.Costald });
        output.WriteLine($"C3/nC10: default {standard:F1}, COSTALD {costald:F1} kg/m³");
        Assert.InRange(standard, 600, 630);
        Assert.InRange(costald, 675, 710);

        var bad = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", hexane with { LiquidDensity = "rackett" }, Json.Options));
        Assert.Equal(SolveStatus.Invalid, bad.Status);
    }

    [Theory]
    [InlineData(ObjectType.Heater, 298.15, 353.15)]
    [InlineData(ObjectType.Cooler, 353.15, 298.15)]
    public async Task HeaterAndCoolerWithoutDrawnEnergyStream(ObjectType type, double tin, double tout)
    {
        // 1 kg/s water at 2 bar, 25 ↔ 80 °C. IAPWS-IF97: Δh = 334.9 − 104.9 ≈ 230.1 kJ/kg, so |Q| ≈ 230.1 kW.
        var fs = new Flowsheet
        {
            PropertyPackage = "Steam Tables (IAPWS-IF97)",
            Compounds = ["Water"],
            Objects =
            [
                new SimObject
                {
                    Id = "in", Tag = "1", Type = ObjectType.MaterialStream,
                    Specs = new()
                    {
                        [SpecKeys.Temperature] = new Spec(tin, "C"),
                        [SpecKeys.Pressure] = new Spec(2e5, "bara"),
                        [SpecKeys.MassFlow] = new Spec(1.0, "kg/s"),
                    },
                    Composition = new() { ["Water"] = 1.0 },
                },
                new SimObject { Id = "e", Tag = "E-100", Type = type, Specs = new() { [SpecKeys.OutletTemperature] = new Spec(tout, "C") } },
                new SimObject { Id = "out", Tag = "2", Type = ObjectType.MaterialStream },
            ],
            Connections = [new Connection("in", 0, "e", 0), new Connection("e", 0, "out", 0)],
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.True(r.Status == SolveStatus.Converged, string.Join("; ", r.Messages.Select(m => m.Text)));
        Assert.Equal(tout, r.Objects["out"].Properties["temperature"], 2);
        var duty = r.Objects["e"].Properties["duty"];
        Assert.InRange(Math.Abs(duty), 230_100 * 0.995, 230_100 * 1.005);
        Assert.Equal(3, r.Objects.Count); // the hidden energy stream is not reported
    }

    [Fact]
    public async Task AdjustFindsTheDutyForHalfVaporisedWater()
    {
        // 1 kg/s water at 1 atm, 25 °C. VF = 0.5 needs Δh = (h_f − h_25) + h_fg/2 ≈ 314.1 + 1128.2 ≈ 1442 kW (IAPWS-IF97).
        var fs = new Flowsheet
        {
            PropertyPackage = "Steam Tables (IAPWS-IF97)",
            Compounds = ["Water"],
            Objects =
            [
                new SimObject
                {
                    Id = "in", Tag = "1", Type = ObjectType.MaterialStream,
                    Specs = new()
                    {
                        [SpecKeys.Temperature] = new Spec(298.15, "C"),
                        [SpecKeys.Pressure] = new Spec(101325, "kPa"),
                        [SpecKeys.MassFlow] = new Spec(1.0, "kg/s"),
                    },
                    Composition = new() { ["Water"] = 1.0 },
                },
                new SimObject { Id = "h", Tag = "E-100", Type = ObjectType.Heater, Specs = new() { [SpecKeys.Duty] = new Spec(500_000, "kW") } },
                new SimObject { Id = "out", Tag = "2", Type = ObjectType.MaterialStream },
                new SimObject
                {
                    Id = "adj", Tag = "ADJ-1", Type = ObjectType.Adjust,
                    Refs = new()
                    {
                        [AdjustKeys.ManipulatedObject] = "h", [AdjustKeys.ManipulatedSpec] = SpecKeys.Duty,
                        [AdjustKeys.ControlledObject] = "out", [AdjustKeys.ControlledProperty] = "vapourFraction",
                    },
                    Specs = new()
                    {
                        [AdjustKeys.Target] = new Spec(0.5, "frac"),
                        [AdjustKeys.Tolerance] = new Spec(1e-5, "frac"),
                        [AdjustKeys.Minimum] = new Spec(0, "kW"),
                    },
                },
            ],
            Connections = [new Connection("in", 0, "h", 0), new Connection("h", 0, "out", 0)],
        };
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.True(r.Status == SolveStatus.Converged, string.Join("; ", r.Messages.Select(m => m.Text)));
        Assert.Equal(0.5, r.Objects["out"].Properties["vapourFraction"], 4);
        Assert.InRange(r.Objects["h"].Properties["duty"], 1_442_300 * 0.995, 1_442_300 * 1.005);
        var adj = r.Objects["adj"].Properties;
        Assert.InRange(adj["iterations"], 2, AdjustSolverLimits.Max);
        Assert.Equal(r.Iterations, (int)adj["iterations"]);
    }

    [Fact]
    public async Task AdjustWithoutAStartingValueIsRejected()
    {
        var fs = Water(300) with { };
        fs.Objects.Add(new SimObject
        {
            Id = "adj", Tag = "ADJ-1", Type = ObjectType.Adjust,
            Refs = new()
            {
                [AdjustKeys.ManipulatedObject] = "w", [AdjustKeys.ManipulatedSpec] = SpecKeys.VapourFraction,
                [AdjustKeys.ControlledObject] = "w", [AdjustKeys.ControlledProperty] = "temperature",
            },
            Specs = new() { [AdjustKeys.Target] = new Spec(350, "K") },
        });
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.Equal(SolveStatus.Invalid, r.Status);
        Assert.Contains(r.Messages, m => m.Text.Contains("no vapourFraction specified"));
    }

    private static SimObject Set(string id, string source, string target, string key) => new()
    {
        Id = id, Tag = id.ToUpperInvariant(), Type = ObjectType.Set,
        Refs = new() { [SetKeys.SourceObject] = source, [SetKeys.TargetObject] = target, [SetKeys.Variable] = key },
    };

    [Fact]
    public async Task SetsThatLoopOrDriveAnUnknownSpecAreRejected()
    {
        var fs = Water(300) with { };
        fs.Objects.Add(Water(320).Objects[0] with { Id = "w2", Tag = "W-2" });
        fs.Objects.Add(Set("s1", "w", "w2", SpecKeys.Temperature));
        fs.Objects.Add(Set("s2", "w2", "w", SpecKeys.Temperature));
        fs.Objects.Add(Set("s3", "w", "w2", SpecKeys.OutletPressure));
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.Equal(SolveStatus.Invalid, r.Status);
        Assert.Contains(r.Messages, m => m.Text.Contains("Sets form a loop"));
        Assert.Contains(r.Messages, m => m.Text == "S3: W-2 can't take outletPressure as a specification.");
    }

    [Fact]
    public async Task SetCopiesTheSourceSpecWithMultiplierAndOffset()
    {
        var fs = Water(300) with { };
        fs.Objects.Add(Water(400).Objects[0] with { Id = "w2", Tag = "W-2" });
        fs.Objects.Add(Set("s1", "w", "w2", SpecKeys.Temperature) with
        {
            Specs = new() { [SetKeys.Multiplier] = new Spec(1.0, "ratio"), [SetKeys.Offset] = new Spec(15.0, "dC") },
        });
        var r = await EngineFixture.Json<SolveResult>(await engine.Authed().PostAsJsonAsync("/solve", fs, Json.Options));
        Assert.Equal(SolveStatus.Converged, r.Status);
        Assert.Equal(315.0, r.Objects["w2"].Properties["temperature"], 9);
        Assert.Equal(315.0, r.Objects["s1"].Properties["targetValue"], 9);
    }
}

internal static class AdjustSolverLimits
{
    public const int Max = 40;
}
