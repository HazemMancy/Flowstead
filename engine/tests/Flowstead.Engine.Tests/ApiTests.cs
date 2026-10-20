// Flowstead engine tests — HTTP API behaviour: auth, lookups, CRUD, stored results, validation.
// Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later

using System.Net;
using System.Net.Http.Json;
using Flowstead.Engine.Api;
using Flowstead.Engine.Contracts;
using Xunit;

namespace Flowstead.Engine.Tests;

[Collection(EngineCollection.Name)]
public sealed class ApiTests(EngineFixture engine)
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
}
